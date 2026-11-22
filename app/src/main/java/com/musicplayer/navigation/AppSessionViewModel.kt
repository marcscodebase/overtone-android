package com.musicplayer.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.AuthSession
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.data.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
                } else {
                    _sessionState.value = AppSessionState.Unauthenticated
                }
            }
        }
    }
}
