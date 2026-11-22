package com.musicplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val recentlyPlayedTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val activeTrackId: String? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = recentlyAddedAlbums.isEmpty() && recentlyPlayedTracks.isEmpty() && playlists.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        libraryRepository.observeRecentlyAddedAlbums(limit = 16),
        libraryRepository.observeRecentlyPlayedTracks(limit = 10),
        libraryRepository.observeAllPlaylists(),
        playbackController.playbackState,
        combine(isRefreshing, errorMessage) { refreshing, error -> refreshing to error },
    ) { albums, recentTracks, playlists, playback, (refreshing, error) ->
        HomeUiState(
            recentlyAddedAlbums = albums,
            recentlyPlayedTracks = recentTracks,
            playlists = playlists,
            activeTrackId = playback.currentTrack?.id,
            isRefreshing = refreshing,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isRefreshing = true),
    )

    init {
        refreshLibrary()
    }

    fun refreshLibrary() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            when (val result = libraryRepository.syncLibrary()) {
                is AppResult.Success -> errorMessage.value = null
                is AppResult.Error -> errorMessage.value = result.error.message
            }
            isRefreshing.value = false
        }
    }

    fun onTrackClick(tracks: List<Track>, index: Int) {
        playbackController.playTracks(tracks = tracks, startIndex = index)
    }

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
}
