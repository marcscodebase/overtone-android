package com.musicplayer.data.repository

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.AlbumDao
import com.musicplayer.core.database.dao.ArtistDao
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.InstantMixResult
import com.musicplayer.core.model.InstantMixSeedKind
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.SearchResults
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.NetworkMonitor
import com.musicplayer.data.remote.JellyfinArtworkUrlBuilder
import com.musicplayer.data.remote.JellyfinMappers
import com.musicplayer.data.remote.JellyfinMappers.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LibraryRepository {
    fun observeRecentlyAddedAlbums(limit: Int = 20): Flow<List<Album>>
    fun observeRecentlyPlayedTracks(limit: Int = 20): Flow<List<Track>>
    fun observeFavoriteTracks(): Flow<List<Track>>
    fun observeAllAlbums(): Flow<List<Album>>
    fun observeAllArtists(): Flow<List<Artist>>
    fun observeAllTracks(): Flow<List<Track>>
    fun observeAllPlaylists(): Flow<List<Playlist>>
    fun observeDownloadedTracks(): Flow<List<Track>>

    fun observeAlbum(albumId: String): Flow<Album?>
    fun observeAlbumTracks(albumId: String): Flow<List<Track>>
    fun observeArtist(artistId: String): Flow<Artist?>
    fun observeArtistAlbums(artistId: String): Flow<List<Album>>
    fun observeArtistTracks(artistId: String): Flow<List<Track>>
    fun observePlaylist(playlistId: String): Flow<Playlist?>
    fun observePlaylistTracks(playlistId: String): Flow<List<Track>>

    suspend fun getRandomTracks(
        limit: Int,
        downloadedOnly: Boolean = false,
        excludeTrackIds: Set<String> = emptySet(),
    ): List<Track>

    suspend fun getInstantMix(
        seedKind: InstantMixSeedKind,
        seedId: String? = null,
        genreName: String? = null,
        limit: Int = 50,
    ): InstantMixResult

    suspend fun syncLibrary(): AppResult<Unit>
    suspend fun syncPlaylistTracks(playlistId: String): AppResult<Unit>
    suspend fun search(query: String): AppResult<SearchResults>
}

@Singleton
class JellyfinLibraryRepository @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val networkMonitor: NetworkMonitor,
    private val authSessionStore: AuthSessionStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val artworkUrlBuilder: JellyfinArtworkUrlBuilder,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val downloadDao: DownloadDao,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : LibraryRepository {

    private val downloadsByTrackId: Flow<Map<String, DownloadRecordEntity>> =
        downloadDao.observeAllRecords().map { list -> list.associateBy { it.trackId } }

    override fun observeRecentlyAddedAlbums(limit: Int): Flow<List<Album>> =
        albumDao.observeRecentlyAdded(limit).map { albums ->
            albums.map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        }

    override fun observeRecentlyPlayedTracks(limit: Int): Flow<List<Track>> =
        combine(trackDao.observeRecentlyPlayed(limit), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override fun observeFavoriteTracks(): Flow<List<Track>> =
        combine(trackDao.observeFavorites(), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override fun observeAllAlbums(): Flow<List<Album>> =
        albumDao.observeAll().map { albums ->
            albums.map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        }

    override fun observeAllArtists(): Flow<List<Artist>> =
        artistDao.observeAll().map { artists ->
            artists.map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        }

    override fun observeAllTracks(): Flow<List<Track>> =
        combine(trackDao.observeAll(), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override fun observeAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { playlists ->
            playlists.map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        }

    override fun observeDownloadedTracks(): Flow<List<Track>> =
        combine(
            trackDao.observeAll(),
            downloadDao.observeByStatus(DownloadStatus.COMPLETED),
        ) { tracks, downloads ->
            val completedById = downloads.associateBy { it.trackId }
            tracks.mapNotNull { entity ->
                val record = completedById[entity.id] ?: return@mapNotNull null
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = record,
                )
            }
        }

    override fun observeAlbum(albumId: String): Flow<Album?> =
        albumDao.observeById(albumId).map { entity ->
            entity?.toDomain(
                artworkUrlBuilder.buildItemImageUrl(
                    itemId = entity.id,
                    imageTag = entity.primaryImageTag,
                    maxWidth = JellyfinArtworkUrlBuilder.LARGE_MAX_WIDTH,
                ),
            )
        }

    override fun observeAlbumTracks(albumId: String): Flow<List<Track>> =
        combine(trackDao.observeByAlbum(albumId), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override fun observeArtist(artistId: String): Flow<Artist?> =
        artistDao.observeById(artistId).map { entity ->
            entity?.toDomain(
                artworkUrlBuilder.buildItemImageUrl(
                    itemId = entity.id,
                    imageTag = entity.primaryImageTag,
                    maxWidth = JellyfinArtworkUrlBuilder.LARGE_MAX_WIDTH,
                ),
            )
        }

    override fun observeArtistAlbums(artistId: String): Flow<List<Album>> =
        albumDao.observeByArtist(artistId).map { albums ->
            albums.map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        }

    override fun observeArtistTracks(artistId: String): Flow<List<Track>> =
        combine(trackDao.observeByArtist(artistId), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override fun observePlaylist(playlistId: String): Flow<Playlist?> =
        playlistDao.observeById(playlistId).map { entity ->
            entity?.toDomain(
                artworkUrlBuilder.buildItemImageUrl(
                    itemId = entity.id,
                    imageTag = entity.primaryImageTag,
                    maxWidth = JellyfinArtworkUrlBuilder.LARGE_MAX_WIDTH,
                ),
            )
        }

    override fun observePlaylistTracks(playlistId: String): Flow<List<Track>> =
        combine(playlistDao.observeTracksForPlaylist(playlistId), downloadsByTrackId) { tracks, downloads ->
            tracks.map { entity ->
                entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = downloads[entity.id],
                )
            }
        }

    override suspend fun getRandomTracks(
        limit: Int,
        downloadedOnly: Boolean,
        excludeTrackIds: Set<String>,
    ): List<Track> = withContext(ioDispatcher) {
        if (limit <= 0) return@withContext emptyList()
        val fetchLimit = (limit + excludeTrackIds.size.coerceAtMost(MAX_EXCLUDE_OVERSAMPLE))
            .coerceAtLeast(limit)
        val rawEntities = if (downloadedOnly) {
            trackDao.getRandomDownloadedTracks(fetchLimit)
        } else {
            trackDao.getRandomTracks(fetchLimit)
        }
        val filtered = if (excludeTrackIds.isEmpty()) {
            rawEntities.take(limit)
        } else {
            rawEntities.filterNot { it.id in excludeTrackIds }.take(limit)
        }
        if (filtered.isEmpty()) return@withContext emptyList()

        val completedDownloads = downloadDao.getCompletedRecords().associateBy { it.trackId }
        filtered.map { entity ->
            entity.toDomain(
                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                    trackId = entity.id,
                    albumId = entity.albumId,
                    imageTag = entity.primaryImageTag,
                ),
                downloadRecord = completedDownloads[entity.id],
            )
        }
    }

    override suspend fun getInstantMix(
        seedKind: InstantMixSeedKind,
        seedId: String?,
        genreName: String?,
        limit: Int,
    ): InstantMixResult = withContext(ioDispatcher) {
        if (limit <= 0) return@withContext InstantMixResult(emptyList(), fromJellyfinServer = false)
        val prefs = preferencesDataStore.userPreferences.first()
        val offline = prefs.offlineModeEnabled || !networkMonitor.networkState.value.isConnected
        val completedDownloads = downloadDao.getCompletedRecords().associateBy { it.trackId }

        if (!offline) {
            val session = authSessionStore.getSession()
            val userUuid = session?.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val seedUuid = seedId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (userUuid != null) {
                try {
                    val api = clientProvider.getClient()
                    val fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.GENRES)
                    val dtos = when (seedKind) {
                        InstantMixSeedKind.TRACK -> seedUuid?.let { id ->
                            api.instantMixApi.getInstantMixFromSong(
                                itemId = id,
                                userId = userUuid,
                                limit = limit,
                                fields = fields,
                            ).content.items
                        }

                        InstantMixSeedKind.ALBUM -> seedUuid?.let { id ->
                            api.instantMixApi.getInstantMixFromAlbum(
                                itemId = id,
                                userId = userUuid,
                                limit = limit,
                                fields = fields,
                            ).content.items
                        }

                        InstantMixSeedKind.ARTIST -> seedUuid?.let { id ->
                            api.instantMixApi.getInstantMixFromArtists(
                                itemId = id,
                                userId = userUuid,
                                limit = limit,
                                fields = fields,
                            ).content.items
                        }

                        InstantMixSeedKind.PLAYLIST -> seedUuid?.let { id ->
                            api.instantMixApi.getInstantMixFromPlaylist(
                                itemId = id,
                                userId = userUuid,
                                limit = limit,
                                fields = fields,
                            ).content.items
                        }

                        InstantMixSeedKind.GENRE -> genreName?.trim()?.takeIf { it.isNotEmpty() }?.let { genre ->
                            api.instantMixApi.getInstantMixFromMusicGenreByName(
                                name = genre,
                                userId = userUuid,
                                limit = limit,
                                fields = fields,
                            ).content.items
                        }
                    }.orEmpty()

                    val mapped = dtos
                        .filter { it.type == BaseItemKind.AUDIO }
                        .map { dto ->
                            val entity = JellyfinMappers.toTrackEntity(dto)
                            entity.toDomain(
                                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                                    trackId = entity.id,
                                    albumId = entity.albumId,
                                    imageTag = entity.primaryImageTag,
                                ),
                                downloadRecord = completedDownloads[entity.id],
                            )
                        }
                        .distinctBy { it.id }

                    if (mapped.isNotEmpty()) {
                        return@withContext InstantMixResult(
                            tracks = mapped.take(limit),
                            fromJellyfinServer = true,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Fall back cleanly to the local Room mix engine below
                }
            }
        }

        val allLocal = trackDao.getAllSnapshot().map { entity ->
            entity.toDomain(
                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                    trackId = entity.id,
                    albumId = entity.albumId,
                    imageTag = entity.primaryImageTag,
                ),
                downloadRecord = completedDownloads[entity.id],
            )
        }
        val pool = if (offline) {
            allLocal.filter { it.isDownloaded }.ifEmpty { allLocal }
        } else {
            allLocal
        }
        val localTracks = buildLocalInstantMix(
            pool = pool,
            seedKind = seedKind,
            seedId = seedId,
            genreName = genreName,
            limit = limit,
        )
        InstantMixResult(tracks = localTracks, fromJellyfinServer = false)
    }

    private fun buildLocalInstantMix(
        pool: List<Track>,
        seedKind: InstantMixSeedKind,
        seedId: String?,
        genreName: String?,
        limit: Int,
    ): List<Track> {
        if (pool.isEmpty()) return emptyList()
        val targetGenres: Set<String>
        val seedArtistIds: Set<String>
        val seedDecade: Int?

        when (seedKind) {
            InstantMixSeedKind.GENRE -> {
                targetGenres = setOfNotNull(genreName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() })
                seedArtistIds = emptySet()
                seedDecade = null
            }

            InstantMixSeedKind.ARTIST -> {
                val artistTracks = pool.filter { track ->
                    track.artistId == seedId || track.resolvedArtists.any { it.id == seedId }
                }
                targetGenres = artistTracks.flatMap { it.genres }.map { it.lowercase() }.toSet()
                seedArtistIds = setOfNotNull(seedId)
                seedDecade = artistTracks.mapNotNull { it.year }.firstOrNull()?.let { (it / 10) * 10 }
            }

            InstantMixSeedKind.ALBUM -> {
                val albumTracks = pool.filter { it.albumId == seedId }
                targetGenres = albumTracks.flatMap { it.genres }.map { it.lowercase() }.toSet()
                seedArtistIds = albumTracks.mapNotNull { it.artistId }.toSet()
                seedDecade = albumTracks.mapNotNull { it.year }.firstOrNull()?.let { (it / 10) * 10 }
            }

            InstantMixSeedKind.TRACK -> {
                val seedTrack = pool.firstOrNull { it.id == seedId }
                targetGenres = seedTrack?.genres.orEmpty().map { it.lowercase() }.toSet()
                seedArtistIds = seedTrack?.resolvedArtists.orEmpty().map { it.id }.toSet()
                seedDecade = seedTrack?.year?.let { (it / 10) * 10 }
            }

            InstantMixSeedKind.PLAYLIST -> {
                targetGenres = emptySet()
                seedArtistIds = emptySet()
                seedDecade = null
            }
        }

        val primary = mutableListOf<Track>()
        val secondary = mutableListOf<Track>()
        val remainder = mutableListOf<Track>()

        for (track in pool) {
            val matchesGenre = targetGenres.isNotEmpty() &&
                track.genres.any { it.lowercase() in targetGenres }
            val matchesArtist = seedArtistIds.isNotEmpty() &&
                (track.artistId in seedArtistIds || track.resolvedArtists.any { it.id in seedArtistIds })
            val matchesDecade = seedDecade != null && track.year != null &&
                (track.year / 10) * 10 == seedDecade

            when {
                matchesGenre || matchesArtist -> primary += track
                matchesDecade || track.isFavorite || track.playCount > 0 -> secondary += track
                else -> remainder += track
            }
        }

        val picked = LinkedHashMap<String, Track>(limit)
        fun append(source: List<Track>, maxCount: Int) {
            for (track in source.shuffled()) {
                if (picked.size >= limit || picked.size >= maxCount) break
                picked.putIfAbsent(track.id, track)
            }
        }

        if (seedKind == InstantMixSeedKind.GENRE && primary.isNotEmpty()) {
            append(primary, limit)
            if (picked.size < limit) append(secondary, limit)
        } else {
            val primaryCap = (limit * 0.7f).toInt().coerceAtLeast(1)
            append(primary, primaryCap)
            append(secondary, limit)
            append(remainder, limit)
        }
        return picked.values.toList()
    }

    override suspend fun syncLibrary(): AppResult<Unit> = withContext(ioDispatcher) {
        val prefs = preferencesDataStore.userPreferences.first()
        if (prefs.offlineModeEnabled || !networkMonitor.networkState.value.isConnected) {
            return@withContext AppResult.Error(AppError.Network("Offline mode — using cached library"))
        }

        val session = authSessionStore.getSession()
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Not signed in"))

        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: return@withContext AppResult.Error(AppError.Unauthorized("Invalid user session"))

        val selectedLibraries = prefs.selectedMusicLibraryIds
        val parentIds: List<UUID?> = if (selectedLibraries.isEmpty()) {
            listOf(null)
        } else {
            selectedLibraries.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                .ifEmpty { listOf(null) }
        }

        try {
            val api = clientProvider.getClient()
            for (parentId in parentIds) {
                syncArtists(api, userUuid, parentId)
                syncAlbums(api, userUuid, parentId)
                syncTracks(api, userUuid, parentId)
            }
            syncPlaylists(api, userUuid)
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            AppResult.Error(AppError.Network("Could not sync library while offline", io))
        } catch (t: Throwable) {
            AppResult.Error(AppError.Unknown(t.message ?: "Failed to sync Jellyfin library", t))
        }
    }

    override suspend fun syncPlaylistTracks(playlistId: String): AppResult<Unit> =
        withContext(ioDispatcher) {
            val prefs = preferencesDataStore.userPreferences.first()
            if (prefs.offlineModeEnabled || !networkMonitor.networkState.value.isConnected) {
                return@withContext AppResult.Success(Unit)
            }
            val session = authSessionStore.getSession()
                ?: return@withContext AppResult.Error(AppError.Unauthorized("Not signed in"))
            val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            val playlistUuid = runCatching { UUID.fromString(playlistId) }.getOrNull()
                ?: return@withContext AppResult.Error(AppError.Validation("Invalid playlist ID"))

            try {
                val api = clientProvider.getClient()
                val items = api.playlistsApi.getPlaylistItems(
                    playlistId = playlistUuid,
                    userId = userUuid,
                    fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.GENRES),
                ).content.items.orEmpty()

                val trackEntities = items.map(JellyfinMappers::toTrackEntity)
                val crossRefs = items.mapIndexed { index, dto ->
                    PlaylistTrackCrossRef(
                        playlistId = playlistId,
                        trackId = dto.id.toString(),
                        playlistItemId = dto.playlistItemId ?: "${dto.id}_$index",
                        position = index,
                    )
                }
                trackDao.upsertAll(trackEntities)
                playlistDao.replacePlaylistTracks(playlistId, crossRefs)
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                AppResult.Error(AppError.Network("Unable to refresh playlist tracks", io))
            } catch (t: Throwable) {
                AppResult.Error(AppError.Unknown(t.message ?: "Failed to load playlist tracks", t))
            }
        }

    override suspend fun search(query: String): AppResult<SearchResults> =
        withContext(ioDispatcher) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Success(SearchResults())
            }

            val prefs = preferencesDataStore.userPreferences.first()
            if (prefs.offlineModeEnabled || !networkMonitor.networkState.value.isConnected) {
                return@withContext AppResult.Success(searchLocal(trimmed, isOfflineFallback = true))
            }

            val session = authSessionStore.getSession()
                ?: return@withContext AppResult.Success(searchLocal(trimmed, isOfflineFallback = true))
            val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()

            try {
                val api = clientProvider.getClient()
                val response = api.itemsApi.getItems(
                    userId = userUuid,
                    searchTerm = trimmed,
                    recursive = true,
                    includeItemTypes = listOf(
                        BaseItemKind.AUDIO,
                        BaseItemKind.MUSIC_ALBUM,
                        BaseItemKind.MUSIC_ARTIST,
                        BaseItemKind.PLAYLIST,
                    ),
                    fields = listOf(
                        ItemFields.MEDIA_SOURCES,
                        ItemFields.DATE_CREATED,
                        ItemFields.TAGS,
                        ItemFields.GENRES,
                    ),
                    limit = SEARCH_LIMIT,
                ).content.items.orEmpty()

                val downloads = downloadsByTrackId.first()
                val tracks = mutableListOf<Track>()
                val albums = mutableListOf<Album>()
                val artists = mutableListOf<Artist>()
                val playlists = mutableListOf<Playlist>()

                response.forEach { dto ->
                    when (dto.type) {
                        BaseItemKind.AUDIO -> {
                            val entity = JellyfinMappers.toTrackEntity(dto)
                            tracks += entity.toDomain(
                                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                                    trackId = entity.id,
                                    albumId = entity.albumId,
                                    imageTag = entity.primaryImageTag,
                                ),
                                downloadRecord = downloads[entity.id],
                            )
                        }

                        BaseItemKind.MUSIC_ALBUM -> {
                            val entity = JellyfinMappers.toAlbumEntity(dto)
                            albums += entity.toDomain(
                                artworkUrlBuilder.buildItemImageUrl(entity.id, entity.primaryImageTag),
                            )
                        }

                        BaseItemKind.MUSIC_ARTIST -> {
                            val entity = JellyfinMappers.toArtistEntity(dto)
                            artists += entity.toDomain(
                                artworkUrlBuilder.buildItemImageUrl(entity.id, entity.primaryImageTag),
                            )
                        }

                        BaseItemKind.PLAYLIST -> {
                            val entity = JellyfinMappers.toPlaylistEntity(dto)
                            playlists += entity.toDomain(
                                artworkUrlBuilder.buildItemImageUrl(entity.id, entity.primaryImageTag),
                            )
                        }

                        else -> Unit
                    }
                }

                AppResult.Success(
                    SearchResults(
                        tracks = tracks,
                        albums = albums,
                        artists = artists,
                        playlists = playlists,
                        isOfflineFallback = false,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                AppResult.Success(searchLocal(trimmed, isOfflineFallback = true))
            }
        }

    private suspend fun searchLocal(query: String, isOfflineFallback: Boolean): SearchResults {
        val downloads = downloadsByTrackId.first()
        val matchedTracks = trackDao.search(query, limit = 30).first().map { entity ->
            entity.toDomain(
                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                    trackId = entity.id,
                    albumId = entity.albumId,
                    imageTag = entity.primaryImageTag,
                ),
                downloadRecord = downloads[entity.id],
            )
        }.let { tracks ->
            if (isOfflineFallback) tracks.sortedByDescending { it.isDownloaded } else tracks
        }
        val matchedAlbums = albumDao.observeAll().first()
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artistName.contains(query, ignoreCase = true)
            }
            .take(15)
            .map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        val matchedArtists = artistDao.observeAll().first()
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(15)
            .map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }
        val matchedPlaylists = playlistDao.observeAll().first()
            .filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.folderName?.contains(query, ignoreCase = true) == true
            }
            .take(15)
            .map { it.toDomain(artworkUrlBuilder.buildItemImageUrl(it.id, it.primaryImageTag)) }

        return SearchResults(
            tracks = matchedTracks,
            albums = matchedAlbums,
            artists = matchedArtists,
            playlists = matchedPlaylists,
            isOfflineFallback = isOfflineFallback,
        )
    }

    private suspend fun syncArtists(
        api: org.jellyfin.sdk.api.client.ApiClient,
        userId: UUID,
        parentId: UUID?,
    ) {
        var startIndex = 0
        while (true) {
            val result = api.artistsApi.getAlbumArtists(
                userId = userId,
                parentId = parentId,
                startIndex = startIndex,
                limit = PAGE_SIZE,
            ).content
            val items = result.items.orEmpty()
            if (items.isNotEmpty()) {
                artistDao.upsertAll(items.map(JellyfinMappers::toArtistEntity))
            }
            val total = result.totalRecordCount
            startIndex += items.size
            if (items.isEmpty() || startIndex >= total) break
        }
    }

    private suspend fun syncAlbums(
        api: org.jellyfin.sdk.api.client.ApiClient,
        userId: UUID,
        parentId: UUID?,
    ) {
        var startIndex = 0
        while (true) {
            val result = api.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                fields = listOf(ItemFields.DATE_CREATED, ItemFields.GENRES),
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                startIndex = startIndex,
                limit = PAGE_SIZE,
            ).content
            val items = result.items.orEmpty()
            if (items.isNotEmpty()) {
                albumDao.upsertAll(items.map(JellyfinMappers::toAlbumEntity))
            }
            val total = result.totalRecordCount
            startIndex += items.size
            if (items.isEmpty() || startIndex >= total) break
        }
    }

    private suspend fun syncTracks(
        api: org.jellyfin.sdk.api.client.ApiClient,
        userId: UUID,
        parentId: UUID?,
    ) {
        var startIndex = 0
        while (true) {
            val result = api.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.DATE_CREATED, ItemFields.GENRES),
                startIndex = startIndex,
                limit = PAGE_SIZE,
            ).content
            val items = result.items.orEmpty()
            if (items.isNotEmpty()) {
                trackDao.upsertAll(items.map(JellyfinMappers::toTrackEntity))
            }
            val total = result.totalRecordCount
            startIndex += items.size
            if (items.isEmpty() || startIndex >= total) break
        }
    }

    private suspend fun syncPlaylists(
        api: org.jellyfin.sdk.api.client.ApiClient,
        userId: UUID,
    ) {
        val existingById = playlistDao.getAllSnapshot().associateBy { it.id }
        val result = api.itemsApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            fields = listOf(ItemFields.TAGS),
            limit = PAGE_SIZE,
        ).content
        val items = result.items.orEmpty()
        val entities = items.map { dto ->
            val normalizedId = dto.id.toString()
            val existing = existingById[normalizedId]
            JellyfinMappers.toPlaylistEntity(
                dto = dto,
                existingAutoSync = existing?.isAutoSyncDownloaded == true,
                existingDownloadQualityOverride = existing?.downloadQualityOverride,
                existingLastSyncedAt = existing?.lastSyncedAt,
            )
        }
        playlistDao.syncPlaylistsAuthoritative(entities)
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val SEARCH_LIMIT = 60
        private const val MAX_EXCLUDE_OVERSAMPLE = 1_500
    }
}
