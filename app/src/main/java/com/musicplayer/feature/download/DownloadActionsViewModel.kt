package com.musicplayer.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Track
import com.musicplayer.core.model.UserPreferences
import com.musicplayer.data.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PendingDownloadRequest {
    val displayTitle: String

    data class SingleTrack(val track: Track) : PendingDownloadRequest {
        override val displayTitle: String = "${track.title} — ${track.artistName}"
    }

    data class AlbumScope(
        val albumId: String,
        val albumTitle: String,
    ) : PendingDownloadRequest {
        override val displayTitle: String = albumTitle
    }

    data class PlaylistScope(
        val playlistId: String,
        val playlistName: String,
    ) : PendingDownloadRequest {
        override val displayTitle: String = playlistName
    }
}

@HiltViewModel
class DownloadActionsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    preferencesDataStore: UserPreferencesDataStore,
) : ViewModel() {

    val downloadRecords: StateFlow<Map<String, DownloadRecordEntity>> =
        downloadRepository.observeAllRecords().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    val preferences: StateFlow<UserPreferences> =
        preferencesDataStore.userPreferences.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    private val _pendingRequest = MutableStateFlow<PendingDownloadRequest?>(null)
    val pendingRequest: StateFlow<PendingDownloadRequest?> = _pendingRequest.asStateFlow()

    private val _feedbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val feedbackMessages: SharedFlow<String> = _feedbackMessages.asSharedFlow()

    fun onReconcileDownloads() {
        viewModelScope.launch {
            val result = downloadRepository.reconcileDownloads()
            val parts = buildList {
                if (result.adoptedExistingCount > 0) {
                    add(
                        "Found ${result.adoptedExistingCount} existing offline song${if (result.adoptedExistingCount == 1) "" else "s"}",
                    )
                }
                if (result.deduplicatedCount > 0) {
                    add(
                        "cleaned up ${result.deduplicatedCount} duplicate${if (result.deduplicatedCount == 1) "" else "s"}",
                    )
                }
            }
            if (parts.isNotEmpty()) {
                _feedbackMessages.tryEmit(parts.joinToString(" • "))
            }
        }
    }

    fun onToggleTrackDownload(track: Track) {
        val existing = downloadRecords.value[track.id]
        when (existing?.status) {
            DownloadStatus.COMPLETED -> {
                viewModelScope.launch {
                    downloadRepository.removeTrackDownload(track.id)
                    _feedbackMessages.tryEmit("Removed download for \"${track.title}\"")
                }
            }

            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.TRANSCODING,
            -> {
                viewModelScope.launch {
                    downloadRepository.cancelTrackDownload(track.id)
                    _feedbackMessages.tryEmit("Cancelled download for \"${track.title}\"")
                }
            }

            DownloadStatus.FAILED -> {
                viewModelScope.launch {
                    downloadRepository.retryTrackDownload(track.id)
                    _feedbackMessages.tryEmit("Retrying \"${track.title}\"")
                }
            }

            null -> {
                val request = PendingDownloadRequest.SingleTrack(track)
                if (preferences.value.askDownloadQualityEachTime) {
                    _pendingRequest.value = request
                } else {
                    executeDownload(request, qualityOverride = null)
                }
            }
        }
    }

    fun onDownloadAlbum(albumId: String, albumTitle: String) {
        val request = PendingDownloadRequest.AlbumScope(albumId, albumTitle)
        if (preferences.value.askDownloadQualityEachTime) {
            _pendingRequest.value = request
        } else {
            executeDownload(request, qualityOverride = null)
        }
    }

    fun onRemoveAlbumDownload(albumId: String, albumTitle: String) {
        viewModelScope.launch {
            val removed = downloadRepository.removeAlbumDownload(albumId)
            val msg = if (removed > 0) {
                "Removed $removed downloaded song${if (removed == 1) "" else "s"} from \"$albumTitle\""
            } else {
                "Unpinned \"$albumTitle\" (tracks kept by other downloads)"
            }
            _feedbackMessages.tryEmit(msg)
        }
    }

    fun onDownloadPlaylist(playlistId: String, playlistName: String) {
        val request = PendingDownloadRequest.PlaylistScope(playlistId, playlistName)
        if (preferences.value.askDownloadQualityEachTime) {
            _pendingRequest.value = request
        } else {
            executeDownload(request, qualityOverride = null)
        }
    }

    fun onRemovePlaylistDownload(playlistId: String, playlistName: String) {
        viewModelScope.launch {
            val removed = downloadRepository.removePlaylistDownload(playlistId)
            val msg = if (removed > 0) {
                "Removed $removed downloaded song${if (removed == 1) "" else "s"} from \"$playlistName\""
            } else {
                "Unpinned \"$playlistName\" (tracks kept by other downloads)"
            }
            _feedbackMessages.tryEmit(msg)
        }
    }

    fun onConfirmPendingDownload(quality: DownloadQuality) {
        val request = _pendingRequest.value ?: return
        _pendingRequest.value = null
        executeDownload(request, qualityOverride = quality)
    }

    fun onDismissPendingDownload() {
        _pendingRequest.value = null
    }

    private fun executeDownload(
        request: PendingDownloadRequest,
        qualityOverride: DownloadQuality?,
    ) {
        viewModelScope.launch {
            when (request) {
                is PendingDownloadRequest.SingleTrack -> {
                    downloadRepository.downloadTrack(request.track.id, qualityOverride)
                    _feedbackMessages.tryEmit("Downloading \"${request.track.title}\"")
                }

                is PendingDownloadRequest.AlbumScope -> {
                    val count = downloadRepository.downloadAlbum(request.albumId, qualityOverride)
                    val msg = if (count > 0) {
                        "Queued $count song${if (count == 1) "" else "s"} from \"${request.albumTitle}\""
                    } else {
                        "\"${request.albumTitle}\" is already downloaded"
                    }
                    _feedbackMessages.tryEmit(msg)
                }

                is PendingDownloadRequest.PlaylistScope -> {
                    val count = downloadRepository.downloadPlaylist(request.playlistId, qualityOverride)
                    val msg = if (count > 0) {
                        "Queued $count song${if (count == 1) "" else "s"} from \"${request.playlistName}\""
                    } else {
                        "\"${request.playlistName}\" is already downloaded"
                    }
                    _feedbackMessages.tryEmit(msg)
                }
            }
        }
    }
}
