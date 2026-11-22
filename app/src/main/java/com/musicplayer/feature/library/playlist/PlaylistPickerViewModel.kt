package com.musicplayer.feature.library.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistPickerViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = libraryRepository.observeAllPlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            playlistRepository.addTracksToPlaylist(playlistId, listOf(track.id))
        }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name = name, initialTrackIds = listOf(track.id))
        }
    }
}
