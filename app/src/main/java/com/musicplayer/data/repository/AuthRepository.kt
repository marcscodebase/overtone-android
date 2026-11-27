package com.musicplayer.data.repository

import android.content.Context
import androidx.work.WorkManager
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.MusicPlayerDatabase
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.model.MusicLibrary
import com.musicplayer.core.model.QuickConnectSession
import com.musicplayer.core.model.ServerPublicInfo
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.ServerUrlNormalizer
import com.musicplayer.core.network.wireguard.WireGuardTunnelManager
import com.musicplayer.data.worker.DownloadWorker
import com.musicplayer.data.worker.OfflineSyncWorker
import com.musicplayer.data.worker.PlaylistSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.QuickConnectDto
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    val session: Flow<AuthSession?>
    suspend fun restoreSession(): AuthSession?
    suspend fun connectToServer(rawUrl: String): AppResult<ServerPublicInfo>
    suspend fun loginWithPassword(
        serverInfo: ServerPublicInfo,
        username: String,
        password: String,
    ): AppResult<AuthSession>

    suspend fun initiateQuickConnect(serverUrl: String): AppResult<QuickConnectSession>
    suspend fun pollQuickConnect(
        serverInfo: ServerPublicInfo,
        secret: String,
    ): AppResult<AuthSession?>

    suspend fun fetchMusicLibraries(userId: String): AppResult<List<MusicLibrary>>
    suspend fun completeSignIn(session: AuthSession, libraryIds: Set<String>)
    suspend fun logout()
}

@Singleton
class JellyfinAuthRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clientProvider: JellyfinClientProvider,
    private val authSessionStore: AuthSessionStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val wireGuardTunnelManager: WireGuardTunnelManager,
    private val lyricsRepository: LyricsRepository,
    private val database: MusicPlayerDatabase,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    override val session: Flow<AuthSession?> = authSessionStore.session

    override suspend fun restoreSession(): AuthSession? = withContext(ioDispatcher) {
        val saved = authSessionStore.getSession() ?: return@withContext null
        runCatching {
            clientProvider.configure(baseUrl = saved.serverUrl, accessToken = saved.accessToken)
        }.onFailure {
            authSessionStore.clearSession()
            clientProvider.clear()
            return@withContext null
        }
        saved
    }

    override suspend fun connectToServer(rawUrl: String): AppResult<ServerPublicInfo> =
        withContext(ioDispatcher) {
            val normalizedUrl = ServerUrlNormalizer.normalize(rawUrl)
                ?: return@withContext AppResult.Error(
                    AppError.Validation("Enter a valid HTTP or HTTPS Jellyfin server URL"),
                )

            wireGuardTunnelManager.prepareForServerUrl(normalizedUrl)

            runJellyfinCall {
                val api = clientProvider.configure(baseUrl = normalizedUrl, accessToken = null)
                val infoResponse = api.systemApi.getPublicSystemInfo()
                val info = infoResponse.content
                val quickConnectEnabled = runCatching {
                    api.quickConnectApi.getQuickConnectEnabled().content
                }.getOrDefault(false)

                ServerPublicInfo(
                    serverUrl = normalizedUrl,
                    serverId = info.id ?: normalizedUrl,
                    serverName = info.serverName?.takeIf { it.isNotBlank() } ?: normalizedUrl,
                    version = info.version,
                    quickConnectEnabled = quickConnectEnabled,
                )
            }
        }

    override suspend fun loginWithPassword(
        serverInfo: ServerPublicInfo,
        username: String,
        password: String,
    ): AppResult<AuthSession> = withContext(ioDispatcher) {
        val trimmedUser = username.trim()
        if (trimmedUser.isEmpty()) {
            return@withContext AppResult.Error(AppError.Validation("Username cannot be empty"))
        }

        runJellyfinCall {
            val api = clientProvider.configure(baseUrl = serverInfo.serverUrl, accessToken = null)
            val response = api.userApi.authenticateUserByName(
                data = AuthenticateUserByName(
                    username = trimmedUser,
                    pw = password,
                ),
            ).content

            val accessToken = response.accessToken
                ?: throw IllegalStateException("Server returned an empty access token")
            val userId = response.user?.id?.toString()
                ?: throw IllegalStateException("Server returned an empty user ID")
            val displayName = response.user?.name ?: trimmedUser

            val authSession = AuthSession(
                serverUrl = serverInfo.serverUrl,
                serverId = response.serverId ?: serverInfo.serverId,
                serverName = serverInfo.serverName,
                userId = userId,
                username = displayName,
                accessToken = accessToken,
            )
            clientProvider.configure(baseUrl = authSession.serverUrl, accessToken = authSession.accessToken)
            authSession
        }
    }

    override suspend fun initiateQuickConnect(serverUrl: String): AppResult<QuickConnectSession> =
        withContext(ioDispatcher) {
            runJellyfinCall {
                val api = clientProvider.configure(baseUrl = serverUrl, accessToken = null)
                val result = api.quickConnectApi.initiateQuickConnect().content
                QuickConnectSession(
                    secret = result.secret,
                    code = result.code,
                )
            }
        }

    override suspend fun pollQuickConnect(
        serverInfo: ServerPublicInfo,
        secret: String,
    ): AppResult<AuthSession?> = withContext(ioDispatcher) {
        runJellyfinCall {
            val api = clientProvider.configure(baseUrl = serverInfo.serverUrl, accessToken = null)
            val state = api.quickConnectApi.getQuickConnectState(secret = secret).content
            if (!state.authenticated) {
                return@runJellyfinCall null
            }

            val authResponse = api.userApi.authenticateWithQuickConnect(
                data = QuickConnectDto(secret = secret),
            ).content

            val accessToken = authResponse.accessToken
                ?: throw IllegalStateException("Server returned an empty access token")
            val userId = authResponse.user?.id?.toString()
                ?: throw IllegalStateException("Server returned an empty user ID")
            val displayName = authResponse.user?.name ?: "Jellyfin User"

            val authSession = AuthSession(
                serverUrl = serverInfo.serverUrl,
                serverId = authResponse.serverId ?: serverInfo.serverId,
                serverName = serverInfo.serverName,
                userId = userId,
                username = displayName,
                accessToken = accessToken,
            )
            clientProvider.configure(baseUrl = authSession.serverUrl, accessToken = authSession.accessToken)
            authSession
        }
    }

    override suspend fun fetchMusicLibraries(userId: String): AppResult<List<MusicLibrary>> =
        withContext(ioDispatcher) {
            runJellyfinCall {
                val api = clientProvider.getClient()
                val userUuid = runCatching { UUID.fromString(userId) }.getOrNull()
                val views = api.userViewsApi.getUserViews(userId = userUuid).content.items.orEmpty()
                val musicViews = views.filter { it.collectionType == CollectionType.MUSIC }
                val effectiveViews = musicViews.ifEmpty { views }
                effectiveViews.map { item ->
                    MusicLibrary(
                        id = item.id.toString(),
                        name = item.name ?: "Music",
                        imageTag = item.imageTags?.values?.firstOrNull(),
                    )
                }
            }
        }

    override suspend fun completeSignIn(session: AuthSession, libraryIds: Set<String>) =
        withContext(ioDispatcher) {
            clientProvider.configure(baseUrl = session.serverUrl, accessToken = session.accessToken)
            preferencesDataStore.setSelectedMusicLibraryIds(libraryIds)
            authSessionStore.saveSession(session)
        }

    override suspend fun logout() = withContext(ioDispatcher) {
        runCatching {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelAllWorkByTag(DownloadWorker.TAG_DOWNLOAD)
            workManager.cancelUniqueWork(OfflineSyncWorker.UNIQUE_WORK_NAME)
            workManager.cancelAllWorkByTag(PlaylistSyncWorker.TAG_PLAYLIST_SYNC)
        }
        lyricsRepository.clearCache()
        wireGuardTunnelManager.clearPendingServerUrl()
        authSessionStore.clearSession()
        clientProvider.clear()
        preferencesDataStore.setSelectedMusicLibraryIds(emptySet())
        database.clearAllTables()
    }

    private suspend inline fun <T> runJellyfinCall(block: () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (status: InvalidStatusException) {
            when (status.status) {
                401, 403 -> AppResult.Error(AppError.Unauthorized("Invalid username or password"))
                404 -> AppResult.Error(AppError.NotFound("Jellyfin endpoint not found at this URL"))
                else -> AppResult.Error(
                    AppError.Server(
                        statusCode = status.status,
                        message = "Jellyfin server returned HTTP ${status.status}",
                    ),
                )
            }
        } catch (io: IOException) {
            AppResult.Error(
                AppError.Network(
                    message = "Unable to reach Jellyfin server. Check the URL and network connection.",
                    cause = io,
                ),
            )
        } catch (t: Throwable) {
            AppResult.Error(
                AppError.Unknown(
                    message = t.message ?: "Unexpected Jellyfin error",
                    cause = t,
                ),
            )
        }
    }
}
