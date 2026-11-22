package com.musicplayer.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.ApplicationScope
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.model.Track
import com.musicplayer.player.engine.StreamUrlResolver
import com.musicplayer.player.service.MusicPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackController {
    val playbackState: StateFlow<PlaybackState>
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false)
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun stopAndClear()
    fun clearError()
}

@Singleton
class MediaControllerPlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:Dispatcher(AppDispatcher.Main) private val mainDispatcher: CoroutineDispatcher,
    private val streamUrlResolver: StreamUrlResolver,
) : PlaybackController {

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val connectMutex = Mutex()
    private var controller: MediaController? = null
    private var positionTickerJob: Job? = null
    private val tracksById = LinkedHashMap<String, Track>()
    private var localIds: Set<String> = emptySet()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
            if (isPlaying) startPositionTicker() else stopPositionTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncFromPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            _playbackState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Playback failed",
                )
            }
        }
    }

    override fun playTracks(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch

            val mediaItems = mutableListOf<MediaItem>()
            val resolvedTracks = mutableListOf<Track>()
            val resolvedLocals = mutableSetOf<String>()

            tracks.forEach { track ->
                val resolved = streamUrlResolver.resolve(track) ?: return@forEach
                resolvedTracks += track
                tracksById[track.id] = track
                if (resolved.isLocal) resolvedLocals += track.id
                mediaItems += track.toMediaItem(resolved.uri, resolved.isLocal)
            }

            if (mediaItems.isEmpty()) {
                _playbackState.update {
                    it.copy(errorMessage = "Unable to resolve audio stream URL")
                }
                return@launch
            }

            localIds = resolvedLocals
            val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)

            mediaController.shuffleModeEnabled = shuffle
            mediaController.setMediaItems(mediaItems, safeIndex, 0L)
            mediaController.prepare()
            mediaController.play()

            _playbackState.update {
                it.copy(
                    queue = resolvedTracks,
                    currentIndex = safeIndex,
                    currentTrack = resolvedTracks[safeIndex],
                    isLocalPlayback = resolvedTracks[safeIndex].id in localIds,
                    shuffleEnabled = shuffle,
                    errorMessage = null,
                )
            }
            syncFromPlayer()
        }
    }

    override fun togglePlayPause() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                mediaController.play()
            }
        }
    }

    override fun skipToNext() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            if (mediaController.hasNextMediaItem()) {
                mediaController.seekToNextMediaItem()
            }
        }
    }

    override fun skipToPrevious() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            if (mediaController.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                mediaController.seekTo(0L)
            } else if (mediaController.hasPreviousMediaItem()) {
                mediaController.seekToPreviousMediaItem()
            } else {
                mediaController.seekTo(0L)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            mediaController.seekTo(positionMs.coerceAtLeast(0L))
            _playbackState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
        }
    }

    override fun toggleShuffle() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            mediaController.shuffleModeEnabled = !mediaController.shuffleModeEnabled
        }
    }

    override fun cycleRepeatMode() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            val nextMode = _playbackState.value.repeatMode.next()
            mediaController.repeatMode = when (nextMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    override fun stopAndClear() {
        appScope.launch(mainDispatcher) {
            stopPositionTicker()
            controller?.stop()
            controller?.clearMediaItems()
            tracksById.clear()
            localIds = emptySet()
            _playbackState.value = PlaybackState()
        }
    }

    override fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    private suspend fun ensureConnected(): MediaController? = connectMutex.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        return runCatching {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, MusicPlaybackService::class.java),
            )
            val built = withContext(mainDispatcher) {
                MediaController.Builder(context, sessionToken).buildAsync().await()
            }
            built.addListener(playerListener)
            controller = built
            built
        }.onFailure { err ->
            _playbackState.update {
                it.copy(errorMessage = err.localizedMessage ?: "Failed to connect to audio service")
            }
        }.getOrNull()
    }

    private fun syncFromPlayer() {
        val player = controller ?: return
        val currentMediaId = player.currentMediaItem?.mediaId
        val activeTrack = currentMediaId?.let { tracksById[it] } ?: _playbackState.value.currentTrack
        val duration = player.duration.takeIf { it > 0L } ?: activeTrack?.durationMs ?: 0L
        val repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        val isLocal = player.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_LOCAL)
            ?: (currentMediaId in localIds)

        _playbackState.update { current ->
            current.copy(
                currentTrack = activeTrack,
                currentIndex = player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = repeatMode,
                isLocalPlayback = isLocal,
            )
        }
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = appScope.launch(mainDispatcher) {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
                    _playbackState.update { current ->
                        current.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = player.duration.takeIf { it > 0L } ?: current.durationMs,
                        )
                    }
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun Track.toMediaItem(uri: String, isLocal: Boolean): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_LOCAL, isLocal)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumName)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val EXTRA_IS_LOCAL = "com.musicplayer.extra.IS_LOCAL"
        private const val POSITION_POLL_INTERVAL_MS = 500L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
