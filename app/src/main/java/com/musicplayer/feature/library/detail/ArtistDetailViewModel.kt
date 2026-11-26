package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val artistId: String = "",
    val artist: Artist? = null,
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val favoriteTracks: List<Track> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val isTopTracksExpanded: Boolean = false,
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val displayedTopTracks: List<Track>
        get() = topTracks.take(if (isTopTracksExpanded) TOP_TRACKS_EXPANDED_LIMIT else TOP_TRACKS_COLLAPSED_LIMIT)
    val canExpandTopTracks: Boolean
        get() = topTracks.size > TOP_TRACKS_COLLAPSED_LIMIT
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
    val playableTopTracks: List<Track>
        get() = if (isOffline) topTracks.filter { it.isDownloaded } else topTracks

    companion object {
        const val TOP_TRACKS_COLLAPSED_LIMIT = 5
        const val TOP_TRACKS_EXPANDED_LIMIT = 10
    }
}

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val artistId: String = checkNotNull(savedStateHandle["artistId"])
    private val topTracksExpanded = MutableStateFlow(false)

    private val coreDataFlow = combine(
        libraryRepository.observeArtist(artistId),
        libraryRepository.observeArtistAlbums(artistId),
        libraryRepository.observeArtistTracks(artistId),
    ) { artist, albums, tracks ->
        Triple(artist, albums, tracks)
    }

    val uiState: StateFlow<ArtistDetailUiState> = combine(
        coreDataFlow,
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
        topTracksExpanded,
    ) { (artist, albums, tracks), activeId, offline, expanded ->
        val albumsById = albums.associateBy { it.id }
        val sortedAlbums = ArtistCatalogHelper.chronologicalAlbums(albums, ChronologicalOrder.NEWEST_FIRST)
        val sortedTracks = ArtistCatalogHelper.chronologicalTracks(
            tracks = tracks,
            albumsById = albumsById,
            order = ChronologicalOrder.NEWEST_FIRST,
        )
        val favoriteTracks = ArtistCatalogHelper.favoriteTracks(tracks, albumsById)
        val topTracks = ArtistCatalogHelper.topTracks(
            tracks = tracks,
            albumsById = albumsById,
            limit = ArtistDetailUiState.TOP_TRACKS_EXPANDED_LIMIT,
        )

        ArtistDetailUiState(
            artistId = artistId,
            artist = artist,
            albums = sortedAlbums,
            tracks = sortedTracks,
            favoriteTracks = favoriteTracks,
            topTracks = topTracks,
            isTopTracksExpanded = expanded,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistDetailUiState(artistId = artistId),
    )

    fun onToggleTopTracksExpanded() {
        topTracksExpanded.update { !it }
    }

    fun onPlayAll() {
        val state = uiState.value
        val ordered = (state.playableTopTracks + state.playableTracks).distinctBy { it.id }
        if (ordered.isNotEmpty()) {
            playbackController.playTracks(tracks = ordered, startIndex = 0, shuffle = false)
        }
    }

    fun onShuffleAll() {
        val tracks = uiState.value.playableTracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(tracks = tracks, startIndex = 0, shuffle = true)
        }
    }

    fun onTopTrackClick(track: Track) {
        val state = uiState.value
        if (state.isOffline && !track.isDownloaded) return
        val ordered = (state.playableTopTracks + state.playableTracks).distinctBy { it.id }
        val startIdx = ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (ordered.isNotEmpty()) {
            playbackController.playTracks(tracks = ordered, startIndex = startIdx, shuffle = false)
        }
    }

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleArtistFavorite() {
        val artist = uiState.value.artist ?: return
        viewModelScope.launch {
            syncRepository.setArtistFavorite(artist.id, !artist.isFavorite)
        }
    }

    fun onToggleTrackFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }
}
