package com.musicplayer.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.navigation.LibraryCollectionDestination
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

enum class CollectionTrackSort {
    ALPHABETICAL,
    RECENTLY_PLAYED,
}

data class LibraryCollectionUiState(
    val section: LibraryCollectionSection = LibraryCollectionSection.FAVORITES,
    val sort: CollectionTrackSort = CollectionTrackSort.ALPHABETICAL,
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class LibraryCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<LibraryCollectionDestination>()
    private val section = LibraryCollectionSection.fromRouteKey(destination.section)
    private val sort = MutableStateFlow(CollectionTrackSort.ALPHABETICAL)

    private val sourceTracks = when (section) {
        LibraryCollectionSection.FAVORITES -> libraryRepository.observeFavoriteTracks()
        LibraryCollectionSection.DOWNLOADED -> libraryRepository.observeDownloadedTracks()
    }

    val uiState: StateFlow<LibraryCollectionUiState> = combine(
        sourceTracks,
        sort,
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
    ) { tracks, currentSort, activeId, offline ->
        val sorted = when (currentSort) {
            CollectionTrackSort.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
            CollectionTrackSort.RECENTLY_PLAYED -> tracks.sortedByDescending { it.lastPlayedAt ?: 0L }
        }
        LibraryCollectionUiState(
            section = section,
            sort = currentSort,
            tracks = sorted,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryCollectionUiState(section = section),
        )

    fun onToggleSort() {
        sort.value = when (sort.value) {
            CollectionTrackSort.ALPHABETICAL -> CollectionTrackSort.RECENTLY_PLAYED
            CollectionTrackSort.RECENTLY_PLAYED -> CollectionTrackSort.ALPHABETICAL
        }
    }

    fun onPlayAll() {
        val playable = uiState.value.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = false,
                contextLabel = contextLabel(),
            )
        }
    }

    fun onShuffleAll() {
        val playable = uiState.value.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = true,
                contextLabel = contextLabel(),
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
                contextLabel = contextLabel(),
            )
        }
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    private fun contextLabel(): String = when (section) {
        LibraryCollectionSection.FAVORITES -> "Favorites"
        LibraryCollectionSection.DOWNLOADED -> "Downloaded"
    }
}
