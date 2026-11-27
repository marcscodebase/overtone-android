package com.musicplayer.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class CatalogTab {
    ALBUMS,
    ARTISTS,
    SONGS,
}

enum class CatalogSort {
    ALPHABETICAL,
    RECENTLY_ADDED,
    CREATOR,
}

data class CatalogUiState(
    val selectedTab: CatalogTab = CatalogTab.ALBUMS,
    val selectedSort: CatalogSort = CatalogSort.ALPHABETICAL,
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(CatalogTab.ALBUMS)
    private val selectedSort = MutableStateFlow(CatalogSort.ALPHABETICAL)

    private val activeTrackId = playbackController.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()

    private val activeCatalogFlow: Flow<CatalogUiState> =
        combine(selectedTab, selectedSort) { tab, sort -> tab to sort }
            .flatMapLatest { (tab, sort) ->
                when (tab) {
                    CatalogTab.ALBUMS -> libraryRepository.observeAllAlbums().map { albums ->
                        val sorted = when (sort) {
                            CatalogSort.ALPHABETICAL -> albums.sortedBy { it.title.lowercase() }
                            CatalogSort.RECENTLY_ADDED -> albums.sortedByDescending { it.dateAdded }
                            CatalogSort.CREATOR -> albums.sortedWith(
                                compareBy<Album> { it.artistName.lowercase() }
                                    .thenBy { it.year ?: Int.MAX_VALUE }
                                    .thenBy { it.title.lowercase() },
                            )
                        }
                        CatalogUiState(
                            selectedTab = tab,
                            selectedSort = sort,
                            albums = sorted,
                        )
                    }

                    CatalogTab.ARTISTS -> libraryRepository.observeAllArtists().map { artists ->
                        val sorted = when (sort) {
                            CatalogSort.RECENTLY_ADDED -> artists.sortedWith(
                                compareByDescending<Artist> { it.albumCount }
                                    .thenBy { it.name.lowercase() },
                            )

                            CatalogSort.ALPHABETICAL,
                            CatalogSort.CREATOR,
                            -> artists.sortedBy { it.name.lowercase() }
                        }
                        CatalogUiState(
                            selectedTab = tab,
                            selectedSort = sort,
                            artists = sorted,
                        )
                    }

                    CatalogTab.SONGS -> libraryRepository.observeAllTracks().map { tracks ->
                        val sorted = when (sort) {
                            CatalogSort.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
                            CatalogSort.RECENTLY_ADDED -> tracks.sortedByDescending { it.lastPlayedAt ?: 0L }
                            CatalogSort.CREATOR -> tracks.sortedWith(
                                compareBy<Track> { it.artistName.lowercase() }
                                    .thenBy { it.title.lowercase() },
                            )
                        }
                        CatalogUiState(
                            selectedTab = tab,
                            selectedSort = sort,
                            tracks = sorted,
                        )
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    val uiState: StateFlow<CatalogUiState> = combine(
        activeCatalogFlow,
        activeTrackId,
        offlineStateProvider.offlineStatus,
    ) { state, activeId, offline ->
        state.copy(
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CatalogUiState(),
        )

    fun onTabSelected(tab: CatalogTab) {
        selectedTab.value = tab
    }

    fun onSortSelected(sort: CatalogSort) {
        selectedSort.value = sort
    }

    fun onPlayTracks(tracks: List<Track>, startIndex: Int) {
        val tapped = tracks.getOrNull(startIndex) ?: return
        val isOffline = uiState.value.isOffline
        if (isOffline && !tapped.isDownloaded) return
        val playable = if (isOffline) tracks.filter { it.isDownloaded } else tracks
        val resolvedIndex = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = resolvedIndex)
        }
    }

    fun onShuffleCatalog() {
        playbackController.playLibraryShuffle(downloadedOnly = uiState.value.isOffline)
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
}
