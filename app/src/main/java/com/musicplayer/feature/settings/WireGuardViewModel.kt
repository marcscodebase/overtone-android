package com.musicplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.datastore.WireGuardConfigStore
import com.musicplayer.core.model.WireGuardConfig
import com.musicplayer.core.model.WireGuardConnectionTestResult
import com.musicplayer.core.model.WireGuardMode
import com.musicplayer.core.model.WireGuardRuntimeStatus
import com.musicplayer.core.network.wireguard.WireGuardConfigParser
import com.musicplayer.core.network.wireguard.WireGuardNativeEngine
import com.musicplayer.core.network.wireguard.WireGuardTunnelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WireGuardUiState(
    val config: WireGuardConfig? = null,
    val mode: WireGuardMode = WireGuardMode.OFF,
    val runtimeStatus: WireGuardRuntimeStatus = WireGuardRuntimeStatus(),
    val isNativeAvailable: Boolean = true,
    val serverUrl: String? = null,
    val isTesting: Boolean = false,
    val testResult: WireGuardConnectionTestResult? = null,
    val feedbackMessage: String? = null,
)

@HiltViewModel
class WireGuardViewModel @Inject constructor(
    private val configStore: WireGuardConfigStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val authSessionStore: AuthSessionStore,
    private val tunnelManager: WireGuardTunnelManager,
    private val nativeEngine: WireGuardNativeEngine,
) : ViewModel() {

    private val isTesting = MutableStateFlow(false)
    private val testResult = MutableStateFlow<WireGuardConnectionTestResult?>(null)
    private val feedbackMessage = MutableStateFlow<String?>(null)

    private val interactionState = combine(
        isTesting,
        testResult,
        feedbackMessage,
    ) { testing, result, feedback ->
        Triple(testing, result, feedback)
    }

    val uiState: StateFlow<WireGuardUiState> = combine(
        configStore.config,
        preferencesDataStore.userPreferences.map { it.wireGuardMode },
        tunnelManager.status,
        authSessionStore.session.map { it?.serverUrl },
        interactionState,
    ) { config, mode, runtime, serverUrl, (testing, result, feedback) ->
        WireGuardUiState(
            config = config,
            mode = mode,
            runtimeStatus = runtime,
            isNativeAvailable = nativeEngine.isAvailable,
            serverUrl = serverUrl,
            isTesting = testing,
            testResult = result,
            feedbackMessage = feedback,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WireGuardUiState(isNativeAvailable = nativeEngine.isAvailable),
    )

    fun onImportRawConfig(rawText: String, profileName: String = "Home Network"): Boolean {
        return when (val parsed = WireGuardConfigParser.parse(rawText, defaultProfileName = profileName)) {
            is AppResult.Error -> {
                feedbackMessage.value = parsed.error.message
                false
            }

            is AppResult.Success -> {
                viewModelScope.launch {
                    configStore.saveConfig(parsed.data)
                    val currentMode = preferencesDataStore.userPreferences.first().wireGuardMode
                    if (currentMode == WireGuardMode.OFF) {
                        preferencesDataStore.setWireGuardMode(WireGuardMode.AUTO)
                    }
                    testResult.value = null
                    feedbackMessage.value = "Imported WireGuard profile \"${parsed.data.profileName}\""
                    tunnelManager.syncRoutingNow(forceRestart = true)
                }
                true
            }
        }
    }

    fun onSelectMode(mode: WireGuardMode) {
        viewModelScope.launch {
            testResult.value = null
            feedbackMessage.value = null
            preferencesDataStore.setWireGuardMode(mode)
        }
    }

    fun onTestConnection() {
        if (isTesting.value) return
        viewModelScope.launch {
            isTesting.value = true
            testResult.value = null
            feedbackMessage.value = null
            testResult.value = tunnelManager.testConnection(uiState.value.serverUrl)
            isTesting.value = false
        }
    }

    fun onRefreshStats() {
        viewModelScope.launch {
            tunnelManager.refreshLiveStats()
        }
    }

    fun onDeleteConfig() {
        viewModelScope.launch {
            preferencesDataStore.setWireGuardMode(WireGuardMode.OFF)
            configStore.clearConfig()
            testResult.value = null
            feedbackMessage.value = "WireGuard profile removed"
            tunnelManager.syncRoutingNow(forceRestart = false)
        }
    }

    fun onSetFeedback(message: String) {
        feedbackMessage.value = message
    }
}
