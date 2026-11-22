package com.musicplayer.feature.library.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
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
    val offlineStatus: OfflineStatus = OfflineStatus(),
    val isAlbumPinnedDownloaded: Boolean = false,
    val isDownloadingAny: Boolean = false,
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val albumId: String = checkNotNull(savedStateHandle["albumId"])

    private val downloadStatusBundle = combine(
        downloadRepository.observeOwnerHasDownloadRefs(DownloadOwnerType.ALBUM, albumId),
        downloadRepository.observeAllRecords(),
        offlineStateProvider.offlineStatus,
    ) { hasAlbumRefs, records, offline ->
        Triple(hasAlbumRefs, records, offline)
    }

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        libraryRepository.observeAlbum(albumId),
        libraryRepository.observeAlbumTracks(albumId),
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        downloadStatusBundle,
    ) { album, tracks, activeId, (hasAlbumRefs, records, offline) ->
        val downloadingAny = tracks.any { track ->
            val st = records[track.id]?.status
            st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING || st == DownloadStatus.TRANSCODING
        }
        AlbumDetailUiState(
            album = album,
            tracks = tracks,
            activeTrackId = activeId,
            offlineStatus = offline,
            isAlbumPinnedDownloaded = hasAlbumRefs,
            isDownloadingAny = downloadingAny,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailUiState(),
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
