package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Track
import com.musicplayer.data.repository.DownloadRepository
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

data class AlbumDetailUiState(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val isAlbumPinnedDownloaded: Boolean = false,
    val isDownloadingAny: Boolean = false,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val albumId: String = checkNotNull(savedStateHandle["albumId"])

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        libraryRepository.observeAlbum(albumId),
        libraryRepository.observeAlbumTracks(albumId),
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        downloadRepository.observeOwnerHasDownloadRefs(DownloadOwnerType.ALBUM, albumId),
        downloadRepository.observeAllRecords(),
    ) { album, tracks, activeId, hasAlbumRefs, records ->
        val downloadingAny = tracks.any { track ->
            val st = records[track.id]?.status
            st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING || st == DownloadStatus.TRANSCODING
        }
        AlbumDetailUiState(
            album = album,
            tracks = tracks,
            activeTrackId = activeId,
            isAlbumPinnedDownloaded = hasAlbumRefs,
            isDownloadingAny = downloadingAny,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailUiState(),
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

    fun onToggleAlbumFavorite() {
        val album = uiState.value.album ?: return
        viewModelScope.launch {
            syncRepository.setAlbumFavorite(album.id, !album.isFavorite)
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
