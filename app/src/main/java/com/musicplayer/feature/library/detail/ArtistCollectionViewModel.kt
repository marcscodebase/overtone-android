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
import javax.inject.Inject

data class ArtistCollectionUiState(
    val artist: Artist? = null,
    val section: ArtistCollectionSection = ArtistCollectionSection.ALL_SONGS,
    val chronologicalOrder: ChronologicalOrder = ChronologicalOrder.NEWEST_FIRST,
    val tracks: List<Track> = emptyList(),
    val trackYearsById: Map<String, Int?> = emptyMap(),
    val albums: List<Album> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class ArtistCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val artistId: String = checkNotNull(savedStateHandle["artistId"])
    private val section: ArtistCollectionSection =
        ArtistCollectionSection.fromRouteKey(savedStateHandle["section"])
    private val chronologicalOrder = MutableStateFlow(ChronologicalOrder.NEWEST_FIRST)

    private val coreDataFlow = combine(
        libraryRepository.observeArtist(artistId),
        libraryRepository.observeArtistAlbums(artistId),
        libraryRepository.observeArtistTracks(artistId),
    ) { artist, albums, tracks ->
        Triple(artist, albums, tracks)
    }

    val uiState: StateFlow<ArtistCollectionUiState> = combine(
        coreDataFlow,
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
        chronologicalOrder,
    ) { (artist, albums, tracks), activeId, offline, order ->
        val albumsById = albums.associateBy { it.id }
        val sectionTracks = when (section) {
            ArtistCollectionSection.FAVORITES -> ArtistCatalogHelper.favoriteTracks(tracks, albumsById)
            ArtistCollectionSection.ALL_SONGS -> ArtistCatalogHelper.chronologicalTracks(
                tracks = tracks,
                albumsById = albumsById,
                order = order,
            )
            ArtistCollectionSection.ALL_ALBUMS -> emptyList()
        }
        val trackYearsById = sectionTracks.associate { track ->
            track.id to ArtistCatalogHelper.effectiveReleaseYear(track, albumsById)
        }
        val sectionAlbums = if (section == ArtistCollectionSection.ALL_ALBUMS) {
            ArtistCatalogHelper.chronologicalAlbums(albums, order)
        } else {
            emptyList()
        }

        ArtistCollectionUiState(
            artist = artist,
            section = section,
            chronologicalOrder = order,
            tracks = sectionTracks,
            trackYearsById = trackYearsById,
            albums = sectionAlbums,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistCollectionUiState(section = section),
    )

    fun onToggleChronologicalOrder() {
        chronologicalOrder.update { it.toggle() }
    }

    fun onPlayAll() {
        val tracks = uiState.value.playableTracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(tracks = tracks, startIndex = 0, shuffle = false)
        }
    }

    fun onShuffleAll() {
        val tracks = uiState.value.playableTracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(tracks = tracks, startIndex = 0, shuffle = true)
        }
    }

    fun onTrackClick(track: Track) {
        val state = uiState.value
        if (state.isOffline && !track.isDownloaded) return
        val playable = state.playableTracks
        val startIdx = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = startIdx, shuffle = false)
        }
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
}
