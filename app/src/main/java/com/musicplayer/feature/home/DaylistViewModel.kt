package com.musicplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DaylistUiState(
    val daylist: Daylist? = null,
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val tracks: List<Track> get() = daylist?.tracks.orEmpty()
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class DaylistViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val currentSlot = MutableStateFlow(DaylistSlot.current())

    val uiState: StateFlow<DaylistUiState> = combine(
        libraryRepository.observeAllTracks(),
        currentSlot,
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
    ) { allTracks, slot, activeId, offline ->
        val daylist = if (allTracks.isNotEmpty()) {
            DaylistGenerator.generate(allTracks = allTracks, slot = slot)
        } else {
            null
        }
        DaylistUiState(
            daylist = daylist,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DaylistUiState(),
        )

    fun checkSlotRefresh() {
        val nowSlot = DaylistSlot.current()
        if (currentSlot.value != nowSlot) {
            currentSlot.value = nowSlot
        }
    }

    fun onPlayAll() {
        val tracks = uiState.value.playableTracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(
                tracks = tracks,
                startIndex = 0,
                shuffle = false,
                contextLabel = DAYLIST_CONTEXT_LABEL,
            )
        }
    }

    fun onShuffleAll() {
        val tracks = uiState.value.playableTracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(
                tracks = tracks,
                startIndex = 0,
                shuffle = true,
                contextLabel = DAYLIST_CONTEXT_LABEL,
            )
        }
    }

    fun onTrackClick(index: Int) {
        val state = uiState.value
        val tapped = state.tracks.getOrNull(index) ?: return
        if (state.isOffline && !tapped.isDownloaded) return
        val playable = state.playableTracks
        val startIdx = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = startIdx,
                shuffle = false,
                contextLabel = DAYLIST_CONTEXT_LABEL,
            )
        }
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    companion object {
        const val DAYLIST_CONTEXT_LABEL = "Daylist"
    }
}
