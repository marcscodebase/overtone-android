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
import com.musicplayer.core.model.PlaylistFolderCodec
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
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UpdatePlaylistDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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
    suspend fun createPlaylist(
        name: String,
        folderName: String? = null,
        initialTrackIds: List<String> = emptyList(),
    ): AppResult<Playlist>
    suspend fun renamePlaylist(playlistId: String, newName: String): AppResult<Unit>
    suspend fun movePlaylistToFolder(playlistId: String, folderName: String?): AppResult<Unit>
    suspend fun renameFolder(oldFolderName: String, newFolderName: String?): AppResult<Unit>
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
        folderName: String?,
        initialTrackIds: List<String>,
    ): AppResult<Playlist> = withContext(ioDispatcher) {
        val parsedInput = PlaylistFolderCodec.parse(rawName = name)
        val cleanName = parsedInput.displayName.takeIf { name.trim().isNotEmpty() }.orEmpty()
        if (cleanName.isEmpty()) {
            return@withContext AppResult.Error(AppError.Validation("Playlist name cannot be empty"))
        }
        val cleanFolder = PlaylistFolderCodec.normalizeFolderName(folderName) ?: parsedInput.folderName
        val remoteName = PlaylistFolderCodec.formatRemoteName(cleanName, cleanFolder)

        val session = authSessionStore.getSession()
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Not signed in"))
        val userUuid = parseUuid(session.userId)
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Invalid user session"))

        runPlaylistCall {
            val api = clientProvider.getClient()
            val trackUuids = initialTrackIds.mapNotNull(::parseUuid)
            val result = api.playlistsApi.createPlaylist(
                data = CreatePlaylistDto(
                    name = remoteName,
                    ids = trackUuids,
                    userId = userUuid,
                    mediaType = MediaType.AUDIO,
                    users = emptyList(),
                    isPublic = false,
                ),
            ).content

            val normalizedId = PlaylistFolderCodec.normalizeJellyfinId(result.id)
            val playlistUuid = parseUuid(normalizedId)
            if (cleanFolder != null && playlistUuid != null) {
                syncPlaylistFolderTagBestEffort(api, userUuid, playlistUuid, cleanFolder)
            }

            val entity = PlaylistEntity(
                id = normalizedId,
                name = cleanName,
                trackCount = trackUuids.size,
                isAutoSyncDownloaded = false,
                primaryImageTag = null,
                folderName = cleanFolder,
            )
            playlistDao.upsert(entity)
            if (trackUuids.isNotEmpty()) {
                libraryRepository.syncPlaylistTracks(normalizedId)
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

            val existing = playlistDao.getByIdSnapshot(playlistId)
            val currentFolder = PlaylistFolderCodec.normalizeFolderName(existing?.folderName)
            val parsedInput = PlaylistFolderCodec.parse(rawName = trimmed)
            val targetName = parsedInput.displayName
            val targetFolder = parsedInput.folderName ?: currentFolder
            val remoteName = PlaylistFolderCodec.formatRemoteName(targetName, targetFolder)

            playlistDao.updateNameAndFolder(playlistId, targetName, targetFolder)
            val payload = PlaylistMutationPayload(
                operation = if (targetFolder != currentFolder) {
                    PlaylistMutationPayload.OP_SET_FOLDER
                } else {
                    PlaylistMutationPayload.OP_RENAME
                },
                playlistId = playlistId,
                newName = remoteName,
                folderName = targetFolder,
            )
            if (isOffline()) {
                syncRepository.enqueuePlaylistMutation(payload)
                return@withContext AppResult.Success(Unit)
            }

            try {
                val api = clientProvider.getClient()
                api.playlistsApi.updatePlaylist(
                    playlistId = playlistUuid,
                    data = UpdatePlaylistDto(name = remoteName),
                )
                if (targetFolder != currentFolder) {
                    val userUuid = authSessionStore.getSession()?.userId?.let(::parseUuid)
                    if (userUuid != null) {
                        syncPlaylistFolderTagBestEffort(api, userUuid, playlistUuid, targetFolder)
                    }
                }
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                syncRepository.enqueuePlaylistMutation(payload)
                AppResult.Success(Unit)
            }
        }

    override suspend fun movePlaylistToFolder(
        playlistId: String,
        folderName: String?,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val playlistUuid = parseUuid(playlistId)
            ?: return@withContext AppResult.Error(AppError.Validation("Invalid playlist ID"))
        val existing = playlistDao.getByIdSnapshot(playlistId)
            ?: return@withContext AppResult.Error(AppError.Validation("Playlist not found"))

        val targetFolder = PlaylistFolderCodec.normalizeFolderName(folderName)
        val remoteName = PlaylistFolderCodec.formatRemoteName(existing.name, targetFolder)

        playlistDao.updateNameAndFolder(playlistId, existing.name, targetFolder)
        val payload = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_SET_FOLDER,
            playlistId = playlistId,
            newName = remoteName,
            folderName = targetFolder,
        )
        if (isOffline()) {
            syncRepository.enqueuePlaylistMutation(payload)
            return@withContext AppResult.Success(Unit)
        }

        try {
            val api = clientProvider.getClient()
            api.playlistsApi.updatePlaylist(
                playlistId = playlistUuid,
                data = UpdatePlaylistDto(name = remoteName),
            )
            val userUuid = authSessionStore.getSession()?.userId?.let(::parseUuid)
            if (userUuid != null) {
                syncPlaylistFolderTagBestEffort(api, userUuid, playlistUuid, targetFolder)
            }
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            syncRepository.enqueuePlaylistMutation(payload)
            AppResult.Success(Unit)
        }
    }

    override suspend fun renameFolder(
        oldFolderName: String,
        newFolderName: String?,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val normalizedOld = PlaylistFolderCodec.normalizeFolderName(oldFolderName)
            ?: return@withContext AppResult.Success(Unit)
        val normalizedNew = PlaylistFolderCodec.normalizeFolderName(newFolderName)
        if (normalizedOld.equals(normalizedNew, ignoreCase = false)) {
            return@withContext AppResult.Success(Unit)
        }

        val matching = playlistDao.getAllSnapshot().filter {
            PlaylistFolderCodec.normalizeFolderName(it.folderName)
                ?.equals(normalizedOld, ignoreCase = true) == true
        }
        matching.forEach { playlist ->
            movePlaylistToFolder(playlist.id, normalizedNew)
        }
        AppResult.Success(Unit)
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

    private suspend fun syncPlaylistFolderTagBestEffort(
        api: ApiClient,
        userUuid: UUID,
        playlistUuid: UUID,
        folderName: String?,
    ) {
        try {
            val currentDto = api.userLibraryApi.getItem(
                itemId = playlistUuid,
                userId = userUuid,
            ).content
            val updatedTags = PlaylistFolderCodec.buildUpdatedTags(currentDto.tags, folderName)
            if (updatedTags != currentDto.tags.orEmpty()) {
                api.itemUpdateApi.updateItem(
                    itemId = playlistUuid,
                    data = currentDto.copy(tags = updatedTags),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Non-admin Jellyfin accounts may receive 403 on ItemUpdateApi; the naming convention
            // (`Folder / Name`) already persists the folder reliably across all clients.
        }
    }

    private fun parseUuid(raw: String): UUID? = raw.toUUIDOrNull()
}
