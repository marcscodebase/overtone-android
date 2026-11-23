package com.musicplayer.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AudioOutputState
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.LyricsRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackState
import com.musicplayer.player.engine.AudioOutputMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val lyricsRepository: LyricsRepository,
    private val audioOutputMonitor: AudioOutputMonitor,
    private val preferencesDataStore: UserPreferencesDataStore,
    offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState
    val feedbackMessages: SharedFlow<String> = playbackController.feedbackMessages
    val audioOutputState: StateFlow<AudioOutputState> = audioOutputMonitor.outputState

    val recentlyPlayedTracks: StateFlow<List<Track>> = libraryRepository
        .observeRecentlyPlayedTracks(limit = 50)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val lowQualityBitrateKbps: StateFlow<Int> = preferencesDataStore.userPreferences
        .map { it.lowQualityBitrate.kbps }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Mp3Bitrate.KBPS_128.kbps,
        )

    val autoMatchOutputQuality: StateFlow<Boolean> = preferencesDataStore.userPreferences
        .map { it.autoMatchOutputQuality }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val hiResBluetoothDevices: StateFlow<Set<String>> = preferencesDataStore.userPreferences
        .map { it.hiResBluetoothDevices }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    val isOffline: StateFlow<Boolean> = offlineStateProvider.offlineStatus
        .map { it.isOffline }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    init {
        playbackController.restorePersistedQueue()
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    if (trackId == null) {
                        _lyricsState.value = LyricsUiState()
                    } else {
                        _lyricsState.value = LyricsUiState(trackId = trackId, isLoading = true)
                        val loaded = lyricsRepository.getLyrics(trackId)
                        _lyricsState.value = LyricsUiState(
                            trackId = trackId,
                            isLoading = false,
                            lyrics = loaded,
                        )
                    }
                }
        }
    }

    fun onTogglePlayPause() = playbackController.togglePlayPause()
    fun onSkipNext() = playbackController.skipToNext()
    fun onSkipPrevious() = playbackController.skipToPrevious()
    fun onSeekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun onToggleShuffle() = playbackController.toggleShuffle()
    fun onCycleRepeatMode() = playbackController.cycleRepeatMode()
    fun onSelectStreamQuality(selection: StreamQualitySelection) = playbackController.setStreamQuality(selection)
    fun onPlayNext(track: Track) = playbackController.playNext(track)
    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
    fun onPlayRecentTrack(tracks: List<Track>, index: Int) {
        if (index in tracks.indices) {
            playbackController.playTracks(tracks = tracks, startIndex = index, shuffle = false)
        }
    }
    fun onSelectQueueIndex(index: Int) = playbackController.skipToQueueIndex(index)
    fun onRemoveQueueIndex(index: Int) = playbackController.removeFromQueue(index)
    fun onMoveQueueItem(fromIndex: Int, toIndex: Int) = playbackController.moveQueueItem(fromIndex, toIndex)
    fun onClearManualQueue() = playbackController.clearManualQueue()
    fun onClearUpNext() = playbackController.clearUpNext()
    fun onClearError() = playbackController.clearError()

    fun onRefreshAudioOutputs() = audioOutputMonitor.refreshDevices()

    fun onOpenSystemOutputSwitcher(context: Context) {
        audioOutputMonitor.openSystemOutputSwitcher(context)
    }

    fun onSetBluetoothDeviceHiRes(deviceKey: String, isHiRes: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setBluetoothDeviceHiRes(deviceKey, isHiRes)
        }
    }

    fun onToggleCurrentTrackFavorite() {
        val current = playbackState.value.currentTrack ?: return
        val target = !current.isFavorite
        playbackController.updateTrackFavorite(current.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(current.id, target)
        }
    }
}
