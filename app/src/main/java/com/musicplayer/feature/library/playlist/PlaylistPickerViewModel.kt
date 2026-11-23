package com.musicplayer.feature.library.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistMembershipDiff(
    val playlistIdsToAdd: Set<String>,
    val playlistIdsToRemove: Set<String>,
) {
    companion object {
        fun calculate(initial: Set<String>, target: Set<String>): PlaylistMembershipDiff =
            PlaylistMembershipDiff(
                playlistIdsToAdd = target - initial,
                playlistIdsToRemove = initial - target,
            )
    }
}

@HiltViewModel
class PlaylistPickerViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = libraryRepository.observeAllPlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val trackIdsInAnyPlaylist: StateFlow<Set<String>> = playlistDao.observeAllTrackIdsInPlaylists()
        .map { it.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    private val _feedbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val feedbackMessages: SharedFlow<String> = _feedbackMessages.asSharedFlow()

    fun observeTrackPlaylistMembership(trackId: String): Flow<Map<String, String>> =
        playlistDao.observeRefsForTrack(trackId).map { refs ->
            refs.associate { it.playlistId to it.playlistItemId }
        }

    fun autoFavoriteTrack(track: Track) {
        if (track.isFavorite) return
        playbackController.updateTrackFavorite(track.id, true)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, true)
            _feedbackMessages.tryEmit("Added \"${track.title}\" to Favorites")
        }
    }

    fun applySaveSelection(
        track: Track,
        initialFavorite: Boolean,
        targetFavorite: Boolean,
        initialMembership: Map<String, String>,
        targetPlaylistIds: Set<String>,
    ) {
        viewModelScope.launch {
            if (targetFavorite != initialFavorite) {
                playbackController.updateTrackFavorite(track.id, targetFavorite)
                syncRepository.setTrackFavorite(track.id, targetFavorite)
            }

            val diff = PlaylistMembershipDiff.calculate(
                initial = initialMembership.keys,
                target = targetPlaylistIds,
            )

            diff.playlistIdsToAdd.forEach { playlistId ->
                playlistRepository.addTracksToPlaylist(playlistId, listOf(track.id))
            }
            diff.playlistIdsToRemove.forEach { playlistId ->
                val itemId = initialMembership[playlistId] ?: return@forEach
                playlistRepository.removeTrackFromPlaylist(playlistId, itemId)
            }

            val addedCount = diff.playlistIdsToAdd.size
            val removedCount = diff.playlistIdsToRemove.size
            when {
                addedCount > 0 && removedCount == 0 -> {
                    _feedbackMessages.tryEmit(
                        "Saved to $addedCount playlist${if (addedCount == 1) "" else "s"}",
                    )
                }

                removedCount > 0 && addedCount == 0 -> {
                    _feedbackMessages.tryEmit(
                        "Removed from $removedCount playlist${if (removedCount == 1) "" else "s"}",
                    )
                }

                addedCount > 0 || removedCount > 0 || targetFavorite != initialFavorite -> {
                    _feedbackMessages.tryEmit("Updated saved playlists")
                }
            }
        }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name = name, initialTrackIds = listOf(track.id))
            _feedbackMessages.tryEmit("Added \"${track.title}\" to \"$name\"")
        }
    }
}
