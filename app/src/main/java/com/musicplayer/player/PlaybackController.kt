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
import com.musicplayer.core.network.wireguard.WireGuardTunnelManager
import com.musicplayer.data.repository.LibraryRepository
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
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackController {
    val playbackState: StateFlow<PlaybackState>
    val feedbackMessages: SharedFlow<String>
    fun restorePersistedQueue()
    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        contextLabel: String? = null,
    )
    fun playLibraryShuffle(downloadedOnly: Boolean = false)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun addToQueue(tracks: List<Track>)
    fun skipToQueueIndex(index: Int)
    fun removeFromQueue(index: Int)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun clearManualQueue()
    fun clearUpNext()
    fun reshuffleQueue()
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
    private val libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val networkMonitor: NetworkMonitor,
    private val wireGuardTunnelManager: WireGuardTunnelManager,
    private val audioOutputMonitor: AudioOutputMonitor,
    private val preferencesDataStore: UserPreferencesDataStore,
) : PlaybackController {

    private data class RollingShuffleState(
        val downloadedOnly: Boolean,
        val seenTrackIds: LinkedHashSet<String>,
    )

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _feedbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val feedbackMessages: SharedFlow<String> = _feedbackMessages.asSharedFlow()

    private val connectMutex = Mutex()
    private val qualityUpdateMutex = Mutex()
    private var controller: MediaController? = null
    private var positionTickerJob: Job? = null
    private var backgroundHydrationJob: Job? = null
    private var rollingShuffleRefillJob: Job? = null
    private var queueLoadGeneration: Long = 0L
    private var rollingShuffleState: RollingShuffleState? = null
    private var hasRestoredQueue = false
    private var needsPrepareOnPlay = false
    private var pendingRestoreIndex = 0
    private var pendingRestorePositionMs = 0L
    private var lastReportedTrackId: String? = null
    private var lastReportedPositionMs: Long = 0L
    private var lastProgressReportTimeMs: Long = 0L
    private var sessionQualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO
    private var isShuffleActive: Boolean = false
    private var unshuffledContextTrackIds: List<String> = emptyList()
    private var pendingAutoResumeAfterRouteDrop: Boolean = false
    private var routeDropTimestampMs: Long = 0L
    private var lastKnownRemotePositionMs: Long = 0L
    private var lastPlayWhenReadyForRemote: Boolean = false

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
        // wait for WireGuard routing to settle and automatically re-sync the stream in Auto mode.
        appScope.launch {
            combine(
                networkMonitor.networkState,
                audioOutputMonitor.outputState,
            ) { net, output ->
                Triple(net.isConnected, net.isMetered, output.activeDevice.preferenceKey)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { (isConnected, _, _) ->
                    if (isConnected && sessionQualitySelection == StreamQualitySelection.AUTO) {
                        wireGuardTunnelManager.awaitRoutingSettled()
                        applyStreamQuality(
                            selection = StreamQualitySelection.AUTO,
                            showFeedback = false,
                        )
                    }
                }
        }
        // Whenever WireGuard / direct-LAN routing completes a transition or recovers after a
        // Wi-Fi <-> Cellular handoff, seamlessly rebind active remote playback onto the new route
        // so ExoPlayer never stays stuck buffering or stopped on a dead socket.
        appScope.launch {
            wireGuardTunnelManager.routingReadyGeneration
                .drop(1)
                .collect {
                    recoverPlaybackForRouteChange()
                }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
            val state = _playbackState.value
            val active = state.currentTrack
            if (isPlaying) {
                pendingAutoResumeAfterRouteDrop = false
                if (!state.isLocalPlayback) {
                    lastPlayWhenReadyForRemote = true
                    lastKnownRemotePositionMs = state.positionMs
                }
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
            maybeRefillRollingShuffle()
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
            val state = _playbackState.value
            val player = controller
            val wasTryingToPlayRemote = !state.isLocalPlayback &&
                state.currentTrack != null &&
                (player?.playWhenReady == true || state.isPlaying || state.isBuffering || lastPlayWhenReadyForRemote)
            if (wasTryingToPlayRemote) {
                val bestPos = maxOf(
                    player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    state.positionMs.coerceAtLeast(0L),
                    lastKnownRemotePositionMs,
                )
                lastKnownRemotePositionMs = bestPos
                pendingAutoResumeAfterRouteDrop = true
                routeDropTimestampMs = System.currentTimeMillis()
            }
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

    override fun playTracks(
        tracks: List<Track>,
        startIndex: Int,
        shuffle: Boolean,
        contextLabel: String?,
    ) {
        if (tracks.isEmpty()) return
        appScope.launch(mainDispatcher) {
            val generation = beginNewQueueLoad()
            rollingShuffleState = null

            val previousActiveId = _playbackState.value.currentTrack?.id
            val orderedInput = if (shuffle) {
                QueueOrderHelper.shuffleForPlayback(
                    tracks = tracks,
                    avoidFirstTrackId = previousActiveId,
                )
            } else {
                tracks
            }

            val resolvedMap = streamUrlResolver.resolveBatch(
                tracks = orderedInput,
                qualitySelection = sessionQualitySelection,
            )
            if (generation != queueLoadGeneration) return@launch

            val mediaController = ensureConnected() ?: return@launch
            if (generation != queueLoadGeneration) return@launch

            val mediaItems = ArrayList<MediaItem>(orderedInput.size)
            val resolvedTracks = ArrayList<Track>(orderedInput.size)
            val newResolvedSources = LinkedHashMap<String, ResolvedAudioSource>(orderedInput.size)

            for (track in orderedInput) {
                val resolved = resolvedMap[track.id] ?: continue
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

            needsPrepareOnPlay = false
            unshuffledContextTrackIds = tracks.map { it.id }
            isShuffleActive = shuffle
            resolvedSourcesByTrackId.clear()
            resolvedSourcesByTrackId.putAll(newResolvedSources)

            val safeIndex = if (shuffle) 0 else startIndex.coerceIn(0, mediaItems.lastIndex)
            val activeTrack = resolvedTracks[safeIndex]
            val activeResolved = newResolvedSources[activeTrack.id]

            setMediaItemsAndHydrate(
                mediaController = mediaController,
                mediaItems = mediaItems,
                safeIndex = safeIndex,
                startPositionMs = 0L,
                autoPlay = true,
                generation = generation,
            )

            val resolvedContextLabel = contextLabel?.takeIf { it.isNotBlank() }
                ?: activeTrack.albumName.takeIf { name -> name.isNotBlank() }

            _playbackState.update {
                it.copy(
                    queue = resolvedTracks,
                    currentIndex = safeIndex,
                    manualQueueCount = 0,
                    queueContextLabel = resolvedContextLabel,
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

    override fun playLibraryShuffle(downloadedOnly: Boolean) {
        appScope.launch(mainDispatcher) {
            val generation = beginNewQueueLoad()
            val previousActiveId = _playbackState.value.currentTrack?.id
            val sampled = libraryRepository.getRandomTracks(
                limit = SHUFFLE_WINDOW_SIZE,
                downloadedOnly = downloadedOnly,
            )
            if (generation != queueLoadGeneration || sampled.isEmpty()) return@launch

            val ordered = if (sampled.size > 1 && previousActiveId != null && sampled.first().id == previousActiveId) {
                sampled.drop(1) + sampled.first()
            } else {
                sampled
            }

            val resolvedMap = streamUrlResolver.resolveBatch(
                tracks = ordered,
                qualitySelection = sessionQualitySelection,
            )
            if (generation != queueLoadGeneration) return@launch

            val mediaController = ensureConnected() ?: return@launch
            if (generation != queueLoadGeneration) return@launch

            val mediaItems = ArrayList<MediaItem>(ordered.size)
            val resolvedTracks = ArrayList<Track>(ordered.size)
            val newResolvedSources = LinkedHashMap<String, ResolvedAudioSource>(ordered.size)

            for (track in ordered) {
                val resolved = resolvedMap[track.id] ?: continue
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

            needsPrepareOnPlay = false
            isShuffleActive = true
            unshuffledContextTrackIds = resolvedTracks.map { it.id }
            rollingShuffleState = RollingShuffleState(
                downloadedOnly = downloadedOnly,
                seenTrackIds = resolvedTracks.mapTo(LinkedHashSet()) { it.id },
            )
            resolvedSourcesByTrackId.clear()
            resolvedSourcesByTrackId.putAll(newResolvedSources)

            val activeTrack = resolvedTracks.first()
            val activeResolved = newResolvedSources[activeTrack.id]

            setMediaItemsAndHydrate(
                mediaController = mediaController,
                mediaItems = mediaItems,
                safeIndex = 0,
                startPositionMs = 0L,
                autoPlay = true,
                generation = generation,
            )

            val contextLabel = if (downloadedOnly) "Downloaded" else "Your Library"
            _playbackState.update {
                it.copy(
                    queue = resolvedTracks,
                    currentIndex = 0,
                    manualQueueCount = 0,
                    queueContextLabel = contextLabel,
                    currentTrack = activeTrack,
                    isLocalPlayback = activeResolved?.isLocal == true,
                    transcodedBitrateKbps = activeResolved?.transcodedBitrateKbps,
                    streamQualitySelection = sessionQualitySelection,
                    shuffleEnabled = true,
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
            if (_playbackState.value.queue.isEmpty()) {
                playTracks(listOf(track), startIndex = 0, shuffle = false)
                return@launch
            }
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            backgroundHydrationJob?.join()
            val resolved = streamUrlResolver.resolve(track, sessionQualitySelection) ?: return@launch
            resolvedSourcesByTrackId[track.id] = resolved

            val latest = _playbackState.value
            if (latest.queue.isEmpty()) {
                playTracks(listOf(track), startIndex = 0, shuffle = false)
                return@launch
            }
            val insertIndex = (latest.currentIndex + 1)
                .coerceIn(0, minOf(latest.queue.size, mediaController.mediaItemCount))
            val mutated = QueueOrderHelper.insertNext(
                queue = latest.queue,
                currentIndex = latest.currentIndex,
                track = track,
                manualQueueCount = latest.manualQueueCount,
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
            backgroundHydrationJob?.join()

            val resolvedMap = streamUrlResolver.resolveBatch(tracks, sessionQualitySelection)
            val resolvedTracks = mutableListOf<Track>()
            val mediaItems = mutableListOf<MediaItem>()
            tracks.forEach { track ->
                val resolved = resolvedMap[track.id] ?: return@forEach
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

            val latestState = _playbackState.value
            val insertIndex = (latestState.currentIndex + 1 + latestState.manualQueueCount)
                .coerceIn(0, latestState.queue.size)
            val mutated = QueueOrderHelper.enqueueManual(
                queue = latestState.queue,
                currentIndex = latestState.currentIndex,
                manualQueueCount = latestState.manualQueueCount,
                tracks = resolvedTracks,
            )
            mediaItems.chunked(MEDIA_CONTROLLER_CHUNK_SIZE).forEachIndexed { chunkIdx, chunk ->
                mediaController.addMediaItems(insertIndex + chunkIdx * MEDIA_CONTROLLER_CHUNK_SIZE, chunk)
            }
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
            if (index !in _playbackState.value.queue.indices) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            if (index >= mediaController.mediaItemCount) {
                backgroundHydrationJob?.join()
            }
            val latest = _playbackState.value
            if (index !in latest.queue.indices || index !in 0 until mediaController.mediaItemCount) {
                return@launch
            }
            val updatedManual = QueueOrderHelper.adjustManualCountOnIndexChange(
                oldIndex = latest.currentIndex,
                newIndex = index,
                manualQueueCount = latest.manualQueueCount,
                queueSize = latest.queue.size,
            )
            _playbackState.update { it.copy(manualQueueCount = updatedManual) }
            mediaController.seekTo(index, 0L)
            mediaController.play()
            syncFromPlayer()
            persistPositionAsync()
            maybeRefillRollingShuffle()
        }
    }

    override fun removeFromQueue(index: Int) {
        appScope.launch(mainDispatcher) {
            if (index !in _playbackState.value.queue.indices) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            backgroundHydrationJob?.join()

            val latest = _playbackState.value
            if (index !in latest.queue.indices) return@launch
            val mutated = QueueOrderHelper.removeAt(
                queue = latest.queue,
                currentIndex = latest.currentIndex,
                removeIndex = index,
                manualQueueCount = latest.manualQueueCount,
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
            maybeRefillRollingShuffle()
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        appScope.launch(mainDispatcher) {
            if (fromIndex == toIndex) return@launch
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            backgroundHydrationJob?.join()

            val latest = _playbackState.value
            if (fromIndex !in latest.queue.indices || toIndex !in latest.queue.indices) {
                return@launch
            }
            val mutated = QueueOrderHelper.move(
                queue = latest.queue,
                currentIndex = latest.currentIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
                manualQueueCount = latest.manualQueueCount,
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
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            backgroundHydrationJob?.join()

            val latest = _playbackState.value
            val activeIndex = latest.currentIndex
            if (activeIndex !in latest.queue.indices || latest.manualQueueCount <= 0) return@launch

            val start = activeIndex + 1
            val end = (start + latest.manualQueueCount).coerceAtMost(mediaController.mediaItemCount)
            if (start < end) {
                mediaController.removeMediaItems(start, end)
            }
            val mutated = QueueOrderHelper.clearManualQueue(
                queue = latest.queue,
                currentIndex = activeIndex,
                manualQueueCount = latest.manualQueueCount,
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
            val mediaController = ensureConnected() ?: return@launch
            if (needsPrepareOnPlay) {
                hydrateControllerFromState(mediaController, autoPlay = false)
            }
            backgroundHydrationJob?.cancel()
            backgroundHydrationJob = null
            rollingShuffleRefillJob?.cancel()
            rollingShuffleRefillJob = null
            rollingShuffleState = null

            val latest = _playbackState.value
            val activeIndex = latest.currentIndex
            if (activeIndex !in latest.queue.indices || activeIndex >= latest.queue.lastIndex) return@launch

            val removeStart = activeIndex + 1
            if (removeStart < mediaController.mediaItemCount) {
                mediaController.removeMediaItems(removeStart, mediaController.mediaItemCount)
            }
            val trimmed = latest.queue.subList(0, removeStart).toList()
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
                pendingAutoResumeAfterRouteDrop = false
                hydrateControllerFromState(mediaController, autoPlay = true)
                return@launch
            }
            if (mediaController.isPlaying || mediaController.playWhenReady) {
                pendingAutoResumeAfterRouteDrop = false
                lastPlayWhenReadyForRemote = false
                mediaController.pause()
            } else {
                pendingAutoResumeAfterRouteDrop = false
                if (mediaController.playbackState == Player.STATE_ENDED && mediaController.mediaItemCount > 0) {
                    mediaController.seekTo(0, 0L)
                } else if (mediaController.playbackState == Player.STATE_IDLE && mediaController.mediaItemCount > 0) {
                    val resumePos = maxOf(
                        mediaController.currentPosition.coerceAtLeast(0L),
                        _playbackState.value.positionMs.coerceAtLeast(0L),
                    )
                    val idx = mediaController.currentMediaItemIndex
                    if (idx in 0 until mediaController.mediaItemCount && resumePos > 0L) {
                        mediaController.seekTo(idx, resumePos)
                    }
                    mediaController.prepare()
                }
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
        appScope.launch(mainDispatcher) {
            val nextShuffle = !isShuffleActive
            isShuffleActive = nextShuffle
            val current = _playbackState.value
            if (current.queue.isEmpty() || current.currentIndex !in current.queue.indices) {
                _playbackState.update { it.copy(shuffleEnabled = nextShuffle) }
                return@launch
            }

            if (nextShuffle && unshuffledContextTrackIds.isEmpty()) {
                unshuffledContextTrackIds = current.queue.map { it.id }
            }

            val mutated = if (nextShuffle) {
                QueueOrderHelper.reshuffleUpcoming(
                    queue = current.queue,
                    currentIndex = current.currentIndex,
                    manualQueueCount = current.manualQueueCount,
                )
            } else {
                QueueOrderHelper.restoreContextOrder(
                    queue = current.queue,
                    currentIndex = current.currentIndex,
                    manualQueueCount = current.manualQueueCount,
                    originalContextTrackIds = unshuffledContextTrackIds,
                )
            }

            applyUpcomingQueueMutation(
                mutated = mutated,
                shuffleEnabled = nextShuffle,
            )
        }
    }

    override fun reshuffleQueue() {
        appScope.launch(mainDispatcher) {
            val current = _playbackState.value
            if (current.queue.isEmpty() || current.currentIndex !in current.queue.indices) return@launch
            val upcomingCount = (current.queue.size - 1 - current.currentIndex).coerceAtLeast(0)
            if (upcomingCount < 2) return@launch

            if (unshuffledContextTrackIds.isEmpty()) {
                unshuffledContextTrackIds = current.queue.map { it.id }
            }
            isShuffleActive = true

            val rolling = rollingShuffleState
            val mutated = if (rolling != null) {
                val safeManual = current.manualQueueCount.coerceIn(0, upcomingCount)
                val pinnedEnd = current.currentIndex + 1 + safeManual
                val prefixAndManual = current.queue.subList(0, pinnedEnd)
                val pinnedIds = prefixAndManual.mapTo(LinkedHashSet()) { it.id }
                val freshContext = libraryRepository.getRandomTracks(
                    limit = (SHUFFLE_WINDOW_SIZE - 1).coerceAtLeast(1),
                    downloadedOnly = rolling.downloadedOnly,
                    excludeTrackIds = pinnedIds,
                )
                if (freshContext.size >= 2) {
                    rolling.seenTrackIds.clear()
                    rolling.seenTrackIds.addAll(pinnedIds)
                    rolling.seenTrackIds.addAll(freshContext.map { it.id })
                    val combined = prefixAndManual + freshContext
                    unshuffledContextTrackIds = combined.map { it.id }
                    com.musicplayer.player.queue.QueueMutationResult(
                        queue = combined,
                        currentIndex = current.currentIndex,
                        manualQueueCount = safeManual,
                    )
                } else {
                    QueueOrderHelper.reshuffleUpcoming(
                        queue = current.queue,
                        currentIndex = current.currentIndex,
                        manualQueueCount = current.manualQueueCount,
                    )
                }
            } else {
                QueueOrderHelper.reshuffleUpcoming(
                    queue = current.queue,
                    currentIndex = current.currentIndex,
                    manualQueueCount = current.manualQueueCount,
                )
            }

            applyUpcomingQueueMutation(
                mutated = mutated,
                shuffleEnabled = true,
            )
            _feedbackMessages.tryEmit("Queue reshuffled")
        }
    }

    private suspend fun applyUpcomingQueueMutation(
        mutated: com.musicplayer.player.queue.QueueMutationResult,
        shuffleEnabled: Boolean,
    ) {
        val generation = beginNewQueueLoad()
        val replaceStart = (mutated.currentIndex + 1).coerceAtMost(mutated.queue.size)
        val upcomingTracks = mutated.queue.subList(replaceStart, mutated.queue.size)
        val missingTracks = upcomingTracks.filter { it.id !in resolvedSourcesByTrackId }
        if (missingTracks.isNotEmpty()) {
            val resolvedMissing = streamUrlResolver.resolveBatch(missingTracks, sessionQualitySelection)
            if (generation != queueLoadGeneration) return
            resolvedSourcesByTrackId.putAll(resolvedMissing)
        }

        if (!needsPrepareOnPlay) {
            val mediaController = ensureConnected()
            if (mediaController != null && generation == queueLoadGeneration) {
                mediaController.shuffleModeEnabled = false
                if (replaceStart < mediaController.mediaItemCount) {
                    mediaController.removeMediaItems(replaceStart, mediaController.mediaItemCount)
                }
                val newMediaItems = upcomingTracks.mapNotNull { track ->
                    val resolved = resolvedSourcesByTrackId[track.id] ?: return@mapNotNull null
                    track.toMediaItem(
                        uri = resolved.uri,
                        isLocal = resolved.isLocal,
                        transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                        mimeType = resolved.mimeType,
                    )
                }
                if (newMediaItems.isNotEmpty()) {
                    val insertPos = replaceStart.coerceAtMost(mediaController.mediaItemCount)
                    val initialEnd = minOf(newMediaItems.size, MEDIA_CONTROLLER_INITIAL_BATCH_SIZE)
                    mediaController.addMediaItems(insertPos, newMediaItems.subList(0, initialEnd))
                    if (initialEnd < newMediaItems.size) {
                        backgroundHydrationJob = appScope.launch(mainDispatcher) {
                            newMediaItems.subList(initialEnd, newMediaItems.size)
                                .chunked(MEDIA_CONTROLLER_CHUNK_SIZE)
                                .forEach { chunk ->
                                    yield()
                                    if (generation != queueLoadGeneration || !mediaController.isConnected) return@launch
                                    mediaController.addMediaItems(mediaController.mediaItemCount, chunk)
                                }
                        }
                    }
                }
            }
        }

        _playbackState.update {
            it.copy(
                queue = mutated.queue,
                currentIndex = mutated.currentIndex,
                manualQueueCount = mutated.manualQueueCount,
                shuffleEnabled = shuffleEnabled,
            )
        }
        persistQueueAsync()
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

    private suspend fun recoverPlaybackForRouteChange() = withContext(mainDispatcher) {
        val state = _playbackState.value
        if (needsPrepareOnPlay || state.queue.isEmpty() || state.currentIndex !in state.queue.indices) {
            return@withContext
        }
        val activeTrack = state.queue[state.currentIndex]
        val activeResolved = resolvedSourcesByTrackId[activeTrack.id]
        // Never interrupt locally downloaded playback during network switches
        if (state.isLocalPlayback || activeResolved?.isLocal == true) {
            pendingAutoResumeAfterRouteDrop = false
            return@withContext
        }

        val mediaController = ensureConnected() ?: return@withContext
        val recentErrorAutoResume = pendingAutoResumeAfterRouteDrop &&
            (System.currentTimeMillis() - routeDropTimestampMs) <= AUTO_RESUME_WINDOW_MS
        val isActivelyStreaming = mediaController.isPlaying ||
            mediaController.playWhenReady ||
            state.isBuffering

        if (!isActivelyStreaming && !recentErrorAutoResume) {
            return@withContext
        }

        pendingAutoResumeAfterRouteDrop = false
        applyStreamQuality(
            selection = sessionQualitySelection,
            showFeedback = false,
            forceReload = true,
            forceAutoPlay = true,
        )
    }

    private suspend fun applyStreamQuality(
        selection: StreamQualitySelection,
        showFeedback: Boolean,
        forceReload: Boolean = false,
        forceAutoPlay: Boolean = false,
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

            val newResolvedSources = streamUrlResolver.resolveBatch(
                tracks = queue,
                qualitySelection = selection,
                fallbackToAuto = true,
            )
            if (queue.any { it.id !in newResolvedSources }) return@withLock
            val newItems = queue.map { track ->
                val resolved = newResolvedSources[track.id] ?: return@withLock
                track.toMediaItem(
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
            // (unless forceReload is requested after a network/WireGuard route handoff).
            if (!showFeedback && !forceReload && previousActiveResolved == activeResolved) {
                _playbackState.update {
                    it.copy(
                        isLocalPlayback = activeResolved.isLocal,
                        transcodedBitrateKbps = activeResolved.transcodedBitrateKbps,
                        streamQualitySelection = selection,
                    )
                }
                return@withLock
            }

            val controllerPos = mediaController.currentPosition.coerceAtLeast(0L)
            val savedPositionMs = when {
                controllerPos > 0L -> controllerPos
                forceReload && lastKnownRemotePositionMs > 0L -> lastKnownRemotePositionMs
                else -> state.positionMs.coerceAtLeast(0L)
            }
            val wasPlaying = forceAutoPlay || mediaController.playWhenReady
            val generation = beginNewQueueLoad()

            // Seamlessly swap media items while preserving exact playback position & play state
            setMediaItemsAndHydrate(
                mediaController = mediaController,
                mediaItems = newItems,
                safeIndex = currentIndex,
                startPositionMs = savedPositionMs,
                autoPlay = wasPlaying,
                generation = generation,
            )
            if (!wasPlaying) {
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
            beginNewQueueLoad()
            rollingShuffleState = null
            pendingAutoResumeAfterRouteDrop = false
            lastPlayWhenReadyForRemote = false
            lastKnownRemotePositionMs = 0L
            reportStoppedForLastTrack()
            stopPositionTicker()
            needsPrepareOnPlay = false
            isShuffleActive = false
            unshuffledContextTrackIds = emptyList()
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
        val generation = beginNewQueueLoad()
        val state = _playbackState.value
        val newResolvedSources = streamUrlResolver.resolveBatch(
            tracks = state.queue,
            qualitySelection = sessionQualitySelection,
        )
        val resolvedTracks = ArrayList<Track>(state.queue.size)
        val items = ArrayList<MediaItem>(state.queue.size)
        var mappedRestoreIndex = 0
        state.queue.forEachIndexed { oldIdx, track ->
            val resolved = newResolvedSources[track.id] ?: return@forEachIndexed
            if (oldIdx == pendingRestoreIndex) {
                mappedRestoreIndex = resolvedTracks.size
            }
            resolvedTracks += track
            items += track.toMediaItem(
                uri = resolved.uri,
                isLocal = resolved.isLocal,
                transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                mimeType = resolved.mimeType,
            )
        }
        if (items.isEmpty()) {
            _playbackState.update {
                it.copy(errorMessage = "No playable tracks available while offline")
            }
            return
        }
        needsPrepareOnPlay = false

        resolvedSourcesByTrackId.clear()
        resolvedSourcesByTrackId.putAll(newResolvedSources)

        val safeIndex = mappedRestoreIndex.coerceIn(0, items.lastIndex)
        val activeResolved = resolvedTracks.getOrNull(safeIndex)?.id?.let { newResolvedSources[it] }
        setMediaItemsAndHydrate(
            mediaController = mediaController,
            mediaItems = items,
            safeIndex = safeIndex,
            startPositionMs = pendingRestorePositionMs,
            autoPlay = autoPlay,
            generation = generation,
        )
        _playbackState.update {
            it.copy(
                queue = resolvedTracks,
                currentIndex = safeIndex,
                currentTrack = resolvedTracks.getOrNull(safeIndex),
                isLocalPlayback = activeResolved?.isLocal ?: it.isLocalPlayback,
                transcodedBitrateKbps = activeResolved?.transcodedBitrateKbps,
                streamQualitySelection = sessionQualitySelection,
                errorMessage = null,
            )
        }
        syncFromPlayer()
    }

    private fun beginNewQueueLoad(): Long {
        backgroundHydrationJob?.cancel()
        backgroundHydrationJob = null
        rollingShuffleRefillJob?.cancel()
        rollingShuffleRefillJob = null
        queueLoadGeneration += 1L
        return queueLoadGeneration
    }

    private fun setMediaItemsAndHydrate(
        mediaController: MediaController,
        mediaItems: List<MediaItem>,
        safeIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean,
        generation: Long,
    ) {
        // Keep ExoPlayer's internal shuffle off so MediaController timeline and QueueSheet
        // always match the true shuffled track order 1:1.
        mediaController.shuffleModeEnabled = false

        if (mediaItems.size <= MEDIA_CONTROLLER_INITIAL_BATCH_SIZE) {
            mediaController.setMediaItems(mediaItems, safeIndex, startPositionMs)
            mediaController.prepare()
            if (autoPlay) mediaController.play()
            return
        }

        if (safeIndex < MEDIA_CONTROLLER_CHUNK_SIZE) {
            val initialEnd = minOf(
                mediaItems.size,
                maxOf(MEDIA_CONTROLLER_INITIAL_BATCH_SIZE, safeIndex + 20),
            )
            mediaController.setMediaItems(mediaItems.subList(0, initialEnd), safeIndex, startPositionMs)
            mediaController.prepare()
            if (autoPlay) mediaController.play()

            if (initialEnd < mediaItems.size) {
                backgroundHydrationJob = appScope.launch(mainDispatcher) {
                    mediaItems.subList(initialEnd, mediaItems.size)
                        .chunked(MEDIA_CONTROLLER_CHUNK_SIZE)
                        .forEach { chunk ->
                            yield()
                            if (generation != queueLoadGeneration || !mediaController.isConnected) return@launch
                            mediaController.addMediaItems(mediaController.mediaItemCount, chunk)
                        }
                }
            }
            return
        }

        // Large non-shuffled queue starting deep inside the list: populate in binder-safe chunks
        val chunks = mediaItems.chunked(MEDIA_CONTROLLER_CHUNK_SIZE)
        mediaController.setMediaItems(chunks.first(), 0, 0L)
        for (i in 1 until chunks.size) {
            mediaController.addMediaItems(mediaController.mediaItemCount, chunks[i])
        }
        mediaController.seekTo(safeIndex, startPositionMs)
        mediaController.prepare()
        if (autoPlay) mediaController.play()
    }

    private fun maybeRefillRollingShuffle() {
        val rolling = rollingShuffleState ?: return
        if (!isShuffleActive || needsPrepareOnPlay) return
        if (rollingShuffleRefillJob?.isActive == true) return
        val state = _playbackState.value
        if (state.queue.isEmpty() || state.currentIndex !in state.queue.indices) return
        val remaining = state.queue.size - 1 - state.currentIndex
        if (remaining > SHUFFLE_REFILL_THRESHOLD) return

        val generation = queueLoadGeneration
        rollingShuffleRefillJob = appScope.launch(mainDispatcher) {
            val excludeIds = rolling.seenTrackIds.toSet()
            var freshTracks = libraryRepository.getRandomTracks(
                limit = SHUFFLE_REFILL_BATCH_SIZE,
                downloadedOnly = rolling.downloadedOnly,
                excludeTrackIds = excludeIds,
            )
            if (freshTracks.isEmpty() && excludeIds.size > _playbackState.value.queue.size) {
                val currentIds = _playbackState.value.queue.mapTo(LinkedHashSet()) { it.id }
                rolling.seenTrackIds.clear()
                rolling.seenTrackIds.addAll(currentIds)
                freshTracks = libraryRepository.getRandomTracks(
                    limit = SHUFFLE_REFILL_BATCH_SIZE,
                    downloadedOnly = rolling.downloadedOnly,
                    excludeTrackIds = currentIds,
                )
            }
            if (generation != queueLoadGeneration || rollingShuffleState !== rolling || freshTracks.isEmpty()) {
                return@launch
            }

            val resolvedMap = streamUrlResolver.resolveBatch(
                tracks = freshTracks,
                qualitySelection = sessionQualitySelection,
            )
            if (generation != queueLoadGeneration || rollingShuffleState !== rolling) return@launch

            val mediaController = ensureConnected() ?: return@launch
            backgroundHydrationJob?.join()
            if (generation != queueLoadGeneration || rollingShuffleState !== rolling) return@launch

            val existingIds = _playbackState.value.queue.mapTo(HashSet()) { it.id }
            val appendedTracks = ArrayList<Track>(freshTracks.size)
            val appendedItems = ArrayList<MediaItem>(freshTracks.size)
            for (track in freshTracks) {
                if (track.id in existingIds) continue
                val resolved = resolvedMap[track.id] ?: continue
                resolvedSourcesByTrackId[track.id] = resolved
                appendedTracks += track
                appendedItems += track.toMediaItem(
                    uri = resolved.uri,
                    isLocal = resolved.isLocal,
                    transcodedBitrateKbps = resolved.transcodedBitrateKbps,
                    mimeType = resolved.mimeType,
                )
            }
            if (appendedItems.isEmpty()) return@launch

            appendedItems.chunked(MEDIA_CONTROLLER_CHUNK_SIZE).forEach { chunk ->
                if (generation != queueLoadGeneration || !mediaController.isConnected) return@launch
                mediaController.addMediaItems(mediaController.mediaItemCount, chunk)
                yield()
            }
            if (generation != queueLoadGeneration) return@launch

            rolling.seenTrackIds.addAll(appendedTracks.map { it.id })
            unshuffledContextTrackIds = unshuffledContextTrackIds + appendedTracks.map { it.id }
            _playbackState.update { current ->
                current.copy(queue = current.queue + appendedTracks)
            }
            persistQueueAsync()
        }
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
                shuffleEnabled = isShuffleActive,
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
                    if (!_playbackState.value.isLocalPlayback) {
                        lastKnownRemotePositionMs = pos
                        lastPlayWhenReadyForRemote = true
                    }
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
        private const val POSITION_POLL_INTERVAL_MS = 250L
        private const val PROGRESS_REPORT_INTERVAL_MS = 15_000L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        internal const val SHUFFLE_WINDOW_SIZE = 300
        internal const val SHUFFLE_REFILL_THRESHOLD = 25
        internal const val SHUFFLE_REFILL_BATCH_SIZE = 150
        private const val MEDIA_CONTROLLER_INITIAL_BATCH_SIZE = 25
        private const val MEDIA_CONTROLLER_CHUNK_SIZE = 100
        private const val AUTO_RESUME_WINDOW_MS = 120_000L
    }
}
