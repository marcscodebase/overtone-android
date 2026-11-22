package com.musicplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState
    val feedbackMessages: SharedFlow<String> = playbackController.feedbackMessages

    init {
        playbackController.restorePersistedQueue()
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
