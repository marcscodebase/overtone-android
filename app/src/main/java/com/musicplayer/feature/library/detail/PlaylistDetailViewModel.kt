package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistTrackEntry
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val entries: List<PlaylistTrackEntry> = emptyList(),
    val activeTrackId: String? = null,
    val isPlaylistPinnedDownloaded: Boolean = false,
    val isDownloadingAny: Boolean = false,
    val isSyncing: Boolean = false,
    val isReordering: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
) {
    val tracks: List<Track>
        get() = entries.map { it.track }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
    private val isSyncing = MutableStateFlow(false)
    private val isReordering = MutableStateFlow(false)
    private val isDeleted = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val metaState = combine(
        isSyncing,
        isReordering,
        isDeleted,
        errorMessage,
    ) { syncing, reordering, deleted, err ->
        PlaylistMeta(syncing, reordering, deleted, err)
    }

    private val downloadMetaState = combine(
        downloadRepository.observeOwnerHasDownloadRefs(DownloadOwnerType.PLAYLIST, playlistId),
        downloadRepository.observeAllRecords(),
    ) { hasRefs, records ->
        hasRefs to records
    }

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        libraryRepository.observePlaylist(playlistId),
        playlistRepository.observePlaylistTrackEntries(playlistId),
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        downloadMetaState,
        metaState,
    ) { playlist, entries, activeId, (hasRefs, records), meta ->
        val downloadingAny = entries.any { entry ->
            val st = records[entry.track.id]?.status
            st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING || st == DownloadStatus.TRANSCODING
        }
        PlaylistDetailUiState(
            playlist = playlist,
            entries = entries,
            activeTrackId = activeId,
            isPlaylistPinnedDownloaded = hasRefs,
            isDownloadingAny = downloadingAny,
            isSyncing = meta.isSyncing,
            isReordering = meta.isReordering,
            isDeleted = meta.isDeleted,
            errorMessage = meta.errorMessage,
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

    fun onToggleReorderMode() {
        isReordering.value = !isReordering.value
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

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleTrackFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }

    fun onRenamePlaylist(newName: String) {
        viewModelScope.launch {
            when (val result = playlistRepository.renamePlaylist(playlistId, newName)) {
                is AppResult.Success -> errorMessage.value = null
                is AppResult.Error -> errorMessage.value = result.error.message
            }
        }
    }

    fun onDeletePlaylist() {
        viewModelScope.launch {
            when (val result = playlistRepository.deletePlaylist(playlistId)) {
                is AppResult.Success -> isDeleted.value = true
                is AppResult.Error -> errorMessage.value = result.error.message
            }
        }
    }

    fun onRemoveTrack(playlistItemId: String) {
        viewModelScope.launch {
            when (val result = playlistRepository.removeTrackFromPlaylist(playlistId, playlistItemId)) {
                is AppResult.Success -> errorMessage.value = null
                is AppResult.Error -> errorMessage.value = result.error.message
            }
        }
    }

    fun onMoveTrack(fromIndex: Int, toIndex: Int) {
        val entry = uiState.value.entries.getOrNull(fromIndex) ?: return
        viewModelScope.launch {
            when (
                val result = playlistRepository.moveTrackInPlaylist(
                    playlistId = playlistId,
                    playlistItemId = entry.playlistItemId,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                )
            ) {
                is AppResult.Success -> errorMessage.value = null
                is AppResult.Error -> errorMessage.value = result.error.message
            }
        }
    }

    private data class PlaylistMeta(
        val isSyncing: Boolean,
        val isReordering: Boolean,
        val isDeleted: Boolean,
        val errorMessage: String?,
    )
}
