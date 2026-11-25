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
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.NetworkMonitor
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.engine.AudioOutputMonitor
import com.musicplayer.player.engine.ResolvedAudioSource
import com.musicplayer.player.engine.StreamUrlResolver
import com.musicplayer.player.queue.QueueOrderHelper
import com.musicplayer.player.queue.QueuePersistenceManager
import com.musicplayer.player.service.MusicPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    val feedbackMessages: SharedFlow<String>
    fun restorePersistedQueue()
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun addToQueue(tracks: List<Track>)
    fun skipToQueueIndex(index: Int)
    fun removeFromQueue(index: Int)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun clearManualQueue()
    fun clearUpNext()
    fun updateTrackFavorite(trackId: String, isFavorite: Boolean)
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun setStreamQuality(selection: StreamQualitySelection)
    fun stopAndClear()
    fun clearError()
}

@Singleton
class MediaControllerPlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:Dispatcher(AppDispatcher.Main) private val mainDispatcher: CoroutineDispatcher,
    private val streamUrlResolver: StreamUrlResolver,
    private val queuePersistenceManager: QueuePersistenceManager,
    private val syncRepository: SyncRepository,
    private val networkMonitor: NetworkMonitor,
    private val audioOutputMonitor: AudioOutputMonitor,
    private val preferencesDataStore: UserPreferencesDataStore,
) : PlaybackController {

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _feedbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val feedbackMessages: SharedFlow<String> = _feedbackMessages.asSharedFlow()

    private val connectMutex = Mutex()
    private val qualityUpdateMutex = Mutex()
    private var controller: MediaController? = null
    private var positionTickerJob: Job? = null
    private var hasRestoredQueue = false
    private var needsPrepareOnPlay = false
    private var pendingRestoreIndex = 0
    private var pendingRestorePositionMs = 0L
    private var lastReportedTrackId: String? = null
    private var lastReportedPositionMs: Long = 0L
    private var lastProgressReportTimeMs: Long = 0L
    private var sessionQualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO

    private val resolvedSourcesByTrackId = mutableMapOf<String, ResolvedAudioSource>()

    init {
        appScope.launch {
            networkMonitor.networkState.collect { netState ->
                if (netState.isConnected) {
                    syncRepository.flushPendingSyncActions()
                }
            }
        }
        // Whenever the user changes any quality-affecting setting (Mobile Data cap, Match Output
        // Device, or Bluetooth Hi-Res override), immediately sync playback back to Auto so the
        // player stream and displayed quality badge always reflect the new setting.
        appScope.launch {
            preferencesDataStore.userPreferences
                .map { prefs ->
                    Triple(
                        prefs.cellularBitrateCap,
                        prefs.autoMatchOutputQuality,
                        prefs.hiResBluetoothDevices,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    applyStreamQuality(
                        selection = StreamQualitySelection.AUTO,
                        showFeedback = false,
                    )
                }
        }
        // Whenever the active network (Wi-Fi <-> Mobile Data) or audio output device changes,
        // automatically re-sync the stream when in Auto quality mode.
        appScope.launch {
            combine(
                networkMonitor.networkState,
                audioOutputMonitor.outputState,
            ) { net, output ->
                Triple(net.isConnected, net.isMetered, output.activeDevice.preferenceKey)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (sessionQualitySelection == StreamQualitySelection.AUTO) {
                        applyStreamQuality(
                            selection = StreamQualitySelection.AUTO,
                            showFeedback = false,
                        )
                    }
                }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
            val state = _playbackState.value
            val active = state.currentTrack
            if (isPlaying) {
                startPositionTicker()
                if (active != null) {
                    if (lastReportedTrackId != active.id) {
                        reportTrackTransition(active.id, state.positionMs, state.transcodedBitrateKbps != null)
                    } else {
                        reportProgressAsync(active.id, state.positionMs, isPaused = false, state.transcodedBitrateKbps != null)
                    }
                }
            } else {
                stopPositionTicker()
                persistPositionAsync()
                if (active != null) {
                    lastReportedPositionMs = state.positionMs
                    reportProgressAsync(active.id, state.positionMs, isPaused = true, state.transcodedBitrateKbps != null)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromPlayer()
            if (playbackState == Player.STATE_ENDED) {
                reportStoppedForLastTrack()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromPlayer()
            persistPositionAsync()
            val state = _playbackState.value
            val newTrack = state.currentTrack
            if (newTrack != null && newTrack.id != lastReportedTrackId) {
                reportTrackTransition(newTrack.id, state.positionMs, state.transcodedBitrateKbps != null)
            }
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

    override fun restorePersistedQueue() {
        if (hasRestoredQueue) return
        hasRestoredQueue = true
        appScope.launch(mainDispatcher) {
            if (_playbackState.value.queue.isNotEmpty()) return@launch
            val snapshot = queuePersistenceManager.restoreQueue() ?: return@launch
            if (_playbackState.value.queue.isNotEmpty()) return@launch

            pendingRestoreIndex = snapshot.currentIndex
            pendingRestorePositionMs = snapshot.positionMs
            needsPrepareOnPlay = true

            val activeTrack = snapshot.tracks.getOrNull(snapshot.currentIndex)
            val activeResolved = activeTrack?.let { streamUrlResolver.resolve(it, sessionQualitySelection) }
            if (activeTrack != null && activeResolved != null) {
                resolvedSourcesByTrackId[activeTrack.id] = activeResolved
            }
            _playbackState.update {
                it.copy(
                    queue = snapshot.tracks,
                    currentIndex = snapshot.currentIndex,
                    currentTrack = activeTrack,
                    positionMs = snapshot.positionMs,
                    durationMs = activeTrack?.durationMs ?: 0L,
                    isLocalPlayback = activeResolved?.isLocal ?: (activeTrack?.isDownloaded == true),
                    transcodedBitrateKbps = activeResolved?.transcodedBitrateKbps,
                    streamQualitySelection = sessionQualitySelection,
                )
            }
        }
    }

    override fun playTracks(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            needsPrepareOnPlay = false

            val mediaItems = mutableListOf<MediaItem>()
            val resolvedTracks = mutableListOf<Track>()
            val newResolvedSources = mutableMapOf<String, ResolvedAudioSource>()

            tracks.forEach { track ->
                val resolved = streamUrlResolver.resolve(track, sessionQualitySelection) ?: return@forEach
                resolvedTracks += track
                newResolvedSources[track.id] = resolved
                mediaItems += track.toMediaItem(
                    uri = resolved.uri,
                    isLocal = resolved.isLocal,
                    transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                    mimeType = resolved.mimeType,
                )
            }

            if (mediaItems.isEmpty()) {
                _playbackState.update {
                    it.copy(errorMessage = "Unable to resolve audio stream URL")
                }
                return@launch
            }

            resolvedSourcesByTrackId.clear()
            resolvedSourcesByTrackId.putAll(newResolvedSources)

            val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
            val activeTrack = resolvedTracks[safeIndex]
            val activeResolved = newResolvedSources[activeTrack.id]

            mediaController.shuffleModeEnabled = shuffle
            mediaController.setMediaItems(mediaItems, safeIndex, 0L)
            mediaController.prepare()
            mediaController.play()

            _playbackState.update {
                it.copy(
                    queue = resolvedTracks,
                    currentIndex = safeIndex,
                    manualQueueCount = 0,
                    queueContextLabel = activeTrack.albumName.takeIf { name -> name.isNotBlank() },
                    currentTrack = activeTrack,
                    isLocalPlayback = activeResolved?.isLocal == true,
                    transcodedBitrateKbps = activeResolved?.transcodedBitrateKbps,
                    streamQualitySelection = sessionQualitySelection,
                    shuffleEnabled = shuffle,
                    positionMs = 0L,
                    durationMs = activeTrack.durationMs,
                    errorMessage = null,
                )
            }
            syncFromPlayer()
            persistQueueAsync()
        }
    }

    override fun playNext(track: Track) {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (current.queue.isEmpty()) {
                playTracks(listOf(track), startIndex = 0, shuffle = false)
                return@launch
            }
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            val resolved = streamUrlResolver.resolve(track, sessionQualitySelection) ?: return@launch
            resolvedSourcesByTrackId[track.id] = resolved

            val insertIndex = (current.currentIndex + 1).coerceIn(0, current.queue.size)
            val mutated = QueueOrderHelper.insertNext(
                queue = current.queue,
                currentIndex = current.currentIndex,
                track = track,
                manualQueueCount = current.manualQueueCount,
            )
            mediaController.addMediaItem(
                insertIndex,
                track.toMediaItem(
                    uri = resolved.uri,
                    isLocal = resolved.isLocal,
                    transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                    mimeType = resolved.mimeType,
                ),
            )

            _playbackState.update {
                it.copy(
                    queue = mutated.queue,
                    currentIndex = mutated.currentIndex,
                    manualQueueCount = mutated.manualQueueCount,
                )
            }
            _feedbackMessages.tryEmit("Playing \"${track.title}\" next")
            persistQueueAsync()
        }
    }

    override fun addToQueue(track: Track) = addToQueue(listOf(track))

    override fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (current.queue.isEmpty()) {
                playTracks(tracks, startIndex = 0, shuffle = false)
                if (tracks.size == 1) {
                    _feedbackMessages.tryEmit("Added \"${tracks.first().title}\" to queue")
                }
                return@launch
            }
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }

            val resolvedTracks = mutableListOf<Track>()
            val mediaItems = mutableListOf<MediaItem>()
            tracks.forEach { track ->
                val resolved = streamUrlResolver.resolve(track, sessionQualitySelection) ?: return@forEach
                resolvedTracks += track
                resolvedSourcesByTrackId[track.id] = resolved
                mediaItems += track.toMediaItem(
                    uri = resolved.uri,
                    isLocal = resolved.isLocal,
                    transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                    mimeType = resolved.mimeType,
                )
            }
            if (mediaItems.isEmpty()) return@launch

            val insertIndex = (current.currentIndex + 1 + current.manualQueueCount)
                .coerceIn(0, current.queue.size)
            val mutated = QueueOrderHelper.enqueueManual(
                queue = current.queue,
                currentIndex = current.currentIndex,
                manualQueueCount = current.manualQueueCount,
                tracks = resolvedTracks,
            )
            mediaController.addMediaItems(insertIndex, mediaItems)
            _playbackState.update {
                it.copy(
                    queue = mutated.queue,
                    currentIndex = mutated.currentIndex,
                    manualQueueCount = mutated.manualQueueCount,
                )
            }
            val message = if (resolvedTracks.size == 1) {
                "Added \"${resolvedTracks.first().title}\" to queue"
            } else {
                "Added ${resolvedTracks.size} songs to queue"
            }
            _feedbackMessages.tryEmit(message)
            persistQueueAsync()
        }
    }

    override fun skipToQueueIndex(index: Int) {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (index !in current.queue.indices) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            val updatedManual = QueueOrderHelper.adjustManualCountOnIndexChange(
                oldIndex = current.currentIndex,
                newIndex = index,
                manualQueueCount = current.manualQueueCount,
                queueSize = current.queue.size,
            )
            _playbackState.update { it.copy(manualQueueCount = updatedManual) }
            mediaController.seekTo(index, 0L)
            mediaController.play()
            syncFromPlayer()
            persistPositionAsync()
        }
    }

    override fun removeFromQueue(index: Int) {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (index !in current.queue.indices) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }

            val mutated = QueueOrderHelper.removeAt(
                queue = current.queue,
                currentIndex = current.currentIndex,
                removeIndex = index,
                manualQueueCount = current.manualQueueCount,
            )
            if (index < mediaController.mediaItemCount) {
                mediaController.removeMediaItem(index)
            }
            _playbackState.update {
                it.copy(
                    queue = mutated.queue,
                    currentIndex = mutated.currentIndex,
                    manualQueueCount = mutated.manualQueueCount,
                    currentTrack = mutated.queue.getOrNull(mutated.currentIndex),
                )
            }
            syncFromPlayer()
            persistQueueAsync()
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (fromIndex !in current.queue.indices || toIndex !in current.queue.indices ||
                fromIndex == toIndex
            ) {
                return@launch
            }
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }

            val mutated = QueueOrderHelper.move(
                queue = current.queue,
                currentIndex = current.currentIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
                manualQueueCount = current.manualQueueCount,
            )
            if (fromIndex < mediaController.mediaItemCount && toIndex < mediaController.mediaItemCount) {
                mediaController.moveMediaItem(fromIndex, toIndex)
            }
            _playbackState.update {
                it.copy(
                    queue = mutated.queue,
                    currentIndex = mutated.currentIndex,
                    manualQueueCount = mutated.manualQueueCount,
                    currentTrack = mutated.queue.getOrNull(mutated.currentIndex),
                )
            }
            persistQueueAsync()
        }
    }

    override fun clearManualQueue() {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            val activeIndex = current.currentIndex
            if (activeIndex !in current.queue.indices || current.manualQueueCount <= 0) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }

            val start = activeIndex + 1
            val end = (start + current.manualQueueCount).coerceAtMost(mediaController.mediaItemCount)
            if (start < end) {
                mediaController.removeMediaItems(start, end)
            }
            val mutated = QueueOrderHelper.clearManualQueue(
                queue = current.queue,
                currentIndex = activeIndex,
                manualQueueCount = current.manualQueueCount,
            )
            _playbackState.update {
                it.copy(
                    queue = mutated.queue,
                    currentIndex = mutated.currentIndex,
                    manualQueueCount = 0,
                )
            }
            persistQueueAsync()
        }
    }

    override fun clearUpNext() {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            val activeIndex = current.currentIndex
            if (activeIndex !in current.queue.indices || activeIndex >= current.queue.lastIndex) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }

            val removeStart = activeIndex + 1
            if (removeStart < mediaController.mediaItemCount) {
                mediaController.removeMediaItems(removeStart, mediaController.mediaItemCount)
            }
            val trimmed = current.queue.subList(0, removeStart).toList()
            _playbackState.update {
                it.copy(
                    queue = trimmed,
                    manualQueueCount = 0,
                )
            }
            persistQueueAsync()
        }
    }

    override fun updateTrackFavorite(trackId: String, isFavorite: Boolean) {
        _playbackState.update { state ->
            val updatedQueue = state.queue.map { track ->
                if (track.id == trackId) track.copy(isFavorite = isFavorite) else track
            }
            val updatedCurrent = state.currentTrack?.let { current ->
                if (current.id == trackId) current.copy(isFavorite = isFavorite) else current
            }
            state.copy(
                queue = updatedQueue,
                currentTrack = updatedCurrent,
            )
        }
    }

    override fun togglePlayPause() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay && _playbackState.value.queue.isNotEmpty()) {
                hydrateControllerFromState(mediaController, autoPlay = true)
                return@launch
            }
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
            val current = _playbackState.value
            if (needsPrepareOnPlay && current.queue.isNotEmpty()) {
                hydrateControllerFromState(mediaController, autoPlay = true)
            }
            // Always prioritize manually queued tracks first, even when Shuffle is active
            if (current.manualQueueCount > 0 && current.currentIndex + 1 < mediaController.mediaItemCount) {
                val nextIndex = current.currentIndex + 1
                _playbackState.update {
                    it.copy(manualQueueCount = (it.manualQueueCount - 1).coerceAtLeast(0))
                }
                mediaController.seekTo(nextIndex, 0L)
                mediaController.play()
                syncFromPlayer()
            } else if (mediaController.hasNextMediaItem()) {
                mediaController.seekToNextMediaItem()
            }
        }
    }

    override fun skipToPrevious() {
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay && _playbackState.value.queue.isNotEmpty()) {
                hydrateControllerFromState(mediaController, autoPlay = true)
            }
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
            val target = positionMs.coerceAtLeast(0L)
            if (needsPrepareOnPlay) {
                pendingRestorePositionMs = target
            } else {
                mediaController.seekTo(target)
            }
            _playbackState.update { it.copy(positionMs = target) }
        }
    }

    override fun toggleShuffle() {
        val nextShuffle = !_playbackState.value.shuffleEnabled
        _playbackState.update { it.copy(shuffleEnabled = nextShuffle) }
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            mediaController.shuffleModeEnabled = nextShuffle
        }
    }

    override fun cycleRepeatMode() {
        val nextMode = _playbackState.value.repeatMode.next()
        _playbackState.update { it.copy(repeatMode = nextMode) }
        appScope.launch(mainDispatcher) {
            val mediaController = ensureConnected() ?: return@launch
            mediaController.repeatMode = when (nextMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    override fun setStreamQuality(selection: StreamQualitySelection) {
        appScope.launch(mainDispatcher) {
            applyStreamQuality(selection = selection, showFeedback = true)
        }
    }

    private suspend fun applyStreamQuality(
        selection: StreamQualitySelection,
        showFeedback: Boolean,
    ) = withContext(mainDispatcher) {
        qualityUpdateMutex.withLock {
            sessionQualitySelection = selection
            _playbackState.update { it.copy(streamQualitySelection = selection) }

            val state = _playbackState.value
            val queue = state.queue
            val currentIndex = state.currentIndex
            if (queue.isEmpty() || currentIndex !in queue.indices) return@withLock

            if (needsPrepareOnPlay) {
                val activeTrack = queue[currentIndex]
                val activeResolved = streamUrlResolver.resolve(activeTrack, selection)
                    ?: streamUrlResolver.resolve(activeTrack, StreamQualitySelection.AUTO)
                if (activeResolved != null) {
                    resolvedSourcesByTrackId[activeTrack.id] = activeResolved
                    _playbackState.update {
                        it.copy(
                            isLocalPlayback = activeResolved.isLocal,
                            transcodedBitrateKbps = activeResolved.transcodedBitrateKbps,
                            streamQualitySelection = selection,
                        )
                    }
                }
                return@withLock
            }

            val mediaController = ensureConnected() ?: return@withLock
            val activeTrack = queue[currentIndex]
            val previousActiveResolved = resolvedSourcesByTrackId[activeTrack.id]

            val newItems = mutableListOf<MediaItem>()
            val newResolvedSources = mutableMapOf<String, ResolvedAudioSource>()
            for (track in queue) {
                val resolved = streamUrlResolver.resolve(track, selection)
                    ?: streamUrlResolver.resolve(track, StreamQualitySelection.AUTO)
                    ?: return@withLock
                newResolvedSources[track.id] = resolved
                newItems += track.toMediaItem(
                    uri = resolved.uri,
                    isLocal = resolved.isLocal,
                    transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                    mimeType = resolved.mimeType,
                )
            }

            if (currentIndex !in newItems.indices) return@withLock
            val activeResolved = newResolvedSources[activeTrack.id] ?: return@withLock

            resolvedSourcesByTrackId.clear()
            resolvedSourcesByTrackId.putAll(newResolvedSources)

            // Skip reloading ExoPlayer during automatic background syncs if the active stream didn't change
            if (!showFeedback && previousActiveResolved == activeResolved) {
                _playbackState.update {
                    it.copy(
                        isLocalPlayback = activeResolved.isLocal,
                        transcodedBitrateKbps = activeResolved.transcodedBitrateKbps,
                        streamQualitySelection = selection,
                    )
                }
                return@withLock
            }

            val savedPositionMs = mediaController.currentPosition.coerceAtLeast(0L)
            val wasPlaying = mediaController.playWhenReady

            // Seamlessly swap media items while preserving exact playback position & play state
            mediaController.setMediaItems(newItems, currentIndex, savedPositionMs)
            mediaController.prepare()
            if (wasPlaying) {
                mediaController.play()
            } else {
                mediaController.pause()
            }

            _playbackState.update {
                it.copy(
                    isLocalPlayback = activeResolved.isLocal,
                    transcodedBitrateKbps = activeResolved.transcodedBitrateKbps,
                    streamQualitySelection = selection,
                    errorMessage = null,
                )
            }
            syncFromPlayer()

            if (showFeedback) {
                val resolvedLabel = when {
                    activeResolved.isLocal && activeResolved.transcodedBitrateKbps != null ->
                        "MP3 • ${activeResolved.transcodedBitrateKbps} kbps (Local)"
                    activeResolved.isLocal -> "Downloaded (Local)"
                    activeResolved.transcodedBitrateKbps != null ->
                        "MP3 • ${activeResolved.transcodedBitrateKbps} kbps"
                    else -> "Original (Lossless)"
                }
                val feedback = if (selection == StreamQualitySelection.AUTO) {
                    "Auto quality ($resolvedLabel)"
                } else {
                    "Switched to $resolvedLabel"
                }
                _feedbackMessages.tryEmit(feedback)
            }
        }
    }

    override fun stopAndClear() {
        appScope.launch(mainDispatcher) {
            reportStoppedForLastTrack()
            stopPositionTicker()
            needsPrepareOnPlay = false
            controller?.stop()
            controller?.clearMediaItems()
            resolvedSourcesByTrackId.clear()
            _playbackState.value = PlaybackState()
            queuePersistenceManager.clear()
        }
    }

    override fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    private suspend fun hydrateControllerFromState(
        mediaController: MediaController,
        autoPlay: Boolean,
    ) {
        val state = _playbackState.value
        val items = mutableListOf<MediaItem>()
        val newResolvedSources = mutableMapOf<String, ResolvedAudioSource>()
        state.queue.forEach { track ->
            val resolved = streamUrlResolver.resolve(track, sessionQualitySelection) ?: return@forEach
            newResolvedSources[track.id] = resolved
            items += track.toMediaItem(
                uri = resolved.uri,
                isLocal = resolved.isLocal,
                transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                mimeType = resolved.mimeType,
            )
        }
        needsPrepareOnPlay = false
        if (items.isEmpty()) return

        resolvedSourcesByTrackId.clear()
        resolvedSourcesByTrackId.putAll(newResolvedSources)

        val safeIndex = pendingRestoreIndex.coerceIn(0, items.lastIndex)
        val activeResolved = state.queue.getOrNull(safeIndex)?.id?.let { newResolvedSources[it] }
        mediaController.setMediaItems(items, safeIndex, pendingRestorePositionMs)
        mediaController.prepare()
        if (autoPlay) {
            mediaController.play()
        }
        _playbackState.update {
            it.copy(
                isLocalPlayback = activeResolved?.isLocal ?: it.isLocalPlayback,
                transcodedBitrateKbps = activeResolved?.transcodedBitrateKbps,
                streamQualitySelection = sessionQualitySelection,
            )
        }
        syncFromPlayer()
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
        val currentState = _playbackState.value
        val index = player.currentMediaItemIndex
        val activeTrack = currentState.queue.getOrNull(index)
            ?: player.currentMediaItem?.mediaId?.let { id -> currentState.queue.firstOrNull { it.id == id } }
            ?: currentState.currentTrack
        val duration = player.duration.takeIf { it > 0L } ?: activeTrack?.durationMs ?: 0L
        val repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        val resolved = activeTrack?.id?.let { resolvedSourcesByTrackId[it] }
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val isLocal = resolved?.isLocal ?: extras?.getBoolean(EXTRA_IS_LOCAL) ?: currentState.isLocalPlayback
        val transcodedBitrate = if (resolved != null) {
            resolved.transcodedBitrateKbps
        } else {
            extras?.getInt(EXTRA_TRANSCODED_BITRATE_KBPS, 0)?.takeIf { it > 0 }
        }

        val clampedNewIndex = if (currentState.queue.isEmpty()) -1 else index.coerceIn(0, currentState.queue.lastIndex)
        val adjustedManualCount = if (clampedNewIndex != currentState.currentIndex && currentState.currentIndex >= 0) {
            QueueOrderHelper.adjustManualCountOnIndexChange(
                oldIndex = currentState.currentIndex,
                newIndex = clampedNewIndex,
                manualQueueCount = currentState.manualQueueCount,
                queueSize = currentState.queue.size,
            )
        } else {
            currentState.manualQueueCount
        }

        _playbackState.update { state ->
            state.copy(
                currentTrack = activeTrack,
                currentIndex = clampedNewIndex,
                manualQueueCount = adjustedManualCount,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = repeatMode,
                isLocalPlayback = isLocal,
                transcodedBitrateKbps = transcodedBitrate,
            )
        }
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = appScope.launch(mainDispatcher) {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    lastReportedPositionMs = pos
                    _playbackState.update { current ->
                        current.copy(
                            positionMs = pos,
                            durationMs = player.duration.takeIf { it > 0L } ?: current.durationMs,
                        )
                    }
                    val now = System.currentTimeMillis()
                    val active = _playbackState.value.currentTrack
                    if (active != null && now - lastProgressReportTimeMs >= PROGRESS_REPORT_INTERVAL_MS) {
                        lastProgressReportTimeMs = now
                        reportProgressAsync(
                            trackId = active.id,
                            positionMs = pos,
                            isPaused = false,
                            isTranscoded = _playbackState.value.transcodedBitrateKbps != null,
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

    private fun reportTrackTransition(
        newTrackId: String,
        startPositionMs: Long,
        isTranscoded: Boolean,
    ) {
        val previousTrackId = lastReportedTrackId
        val previousPos = lastReportedPositionMs
        lastReportedTrackId = newTrackId
        lastReportedPositionMs = startPositionMs
        lastProgressReportTimeMs = System.currentTimeMillis()

        appScope.launch {
            if (previousTrackId != null && previousTrackId != newTrackId) {
                syncRepository.reportPlaybackStopped(previousTrackId, previousPos)
            }
            syncRepository.reportPlaybackStart(newTrackId, startPositionMs, isTranscoded)
        }
    }

    private fun reportProgressAsync(
        trackId: String,
        positionMs: Long,
        isPaused: Boolean,
        isTranscoded: Boolean,
    ) {
        appScope.launch {
            syncRepository.reportPlaybackProgress(trackId, positionMs, isPaused, isTranscoded)
        }
    }

    private fun reportStoppedForLastTrack() {
        val trackId = lastReportedTrackId ?: return
        val pos = lastReportedPositionMs
        lastReportedTrackId = null
        lastReportedPositionMs = 0L
        appScope.launch {
            syncRepository.reportPlaybackStopped(trackId, pos)
        }
    }

    private fun persistQueueAsync() {
        val snapshot = _playbackState.value
        appScope.launch {
            queuePersistenceManager.saveQueue(
                tracks = snapshot.queue,
                currentIndex = snapshot.currentIndex.coerceAtLeast(0),
                positionMs = snapshot.positionMs,
            )
        }
    }

    private fun persistPositionAsync() {
        val snapshot = _playbackState.value
        if (snapshot.queue.isEmpty()) return
        appScope.launch {
            queuePersistenceManager.savePlaybackPosition(
                currentIndex = snapshot.currentIndex.coerceAtLeast(0),
                positionMs = snapshot.positionMs,
            )
        }
    }

    private fun Track.toMediaItem(
        uri: String,
        isLocal: Boolean,
        transcodedBitrateKbps: Int?,
        mimeType: String? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_LOCAL, isLocal)
            if (transcodedBitrateKbps != null) {
                putInt(EXTRA_TRANSCODED_BITRATE_KBPS, transcodedBitrateKbps)
            }
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
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val EXTRA_IS_LOCAL = "com.musicplayer.extra.IS_LOCAL"
        private const val EXTRA_TRANSCODED_BITRATE_KBPS = "com.musicplayer.extra.TRANSCODED_BITRATE_KBPS"
        private const val POSITION_POLL_INTERVAL_MS = 500L
        private const val PROGRESS_REPORT_INTERVAL_MS = 15_000L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
