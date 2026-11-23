package com.musicplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LyricsRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val syncRepository: SyncRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState
    val feedbackMessages: SharedFlow<String> = playbackController.feedbackMessages

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
    fun onPlayNext(track: Track) = playbackController.playNext(track)
    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
    fun onSelectQueueIndex(index: Int) = playbackController.skipToQueueIndex(index)
    fun onRemoveQueueIndex(index: Int) = playbackController.removeFromQueue(index)
    fun onMoveQueueItem(fromIndex: Int, toIndex: Int) = playbackController.moveQueueItem(fromIndex, toIndex)
    fun onClearManualQueue() = playbackController.clearManualQueue()
    fun onClearUpNext() = playbackController.clearUpNext()
    fun onClearError() = playbackController.clearError()

    fun onToggleCurrentTrackFavorite() {
        val current = playbackState.value.currentTrack ?: return
        val target = !current.isFavorite
        playbackController.updateTrackFavorite(current.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(current.id, target)
        }
    }
}
