package com.musicplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.StorageSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageManagerUiState(
    val summary: StorageSummary = StorageSummary(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class StorageManagerViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val isBusy = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<StorageManagerUiState> = combine(
        downloadRepository.observeStorageSummary(),
        isBusy,
        statusMessage,
    ) { summary, busy, status ->
        StorageManagerUiState(
            summary = summary,
            isBusy = busy,
            statusMessage = status,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StorageManagerUiState(),
    )

    fun onReconcile() {
        viewModelScope.launch {
            isBusy.value = true
            val removed = downloadRepository.reconcileDownloads()
            statusMessage.value = if (removed > 0) {
                "Cleaned up $removed externally deleted file${if (removed == 1) "" else "s"}."
            } else {
                "All downloaded files verified in Music/music-player/."
            }
            isBusy.value = false
        }
    }

    fun onDeleteTrack(trackId: String) {
        viewModelScope.launch {
            downloadRepository.forceDeleteTrackDownload(trackId)
        }
    }

    fun onRetryTrack(trackId: String) {
        viewModelScope.launch {
            downloadRepository.retryTrackDownload(trackId)
        }
    }

    fun onCancelTrack(trackId: String) {
        viewModelScope.launch {
            downloadRepository.cancelTrackDownload(trackId)
        }
    }

    fun onClearAllDownloads() {
        viewModelScope.launch {
            isBusy.value = true
            val removed = downloadRepository.clearAllDownloads()
            statusMessage.value = "Removed $removed downloaded file${if (removed == 1) "" else "s"}."
            isBusy.value = false
        }
    }
}
