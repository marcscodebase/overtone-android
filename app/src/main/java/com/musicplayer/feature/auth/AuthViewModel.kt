package com.musicplayer.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var quickConnectPollJob: Job? = null
    private var pendingAuthSession: com.musicplayer.core.model.AuthSession? = null

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrlInput = value, errorMessage = null) }
    }

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onBackToServerStep() {
        stopQuickConnectPolling()
        _uiState.update {
            it.copy(
                step = AuthStep.SERVER_URL,
                quickConnectSession = null,
                isPollingQuickConnect = false,
                errorMessage = null,
            )
        }
    }

    fun onLoginTabSelected(tab: LoginTab) {
        _uiState.update { it.copy(loginTab = tab, errorMessage = null) }
        if (tab == LoginTab.QUICK_CONNECT) {
            startQuickConnect()
        } else {
            stopQuickConnectPolling()
        }
    }

    fun onConnectToServer() {
        val rawUrl = _uiState.value.serverUrlInput
        if (rawUrl.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your Jellyfin server address") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authRepository.connectToServer(rawUrl)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            serverInfo = result.data,
                            step = AuthStep.CREDENTIALS,
                            loginTab = LoginTab.PASSWORD,
                            errorMessage = null,
                        )
                    }
                }

                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message,
                        )
                    }
                }
            }
        }
    }

    fun onLoginWithPassword() {
        val current = _uiState.value
        val serverInfo = current.serverInfo ?: return
        if (current.username.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your username") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (
                val result = authRepository.loginWithPassword(
                    serverInfo = serverInfo,
                    username = current.username,
                    password = current.password,
                )
            ) {
                is AppResult.Success -> onAuthenticated(result.data)
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    fun startQuickConnect() {
        val serverInfo = _uiState.value.serverInfo ?: return
        if (!serverInfo.quickConnectEnabled) {
            _uiState.update {
                it.copy(errorMessage = "Quick Connect is disabled on this Jellyfin server")
            }
            return
        }

        stopQuickConnectPolling()
        quickConnectPollJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, quickConnectSession = null, errorMessage = null)
            }
            when (val initResult = authRepository.initiateQuickConnect(serverInfo.serverUrl)) {
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isPollingQuickConnect = false,
                            errorMessage = initResult.error.message,
                        )
                    }
                }

                is AppResult.Success -> {
                    val qcSession = initResult.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            quickConnectSession = qcSession,
                            isPollingQuickConnect = true,
                        )
                    }
                    while (isActive) {
                        delay(QUICK_CONNECT_POLL_INTERVAL_MS)
                        when (val pollResult = authRepository.pollQuickConnect(serverInfo, qcSession.secret)) {
                            is AppResult.Success -> {
                                if (pollResult.data != null) {
                                    _uiState.update { it.copy(isPollingQuickConnect = false) }
                                    onAuthenticated(pollResult.data)
                                    break
                                }
                            }

                            is AppResult.Error -> {
                                _uiState.update {
                                    it.copy(
                                        isPollingQuickConnect = false,
                                        errorMessage = pollResult.error.message,
                                    )
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    fun onToggleLibrary(libraryId: String) {
        _uiState.update { state ->
            val updated = if (libraryId in state.selectedLibraryIds) {
                state.selectedLibraryIds - libraryId
            } else {
                state.selectedLibraryIds + libraryId
            }
            state.copy(selectedLibraryIds = updated, errorMessage = null)
        }
    }

    fun onConfirmLibraries() {
        val selected = _uiState.value.selectedLibraryIds
        val session = pendingAuthSession ?: return
        if (selected.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one music library") }
            return
        }
        viewModelScope.launch {
            authRepository.completeSignIn(session, selected)
            _uiState.update { it.copy(isCompleted = true) }
        }
    }

    private suspend fun onAuthenticated(session: com.musicplayer.core.model.AuthSession) {
        pendingAuthSession = session
        when (val librariesResult = authRepository.fetchMusicLibraries(session.userId)) {
            is AppResult.Success -> {
                val libraries = librariesResult.data
                if (libraries.size <= 1) {
                    authRepository.completeSignIn(session, libraries.map { it.id }.toSet())
                    _uiState.update { it.copy(isLoading = false, isCompleted = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            step = AuthStep.LIBRARY_PICKER,
                            availableLibraries = libraries,
                            selectedLibraryIds = libraries.map { lib -> lib.id }.toSet(),
                        )
                    }
                }
            }

            is AppResult.Error -> {
                // Proceed even if library discovery fails; default to all music libraries
                authRepository.completeSignIn(session, emptySet())
                _uiState.update { it.copy(isLoading = false, isCompleted = true) }
            }
        }
    }

    private fun stopQuickConnectPolling() {
        quickConnectPollJob?.cancel()
        quickConnectPollJob = null
    }

    override fun onCleared() {
        stopQuickConnectPolling()
        super.onCleared()
    }

    companion object {
        private const val QUICK_CONNECT_POLL_INTERVAL_MS = 3_000L
    }
}
