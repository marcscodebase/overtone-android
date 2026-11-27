package com.musicplayer.data.repository

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.AlbumDao
import com.musicplayer.core.database.dao.ArtistDao
import com.musicplayer.core.database.dao.PendingSyncActionDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.PendingSyncActionEntity
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.PlaylistFolderCodec
import com.musicplayer.core.model.SyncActionType
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.NetworkMonitor
import com.musicplayer.data.worker.OfflineSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UpdatePlaylistDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.jellyfin.sdk.model.api.RepeatMode as JellyfinRepeatMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PlaybackStopPayload(
    val trackId: String,
    val positionMs: Long,
)

@Serializable
data class FavoriteMutationPayload(
    val itemId: String,
)

@Serializable
data class PlaylistMutationPayload(
    val operation: String,
    val playlistId: String,
    val newName: String? = null,
    val folderName: String? = null,
    val trackIds: List<String> = emptyList(),
    val playlistItemId: String? = null,
    val toIndex: Int? = null,
) {
    companion object {
        const val OP_RENAME = "RENAME"
        const val OP_SET_FOLDER = "SET_FOLDER"
        const val OP_ADD_TRACKS = "ADD_TRACKS"
        const val OP_REMOVE_TRACK = "REMOVE_TRACK"
        const val OP_MOVE_TRACK = "MOVE_TRACK"
    }
}

enum class OutboxActionDisposition {
    DELETE_SUCCEEDED,
    INCREMENT_RETRY,
    DROP_MAX_RETRIES,
}

object OutboxRetryPolicy {
    fun evaluate(succeeded: Boolean, currentRetryCount: Int, maxRetries: Int): OutboxActionDisposition = when {
        succeeded -> OutboxActionDisposition.DELETE_SUCCEEDED
        currentRetryCount + 1 >= maxRetries -> OutboxActionDisposition.DROP_MAX_RETRIES
        else -> OutboxActionDisposition.INCREMENT_RETRY
    }
}

interface SyncRepository {
    fun observePendingSyncCount(): Flow<Int>

    suspend fun reportPlaybackStart(
        trackId: String,
        positionMs: Long,
        isTranscoded: Boolean,
    )

    suspend fun reportPlaybackProgress(
        trackId: String,
        positionMs: Long,
        isPaused: Boolean,
        isTranscoded: Boolean,
    )

    suspend fun reportPlaybackStopped(
        trackId: String,
        positionMs: Long,
    )

    suspend fun setTrackFavorite(trackId: String, isFavorite: Boolean): AppResult<Unit>
    suspend fun setAlbumFavorite(albumId: String, isFavorite: Boolean): AppResult<Unit>
    suspend fun setArtistFavorite(artistId: String, isFavorite: Boolean): AppResult<Unit>
    suspend fun enqueuePlaylistMutation(payload: PlaylistMutationPayload)

    suspend fun flushPendingSyncActions(): Int
}

@Singleton
class JellyfinSyncRepository @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val networkMonitor: NetworkMonitor,
    private val authSessionStore: AuthSessionStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    private val json: Json,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : SyncRepository {

    override fun observePendingSyncCount(): Flow<Int> = pendingSyncActionDao.observePendingCount()

    override suspend fun reportPlaybackStart(
        trackId: String,
        positionMs: Long,
        isTranscoded: Boolean,
    ) = withContext(ioDispatcher) {
        if (!canSyncRemotely()) return@withContext
        val itemUuid = parseUuid(trackId) ?: return@withContext
        val playMethod = if (isTranscoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

        runCatching {
            clientProvider.getClient().playStateApi.reportPlaybackStart(
                data = PlaybackStartInfo(
                    itemId = itemUuid,
                    positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = playMethod,
                    repeatMode = JellyfinRepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
        Unit
    }

    override suspend fun reportPlaybackProgress(
        trackId: String,
        positionMs: Long,
        isPaused: Boolean,
        isTranscoded: Boolean,
    ) = withContext(ioDispatcher) {
        if (!canSyncRemotely()) return@withContext
        val itemUuid = parseUuid(trackId) ?: return@withContext
        val playMethod = if (isTranscoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY

        runCatching {
            clientProvider.getClient().playStateApi.reportPlaybackProgress(
                data = PlaybackProgressInfo(
                    itemId = itemUuid,
                    positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
                    canSeek = true,
                    isPaused = isPaused,
                    isMuted = false,
                    playMethod = playMethod,
                    repeatMode = JellyfinRepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
        Unit
    }

    override suspend fun reportPlaybackStopped(
        trackId: String,
        positionMs: Long,
    ) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        if (positionMs >= MIN_SCROBBLE_POSITION_MS) {
            trackDao.recordPlay(trackId = trackId, playedAt = now)
        }

        val itemUuid = parseUuid(trackId) ?: return@withContext
        if (!canSyncRemotely()) {
            enqueuePlaybackStop(trackId, positionMs)
            return@withContext
        }

        val sent = runCatching {
            sendPlaybackStop(itemUuid, positionMs)
        }.isSuccess

        if (!sent) {
            enqueuePlaybackStop(trackId, positionMs)
        }
    }

    override suspend fun setTrackFavorite(trackId: String, isFavorite: Boolean): AppResult<Unit> =
        withContext(ioDispatcher) {
            trackDao.setFavorite(trackId, isFavorite)
            syncFavoriteRemotely(itemId = trackId, isFavorite = isFavorite)
        }

    override suspend fun setAlbumFavorite(albumId: String, isFavorite: Boolean): AppResult<Unit> =
        withContext(ioDispatcher) {
            albumDao.setFavorite(albumId, isFavorite)
            syncFavoriteRemotely(itemId = albumId, isFavorite = isFavorite)
        }

    override suspend fun setArtistFavorite(artistId: String, isFavorite: Boolean): AppResult<Unit> =
        withContext(ioDispatcher) {
            artistDao.setFavorite(artistId, isFavorite)
            syncFavoriteRemotely(itemId = artistId, isFavorite = isFavorite)
        }

    override suspend fun enqueuePlaylistMutation(payload: PlaylistMutationPayload) {
        withContext(ioDispatcher) {
            pendingSyncActionDao.enqueue(
                PendingSyncActionEntity(
                    actionType = SyncActionType.PLAYLIST_MUTATION,
                    payloadJson = json.encodeToString(payload),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            offlineSyncScheduler.scheduleFlush()
        }
    }

    private val flushMutex = Mutex()

    override suspend fun flushPendingSyncActions(): Int = withContext(ioDispatcher) {
        flushMutex.withLock {
            if (!canSyncRemotely()) return@withLock 0
            val pending = pendingSyncActionDao.getAllPending()
            var flushedCount = 0

            for (action in pending) {
                val succeeded = try {
                    executePendingAction(action)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    false
                }

                when (OutboxRetryPolicy.evaluate(succeeded, action.retryCount, MAX_RETRY_COUNT)) {
                    OutboxActionDisposition.DELETE_SUCCEEDED -> {
                        pendingSyncActionDao.deleteById(action.id)
                        flushedCount++
                    }

                    OutboxActionDisposition.DROP_MAX_RETRIES -> {
                        pendingSyncActionDao.deleteById(action.id)
                    }

                    OutboxActionDisposition.INCREMENT_RETRY -> {
                        pendingSyncActionDao.incrementRetry(action.id)
                    }
                }
            }
            flushedCount
        }
    }

    private suspend fun syncFavoriteRemotely(itemId: String, isFavorite: Boolean): AppResult<Unit> {
        val itemUuid = parseUuid(itemId)
            ?: return AppResult.Error(AppError.Validation("Invalid item ID"))

        if (!canSyncRemotely()) {
            enqueueFavoriteMutation(itemId, isFavorite)
            return AppResult.Success(Unit)
        }

        return try {
            sendFavoriteMutation(itemUuid, isFavorite)
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            enqueueFavoriteMutation(itemId, isFavorite)
            AppResult.Success(Unit)
        }
    }

    private suspend fun executePendingAction(action: PendingSyncActionEntity): Boolean {
        return when (action.actionType) {
            SyncActionType.PLAYBACK_STOP -> {
                val payload = runCatching {
                    json.decodeFromString<PlaybackStopPayload>(action.payloadJson)
                }.getOrNull() ?: return true
                val uuid = parseUuid(payload.trackId) ?: return true
                sendPlaybackStop(uuid, payload.positionMs)
                true
            }

            SyncActionType.FAVORITE_ADD -> {
                val payload = runCatching {
                    json.decodeFromString<FavoriteMutationPayload>(action.payloadJson)
                }.getOrNull() ?: return true
                val uuid = parseUuid(payload.itemId) ?: return true
                sendFavoriteMutation(uuid, isFavorite = true)
                true
            }

            SyncActionType.FAVORITE_REMOVE -> {
                val payload = runCatching {
                    json.decodeFromString<FavoriteMutationPayload>(action.payloadJson)
                }.getOrNull() ?: return true
                val uuid = parseUuid(payload.itemId) ?: return true
                sendFavoriteMutation(uuid, isFavorite = false)
                true
            }

            SyncActionType.PLAYLIST_MUTATION -> {
                val payload = runCatching {
                    json.decodeFromString<PlaylistMutationPayload>(action.payloadJson)
                }.getOrNull() ?: return true
                executePlaylistMutation(payload)
            }
        }
    }

    private suspend fun executePlaylistMutation(payload: PlaylistMutationPayload): Boolean {
        val api = clientProvider.getClient()
        val session = authSessionStore.getSession()
        val userUuid = session?.userId?.let(::parseUuid)

        return when (payload.operation) {
            PlaylistMutationPayload.OP_RENAME -> {
                val playlistUuid = parseUuid(payload.playlistId) ?: return true
                val newName = payload.newName?.takeIf { it.isNotBlank() } ?: return true
                api.playlistsApi.updatePlaylist(
                    playlistId = playlistUuid,
                    data = UpdatePlaylistDto(name = newName),
                )
                true
            }

            PlaylistMutationPayload.OP_SET_FOLDER -> {
                val playlistUuid = parseUuid(payload.playlistId) ?: return true
                val newName = payload.newName?.takeIf { it.isNotBlank() } ?: return true
                api.playlistsApi.updatePlaylist(
                    playlistId = playlistUuid,
                    data = UpdatePlaylistDto(name = newName),
                )
                if (userUuid != null) {
                    try {
                        val currentDto = api.userLibraryApi.getItem(
                            itemId = playlistUuid,
                            userId = userUuid,
                        ).content
                        val updatedTags = PlaylistFolderCodec.buildUpdatedTags(
                            currentDto.tags,
                            payload.folderName,
                        )
                        if (updatedTags != currentDto.tags.orEmpty()) {
                            api.itemUpdateApi.updateItem(
                                itemId = playlistUuid,
                                data = currentDto.copy(tags = updatedTags),
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // Best-effort tag sync; name already carries the folder convention.
                    }
                }
                true
            }

            PlaylistMutationPayload.OP_ADD_TRACKS -> {
                val playlistUuid = parseUuid(payload.playlistId) ?: return true
                val trackUuids = payload.trackIds.mapNotNull(::parseUuid)
                if (trackUuids.isEmpty()) return true
                api.playlistsApi.addItemToPlaylist(
                    playlistId = playlistUuid,
                    ids = trackUuids,
                    userId = userUuid,
                )
                true
            }

            PlaylistMutationPayload.OP_REMOVE_TRACK -> {
                val itemId = payload.playlistItemId?.takeIf { it.isNotBlank() } ?: return true
                api.playlistsApi.removeItemFromPlaylist(
                    playlistId = payload.playlistId,
                    entryIds = listOf(itemId),
                )
                true
            }

            PlaylistMutationPayload.OP_MOVE_TRACK -> {
                val itemId = payload.playlistItemId?.takeIf { it.isNotBlank() } ?: return true
                val targetIndex = payload.toIndex ?: return true
                api.playlistsApi.moveItem(
                    playlistId = payload.playlistId,
                    itemId = itemId,
                    newIndex = targetIndex,
                )
                true
            }

            else -> true
        }
    }

    private suspend fun sendPlaybackStop(itemId: UUID, positionMs: Long) {
        clientProvider.getClient().playStateApi.reportPlaybackStopped(
            data = PlaybackStopInfo(
                itemId = itemId,
                positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
                failed = false,
            ),
        )
    }

    private suspend fun sendFavoriteMutation(itemId: UUID, isFavorite: Boolean) {
        val session = authSessionStore.getSession()
            ?: throw IllegalStateException("Not signed in")
        val userUuid = parseUuid(session.userId)
        val userLibrary = clientProvider.getClient().userLibraryApi
        if (isFavorite) {
            userLibrary.markFavoriteItem(itemId = itemId, userId = userUuid)
        } else {
            userLibrary.unmarkFavoriteItem(itemId = itemId, userId = userUuid)
        }
    }

    private suspend fun enqueuePlaybackStop(trackId: String, positionMs: Long) {
        pendingSyncActionDao.enqueue(
            PendingSyncActionEntity(
                actionType = SyncActionType.PLAYBACK_STOP,
                payloadJson = json.encodeToString(PlaybackStopPayload(trackId, positionMs)),
                createdAt = System.currentTimeMillis(),
            ),
        )
        offlineSyncScheduler.scheduleFlush()
    }

    private suspend fun enqueueFavoriteMutation(itemId: String, isFavorite: Boolean) {
        pendingSyncActionDao.enqueue(
            PendingSyncActionEntity(
                actionType = if (isFavorite) SyncActionType.FAVORITE_ADD else SyncActionType.FAVORITE_REMOVE,
                payloadJson = json.encodeToString(FavoriteMutationPayload(itemId)),
                createdAt = System.currentTimeMillis(),
            ),
        )
        offlineSyncScheduler.scheduleFlush()
    }

    private suspend fun canSyncRemotely(): Boolean {
        val offline = preferencesDataStore.userPreferences.first().offlineModeEnabled
        if (offline || !networkMonitor.networkState.value.isConnected) return false
        val session = authSessionStore.getSession() ?: return false
        return session.accessToken.isNotBlank() && !clientProvider.getClient().baseUrl.isNullOrBlank()
    }

    private fun parseUuid(raw: String): UUID? = raw.toUUIDOrNull()

    companion object {
        private const val TICKS_PER_MS = 10_000L
        private const val MIN_SCROBBLE_POSITION_MS = 5_000L
        internal const val MAX_RETRY_COUNT = 5
    }
}
