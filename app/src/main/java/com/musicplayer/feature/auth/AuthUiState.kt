package com.musicplayer.feature.auth

import com.musicplayer.core.model.MusicLibrary
import com.musicplayer.core.model.QuickConnectSession
import com.musicplayer.core.model.ServerPublicInfo

enum class AuthStep {
    SERVER_URL,
    CREDENTIALS,
    LIBRARY_PICKER,
}

enum class LoginTab {
    PASSWORD,
    QUICK_CONNECT,
}

data class AuthUiState(
    val step: AuthStep = AuthStep.SERVER_URL,
    val serverUrlInput: String = "",
    val serverInfo: ServerPublicInfo? = null,
    val loginTab: LoginTab = LoginTab.PASSWORD,
    val username: String = "",
    val password: String = "",
    val quickConnectSession: QuickConnectSession? = null,
    val isPollingQuickConnect: Boolean = false,
    val availableLibraries: List<MusicLibrary> = emptyList(),
    val selectedLibraryIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isCompleted: Boolean = false,
)
