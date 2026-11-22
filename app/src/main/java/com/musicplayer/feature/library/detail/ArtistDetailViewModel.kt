package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val artistId: String = checkNotNull(savedStateHandle["artistId"])

    val uiState: StateFlow<ArtistDetailUiState> = combine(
        libraryRepository.observeArtist(artistId),
        libraryRepository.observeArtistAlbums(artistId),
        libraryRepository.observeArtistTracks(artistId),
        playbackController.playbackState,
    ) { artist, albums, tracks, playback ->
        ArtistDetailUiState(
            artist = artist,
            albums = albums,
            tracks = tracks,
            activeTrackId = playback.currentTrack?.id,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistDetailUiState(),
    )

    fun onPlayAll() {
        val tracks = uiState.value.tracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(tracks = tracks, startIndex = 0, shuffle = false)
        }
    }

    fun onShuffleAll() {
        val tracks = uiState.value.tracks
        if (tracks.isNotEmpty()) {
            playbackController.playTracks(tracks = tracks, startIndex = 0, shuffle = true)
        }
    }

    fun onTrackClick(index: Int) {
        val tracks = uiState.value.tracks
        if (index in tracks.indices) {
            playbackController.playTracks(tracks = tracks, startIndex = index, shuffle = false)
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
