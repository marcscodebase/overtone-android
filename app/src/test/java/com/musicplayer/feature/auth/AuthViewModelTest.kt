package com.musicplayer.feature.auth

import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.model.MusicLibrary
import com.musicplayer.core.model.QuickConnectSession
import com.musicplayer.core.model.ServerPublicInfo
import com.musicplayer.data.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun connectAndPasswordLoginWithSingleLibraryCompletesAuth() = runTest {
        val fakeRepo = FakeAuthRepository(
            libraries = listOf(MusicLibrary("lib-1", "Lossless Music", null)),
        )
        val viewModel = AuthViewModel(fakeRepo)

        viewModel.onServerUrlChanged("http://192.168.1.50:8096")
        viewModel.onConnectToServer()
        advanceUntilIdle()

        assertEquals(AuthStep.CREDENTIALS, viewModel.uiState.value.step)
        assertEquals("Home Jellyfin", viewModel.uiState.value.serverInfo?.serverName)

        viewModel.onUsernameChanged("marc")
        viewModel.onPasswordChanged("secret")
        viewModel.onLoginWithPassword()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCompleted)
        assertEquals(setOf("lib-1"), fakeRepo.savedLibraryIds)
    }

    @Test
    fun multipleLibrariesOpensLibraryPickerBeforeCompletion() = runTest {
        val fakeRepo = FakeAuthRepository(
            libraries = listOf(
                MusicLibrary("lib-1", "FLAC", null),
                MusicLibrary("lib-2", "Podcasts", null),
            ),
        )
        val viewModel = AuthViewModel(fakeRepo)

        viewModel.onServerUrlChanged("https://jf.example.com")
        viewModel.onConnectToServer()
        advanceUntilIdle()
        viewModel.onUsernameChanged("marc")
        viewModel.onLoginWithPassword()
        advanceUntilIdle()

        assertEquals(AuthStep.LIBRARY_PICKER, viewModel.uiState.value.step)
        assertFalse(viewModel.uiState.value.isCompleted)
        assertEquals(setOf("lib-1", "lib-2"), viewModel.uiState.value.selectedLibraryIds)

        viewModel.onToggleLibrary("lib-2")
        viewModel.onConfirmLibraries()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCompleted)
        assertEquals(setOf("lib-1"), fakeRepo.savedLibraryIds)
    }

    @Test
    fun serverErrorSurfacesActionableMessage() = runTest {
        val fakeRepo = FakeAuthRepository(
            connectError = AppError.Network("Unable to reach server"),
        )
        val viewModel = AuthViewModel(fakeRepo)

        viewModel.onServerUrlChanged("http://10.0.0.1:8096")
        viewModel.onConnectToServer()
        advanceUntilIdle()

        assertEquals(AuthStep.SERVER_URL, viewModel.uiState.value.step)
        assertEquals("Unable to reach server", viewModel.uiState.value.errorMessage)
    }

    private class FakeAuthRepository(
        private val libraries: List<MusicLibrary> = emptyList(),
        private val connectError: AppError? = null,
    ) : AuthRepository {
        private val _session = MutableStateFlow<AuthSession?>(null)
        override val session: Flow<AuthSession?> = _session
        var savedLibraryIds: Set<String> = emptySet()

        override suspend fun restoreSession(): AuthSession? = _session.value

        override suspend fun connectToServer(rawUrl: String): AppResult<ServerPublicInfo> {
            connectError?.let { return AppResult.Error(it) }
            return AppResult.Success(
                ServerPublicInfo(
                    serverUrl = rawUrl,
                    serverId = "srv-1",
                    serverName = "Home Jellyfin",
                    version = "10.10.0",
                    quickConnectEnabled = true,
                ),
            )
        }

        override suspend fun loginWithPassword(
            serverInfo: ServerPublicInfo,
            username: String,
            password: String,
        ): AppResult<AuthSession> {
            val created = AuthSession(
                serverUrl = serverInfo.serverUrl,
                serverId = serverInfo.serverId,
                serverName = serverInfo.serverName,
                userId = "user-1",
                username = username,
                accessToken = "token-123",
            )
            _session.value = created
            return AppResult.Success(created)
        }

        override suspend fun initiateQuickConnect(serverUrl: String): AppResult<QuickConnectSession> =
            AppResult.Success(QuickConnectSession(secret = "sec", code = "123456"))

        override suspend fun pollQuickConnect(
            serverInfo: ServerPublicInfo,
            secret: String,
        ): AppResult<AuthSession?> = AppResult.Success(null)

        override suspend fun fetchMusicLibraries(): AppResult<List<MusicLibrary>> =
            AppResult.Success(libraries)

        override suspend fun saveSelectedMusicLibraries(libraryIds: Set<String>) {
            savedLibraryIds = libraryIds
        }

        override suspend fun logout() {
            _session.value = null
        }
    }
}
