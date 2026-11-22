package com.musicplayer.feature.player

import androidx.lifecycle.ViewModel
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

    fun onTogglePlayPause() = playbackController.togglePlayPause()
    fun onSkipNext() = playbackController.skipToNext()
    fun onSkipPrevious() = playbackController.skipToPrevious()
    fun onSeekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun onToggleShuffle() = playbackController.toggleShuffle()
    fun onCycleRepeatMode() = playbackController.cycleRepeatMode()
    fun onClearError() = playbackController.clearError()
}
