package com.musicplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.core.model.UserPreferences
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.data.worker.OfflineSyncScheduler
import com.musicplayer.data.worker.PlaylistSyncScheduler
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val session: AuthSession? = null,
    val preferences: UserPreferences = UserPreferences(),
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    private val playlistDao: PlaylistDao,
    private val playlistSyncScheduler: PlaylistSyncScheduler,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)

    private val syncMeta = combine(
        syncRepository.observePendingSyncCount(),
        isSyncing,
        statusMessage,
    ) { pending, syncing, status ->
        Triple(pending, syncing, status)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.session,
        preferencesDataStore.userPreferences,
        downloadRepository.observeStorageSummary(),
        syncMeta,
    ) { session, prefs, storage, (pendingCount, syncing, status) ->
        SettingsUiState(
            session = session,
            preferences = prefs,
            downloadedCount = storage.completedCount,
            downloadedBytes = storage.totalBytes,
            pendingSyncCount = pendingCount,
            isSyncing = syncing,
            statusMessage = status,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun onToggleOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setOfflineModeEnabled(enabled)
            if (!enabled) {
                offlineSyncScheduler.scheduleFlush()
            }
        }
    }

    fun onFlushPendingSync() {
        viewModelScope.launch {
            val flushed = syncRepository.flushPendingSyncActions()
            statusMessage.value = if (flushed > 0) {
                "Synced $flushed offline change${if (flushed == 1) "" else "s"} to Jellyfin"
            } else {
                "Scheduled offline sync when connected"
            }
            if (flushed == 0) {
                offlineSyncScheduler.scheduleFlush()
            }
        }
    }

    fun onSelectCellularBitrateCap(cap: CellularBitrateCap) {
        viewModelScope.launch {
            preferencesDataStore.setCellularBitrateCap(cap)
        }
    }

    fun onSelectLowQualityBitrate(bitrate: Mp3Bitrate) {
        viewModelScope.launch {
            preferencesDataStore.setLowQualityBitrate(bitrate)
        }
    }

    fun onToggleAutoMatchOutputQuality(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setAutoMatchOutputQuality(enabled)
        }
    }

    fun onSelectDefaultDownloadQuality(quality: DownloadQuality) {
        viewModelScope.launch {
            preferencesDataStore.setDefaultDownloadQuality(quality)
        }
    }

    fun onToggleWifiOnlyDownloads(wifiOnly: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setWifiOnlyDownloads(wifiOnly)
            if (playlistDao.getAutoSyncPlaylists().isNotEmpty()) {
                playlistSyncScheduler.ensurePeriodicSyncScheduled(wifiOnly)
            }
        }
    }

    fun onToggleAskDownloadQuality(askEachTime: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setAskDownloadQualityEachTime(askEachTime)
        }
    }

    fun onSelectReplayGainMode(mode: ReplayGainMode) {
        viewModelScope.launch {
            preferencesDataStore.setReplayGainMode(mode)
        }
    }

    fun onSetReplayGainPreampDb(preampDb: Float) {
        viewModelScope.launch {
            preferencesDataStore.setReplayGainPreampDb(preampDb)
        }
    }

    fun onSyncLibrary() {
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            statusMessage.value = null
            statusMessage.value = when (val result = libraryRepository.syncLibrary()) {
                is AppResult.Success -> "Library synced successfully"
                is AppResult.Error -> result.error.message
            }
            isSyncing.value = false
        }
    }

    fun onSignOut() {
        viewModelScope.launch {
            playbackController.stopAndClear()
            authRepository.logout()
        }
    }
}
