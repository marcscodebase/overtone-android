package com.musicplayer.data.repository

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.database.entity.PlaylistEntity
import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistTrackEntry
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.NetworkMonitor
import com.musicplayer.data.remote.JellyfinArtworkUrlBuilder
import com.musicplayer.data.remote.JellyfinMappers.toDomain
import com.musicplayer.data.worker.PlaylistSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UpdatePlaylistDto
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

object PlaylistOrderHelper {
    fun reorder(
        refs: List<PlaylistTrackCrossRef>,
        fromIndex: Int,
        toIndex: Int,
    ): List<PlaylistTrackCrossRef> {
        if (fromIndex !in refs.indices || toIndex !in refs.indices || fromIndex == toIndex) {
            return refs
        }
        val mutable = refs.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable.mapIndexed { index, ref -> ref.copy(position = index) }
    }

    fun removeByItemId(
        refs: List<PlaylistTrackCrossRef>,
        playlistItemId: String,
    ): List<PlaylistTrackCrossRef> = refs
        .filterNot { it.playlistItemId == playlistItemId }
        .mapIndexed { index, ref -> ref.copy(position = index) }
}

interface PlaylistRepository {
    fun observePlaylistTrackEntries(playlistId: String): Flow<List<PlaylistTrackEntry>>
    suspend fun createPlaylist(name: String, initialTrackIds: List<String> = emptyList()): AppResult<Playlist>
    suspend fun renamePlaylist(playlistId: String, newName: String): AppResult<Unit>
    suspend fun deletePlaylist(playlistId: String): AppResult<Unit>
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): AppResult<Unit>
    suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String): AppResult<Unit>
    suspend fun moveTrackInPlaylist(
        playlistId: String,
        playlistItemId: String,
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit>
    suspend fun setAutoSyncDownloaded(playlistId: String, enabled: Boolean)
    fun triggerPlaylistDownloadSync(playlistId: String)
}

@Singleton
class JellyfinPlaylistRepository @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val networkMonitor: NetworkMonitor,
    private val authSessionStore: AuthSessionStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val playlistSyncScheduler: PlaylistSyncScheduler,
    private val playlistDao: PlaylistDao,
    private val downloadDao: DownloadDao,
    private val artworkUrlBuilder: JellyfinArtworkUrlBuilder,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : PlaylistRepository {

    private val downloadsByTrackId = downloadDao.observeAllRecords()
        .map { list -> list.associateBy { it.trackId } }

    override fun observePlaylistTrackEntries(playlistId: String): Flow<List<PlaylistTrackEntry>> =
        combine(
            playlistDao.observeTrackEntriesForPlaylist(playlistId),
            downloadsByTrackId,
        ) { rows, downloads ->
            rows.map { row ->
                PlaylistTrackEntry(
                    track = row.track.toDomain(
                        artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                            trackId = row.track.id,
                            albumId = row.track.albumId,
                            imageTag = row.track.primaryImageTag,
                        ),
                        downloadRecord = downloads[row.track.id],
                    ),
                    playlistItemId = row.playlistItemId,
                    position = row.position,
                )
            }
        }

    override suspend fun createPlaylist(
        name: String,
        initialTrackIds: List<String>,
    ): AppResult<Playlist> = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return@withContext AppResult.Error(AppError.Validation("Playlist name cannot be empty"))
        }
        val session = authSessionStore.getSession()
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Not signed in"))
        val userUuid = parseUuid(session.userId)
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Invalid user session"))

        runPlaylistCall {
            val api = clientProvider.getClient()
            val trackUuids = initialTrackIds.mapNotNull(::parseUuid)
            val result = api.playlistsApi.createPlaylist(
                data = CreatePlaylistDto(
                    name = trimmed,
                    ids = trackUuids,
                    userId = userUuid,
                    mediaType = MediaType.AUDIO,
                    users = emptyList(),
                    isPublic = false,
                ),
            ).content

            val entity = PlaylistEntity(
                id = result.id,
                name = trimmed,
                trackCount = trackUuids.size,
                isAutoSyncDownloaded = false,
                primaryImageTag = null,
            )
            playlistDao.upsert(entity)
            if (trackUuids.isNotEmpty()) {
                libraryRepository.syncPlaylistTracks(result.id)
            }
            entity.toDomain(artworkUrlBuilder.buildItemImageUrl(entity.id, entity.primaryImageTag))
        }
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): AppResult<Unit> =
        withContext(ioDispatcher) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Error(AppError.Validation("Playlist name cannot be empty"))
            }
            val playlistUuid = parseUuid(playlistId)
                ?: return@withContext AppResult.Error(AppError.Validation("Invalid playlist ID"))

            playlistDao.updateName(playlistId, trimmed)
            val payload = PlaylistMutationPayload(
                operation = PlaylistMutationPayload.OP_RENAME,
                playlistId = playlistId,
                newName = trimmed,
            )
            if (isOffline()) {
                syncRepository.enqueuePlaylistMutation(payload)
                return@withContext AppResult.Success(Unit)
            }

            try {
                clientProvider.getClient().playlistsApi.updatePlaylist(
                    playlistId = playlistUuid,
                    data = UpdatePlaylistDto(name = trimmed),
                )
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                syncRepository.enqueuePlaylistMutation(payload)
                AppResult.Success(Unit)
            }
        }

    override suspend fun deletePlaylist(playlistId: String): AppResult<Unit> =
        withContext(ioDispatcher) {
            val playlistUuid = parseUuid(playlistId)
                ?: return@withContext AppResult.Error(AppError.Validation("Invalid playlist ID"))

            runPlaylistCall {
                clientProvider.getClient().libraryApi.deleteItem(itemId = playlistUuid)
                playlistDao.deletePlaylistAndRefs(playlistId)
            }
        }

    override suspend fun addTracksToPlaylist(
        playlistId: String,
        trackIds: List<String>,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val playlistUuid = parseUuid(playlistId)
            ?: return@withContext AppResult.Error(AppError.Validation("Invalid playlist ID"))
        val trackUuids = trackIds.mapNotNull(::parseUuid)
        if (trackUuids.isEmpty()) return@withContext AppResult.Success(Unit)

        val currentRefs = playlistDao.getTrackRefsSnapshot(playlistId)
        val basePos = currentRefs.size
        val localAppended = trackIds.mapIndexed { idx, trackId ->
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                playlistItemId = "local_${System.currentTimeMillis()}_$idx",
                position = basePos + idx,
            )
        }
        playlistDao.replacePlaylistTracks(playlistId, currentRefs + localAppended)

        val payload = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_ADD_TRACKS,
            playlistId = playlistId,
            trackIds = trackIds,
        )
        if (isOffline()) {
            syncRepository.enqueuePlaylistMutation(payload)
            return@withContext AppResult.Success(Unit)
        }

        val session = authSessionStore.getSession()
        val userUuid = session?.userId?.let(::parseUuid)

        try {
            clientProvider.getClient().playlistsApi.addItemToPlaylist(
                playlistId = playlistUuid,
                ids = trackUuids,
                userId = userUuid,
            )
            libraryRepository.syncPlaylistTracks(playlistId)
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            syncRepository.enqueuePlaylistMutation(payload)
            AppResult.Success(Unit)
        }
    }

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        playlistItemId: String,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val currentRefs = playlistDao.getTrackRefsSnapshot(playlistId)
        val updated = PlaylistOrderHelper.removeByItemId(currentRefs, playlistItemId)
        playlistDao.replacePlaylistTracks(playlistId, updated)

        val payload = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_REMOVE_TRACK,
            playlistId = playlistId,
            playlistItemId = playlistItemId,
        )
        if (isOffline()) {
            syncRepository.enqueuePlaylistMutation(payload)
            return@withContext AppResult.Success(Unit)
        }

        try {
            clientProvider.getClient().playlistsApi.removeItemFromPlaylist(
                playlistId = playlistId,
                entryIds = listOf(playlistItemId),
            )
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            syncRepository.enqueuePlaylistMutation(payload)
            AppResult.Success(Unit)
        }
    }

    override suspend fun moveTrackInPlaylist(
        playlistId: String,
        playlistItemId: String,
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        if (fromIndex == toIndex) return@withContext AppResult.Success(Unit)

        val currentRefs = playlistDao.getTrackRefsSnapshot(playlistId)
        val reordered = PlaylistOrderHelper.reorder(currentRefs, fromIndex, toIndex)
        playlistDao.replacePlaylistTracks(playlistId, reordered)

        val payload = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_MOVE_TRACK,
            playlistId = playlistId,
            playlistItemId = playlistItemId,
            toIndex = toIndex,
        )
        if (isOffline()) {
            syncRepository.enqueuePlaylistMutation(payload)
            return@withContext AppResult.Success(Unit)
        }

        try {
            clientProvider.getClient().playlistsApi.moveItem(
                playlistId = playlistId,
                itemId = playlistItemId,
                newIndex = toIndex,
            )
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            syncRepository.enqueuePlaylistMutation(payload)
            AppResult.Success(Unit)
        }
    }

    override suspend fun setAutoSyncDownloaded(playlistId: String, enabled: Boolean) {
        withContext(ioDispatcher) {
            playlistDao.setAutoSyncDownloaded(playlistId, enabled)
            if (enabled) {
                val wifiOnly = preferencesDataStore.userPreferences.first().wifiOnlyDownloads
                playlistSyncScheduler.ensurePeriodicSyncScheduled(wifiOnly)
                playlistSyncScheduler.syncPlaylistNow(playlistId)
            } else if (playlistDao.getAutoSyncPlaylists().isEmpty()) {
                playlistSyncScheduler.cancelPeriodicSync()
            }
        }
    }

    override fun triggerPlaylistDownloadSync(playlistId: String) {
        playlistSyncScheduler.syncPlaylistNow(playlistId)
    }

    private suspend fun isOffline(): Boolean {
        val manualOffline = preferencesDataStore.userPreferences.first().offlineModeEnabled
        return manualOffline || !networkMonitor.networkState.value.isConnected
    }

    private suspend inline fun <T> runPlaylistCall(block: () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            AppResult.Error(AppError.Network("Playlist update failed — check server connection", io))
        } catch (t: Throwable) {
            AppResult.Error(AppError.Unknown(t.message ?: "Playlist operation failed", t))
        }
    }

    private fun parseUuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()
}
