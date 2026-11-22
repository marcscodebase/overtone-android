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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
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

    val uiState: StateFlow<ArtistDetailUiState> = combine(
        libraryRepository.observeArtist(artistId),
        libraryRepository.observeArtistAlbums(artistId),
        libraryRepository.observeArtistTracks(artistId),
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
    ) { artist, albums, tracks, activeId, offline ->
        ArtistDetailUiState(
            artist = artist,
            albums = albums,
            tracks = tracks,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistDetailUiState(),
    )

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

    fun onTrackClick(index: Int) {
        val state = uiState.value
        val tapped = state.tracks.getOrNull(index) ?: return
        if (state.isOffline && !tapped.isDownloaded) return
        val playable = state.playableTracks
        val startIdx = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = startIdx, shuffle = false)
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
