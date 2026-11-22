package com.musicplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
import com.musicplayer.data.repository.SyncRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilter {
    DOWNLOADED,
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    SONGS,
}

enum class LibrarySort {
    ALPHABETICAL,
    RECENTLY_ADDED,
}

data class LibraryUiState(
    val selectedFilter: LibraryFilter = LibraryFilter.ALBUMS,
    val selectedSort: LibrarySort = LibrarySort.ALPHABETICAL,
    val downloadedTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(LibraryFilter.ALBUMS)
    private val selectedSort = MutableStateFlow(LibrarySort.ALPHABETICAL)

    private val activeTrackId = playbackController.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()

    private val activeCollectionState: Flow<LibraryUiState> =
        combine(selectedFilter, selectedSort) { filter, sort -> filter to sort }
            .flatMapLatest { (filter, sort) ->
                when (filter) {
                    LibraryFilter.DOWNLOADED -> libraryRepository.observeDownloadedTracks().map { tracks ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            downloadedTracks = sortTracks(tracks, sort),
                        )
                    }

                    LibraryFilter.PLAYLISTS -> libraryRepository.observeAllPlaylists().map { playlists ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            playlists = playlists,
                        )
                    }

                    LibraryFilter.ALBUMS -> libraryRepository.observeAllAlbums().map { albums ->
                        val sorted = when (sort) {
                            LibrarySort.ALPHABETICAL -> albums.sortedBy { it.title.lowercase() }
                            LibrarySort.RECENTLY_ADDED -> albums.sortedByDescending { it.dateAdded }
                        }
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            albums = sorted,
                        )
                    }

                    LibraryFilter.ARTISTS -> libraryRepository.observeAllArtists().map { artists ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            artists = artists,
                        )
                    }

                    LibraryFilter.SONGS -> libraryRepository.observeAllTracks().map { tracks ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            tracks = sortTracks(tracks, sort),
                        )
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    val uiState: StateFlow<LibraryUiState> = combine(
        activeCollectionState,
        activeTrackId,
    ) { state, activeId ->
        state.copy(activeTrackId = activeId)
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState(),
        )

    fun onFilterSelected(filter: LibraryFilter) {
        selectedFilter.value = filter
    }

    fun onToggleSort() {
        selectedSort.value = when (selectedSort.value) {
            LibrarySort.ALPHABETICAL -> LibrarySort.RECENTLY_ADDED
            LibrarySort.RECENTLY_ADDED -> LibrarySort.ALPHABETICAL
        }
    }

    fun onPlayTracks(tracks: List<Track>, startIndex: Int) {
        playbackController.playTracks(tracks = tracks, startIndex = startIndex)
    }

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }

    fun onCreatePlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name = name)
        }
    }

    private fun sortTracks(tracks: List<Track>, sort: LibrarySort): List<Track> = when (sort) {
        LibrarySort.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.RECENTLY_ADDED -> tracks.sortedByDescending { it.lastPlayedAt ?: 0L }
    }
}
