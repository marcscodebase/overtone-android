package com.musicplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.UserPreferences
import com.musicplayer.data.repository.AuthRepository
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

data class SettingsUiState(
    val session: AuthSession? = null,
    val preferences: UserPreferences = UserPreferences(),
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.session,
        preferencesDataStore.userPreferences,
        isSyncing,
        statusMessage,
    ) { session, prefs, syncing, status ->
        SettingsUiState(
            session = session,
            preferences = prefs,
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
        }
    }

    fun onSelectCellularBitrateCap(cap: CellularBitrateCap) {
        viewModelScope.launch {
            preferencesDataStore.setCellularBitrateCap(cap)
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
