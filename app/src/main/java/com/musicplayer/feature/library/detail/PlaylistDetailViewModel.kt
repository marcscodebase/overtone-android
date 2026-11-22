package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
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

data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
    private val isSyncing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        libraryRepository.observePlaylist(playlistId),
        libraryRepository.observePlaylistTracks(playlistId),
        playbackController.playbackState,
        combine(isSyncing, errorMessage) { syncing, err -> syncing to err },
    ) { playlist, tracks, playback, (syncing, err) ->
        PlaylistDetailUiState(
            playlist = playlist,
            tracks = tracks,
            activeTrackId = playback.currentTrack?.id,
            isSyncing = syncing,
            errorMessage = err,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailUiState(isSyncing = true),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isSyncing.value = true
            errorMessage.value = null
            when (val result = libraryRepository.syncPlaylistTracks(playlistId)) {
                is AppResult.Success -> errorMessage.value = null
                is AppResult.Error -> errorMessage.value = result.error.message
            }
            isSyncing.value = false
        }
    }

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
}
