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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(LibraryFilter.ALBUMS)
    private val selectedSort = MutableStateFlow(LibrarySort.ALPHABETICAL)

    private val mediaCollections = combine(
        libraryRepository.observeDownloadedTracks(),
        libraryRepository.observeAllPlaylists(),
        libraryRepository.observeAllAlbums(),
        libraryRepository.observeAllArtists(),
        libraryRepository.observeAllTracks(),
    ) { downloaded, playlists, albums, artists, tracks ->
        MediaCollections(downloaded, playlists, albums, artists, tracks)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        selectedFilter,
        selectedSort,
        mediaCollections,
        playbackController.playbackState,
    ) { filter, sort, collections, playback ->
        val sortedAlbums = when (sort) {
            LibrarySort.ALPHABETICAL -> collections.albums.sortedBy { it.title.lowercase() }
            LibrarySort.RECENTLY_ADDED -> collections.albums.sortedByDescending { it.dateAdded }
        }
        val sortedTracks = when (sort) {
            LibrarySort.ALPHABETICAL -> collections.tracks.sortedBy { it.title.lowercase() }
            LibrarySort.RECENTLY_ADDED -> collections.tracks.sortedByDescending { it.lastPlayedAt ?: 0L }
        }
        LibraryUiState(
            selectedFilter = filter,
            selectedSort = sort,
            downloadedTracks = collections.downloaded,
            playlists = collections.playlists,
            albums = sortedAlbums,
            artists = collections.artists,
            tracks = sortedTracks,
            activeTrackId = playback.currentTrack?.id,
        )
    }.stateIn(
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

    private data class MediaCollections(
        val downloaded: List<Track>,
        val playlists: List<Playlist>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val tracks: List<Track>,
    )
}
