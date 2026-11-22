package com.musicplayer.feature.player

import androidx.lifecycle.ViewModel
import com.musicplayer.core.model.Track
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState

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
}
