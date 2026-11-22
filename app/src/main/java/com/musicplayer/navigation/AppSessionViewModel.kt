package com.musicplayer.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.worker.OfflineSyncScheduler
import com.musicplayer.data.worker.PlaylistSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppSessionState {
    data object Loading : AppSessionState
    data object Unauthenticated : AppSessionState
    data class Authenticated(val session: AuthSession) : AppSessionState
}

@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    private val playlistDao: PlaylistDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val offlineStateProvider: OfflineStateProvider,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    private val playlistSyncScheduler: PlaylistSyncScheduler,
) : ViewModel() {

    private val _sessionState = MutableStateFlow<AppSessionState>(AppSessionState.Loading)
    val sessionState: StateFlow<AppSessionState> = _sessionState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.restoreSession()
            authRepository.session.collectLatest { session ->
                if (session != null) {
                    _sessionState.value = AppSessionState.Authenticated(session)
                    launch { runCatching { downloadRepository.reconcileDownloads() } }
                    launch {
                        offlineStateProvider.offlineStatus
                            .map { it.isOffline }
                            .distinctUntilChanged()
                            .collect { isOffline ->
                                if (!isOffline) {
                                    offlineSyncScheduler.scheduleFlush()
                                    val autoSyncPlaylists = playlistDao.getAutoSyncPlaylists()
                                    if (autoSyncPlaylists.isNotEmpty()) {
                                        val wifiOnly = preferencesDataStore.userPreferences.first().wifiOnlyDownloads
                                        playlistSyncScheduler.ensurePeriodicSyncScheduled(wifiOnly)
                                        playlistSyncScheduler.syncAllAutoSyncPlaylistsNow()
                                    }
                                }
                            }
                    }
                } else {
                    _sessionState.value = AppSessionState.Unauthenticated
                }
            }
        }
    }
}
