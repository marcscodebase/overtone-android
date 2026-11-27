package com.musicplayer.feature.home

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.InstantMixSeedKind
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.core.model.UserPreferences
import com.musicplayer.feature.library.LibraryCollectionSection
import com.musicplayer.feature.library.LibraryListItem
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Zone 1 ("Quick Access / Jump Back In"): 2x3 compact tiles at the very top of Home
 * for sub-second return to daily listening staples.
 */
sealed interface QuickAccessTarget {
    data class AlbumTarget(val albumId: String) : QuickAccessTarget
    data class PlaylistTarget(val playlistId: String) : QuickAccessTarget
    data class ArtistTarget(val artistId: String) : QuickAccessTarget
    data class CollectionTarget(val section: LibraryCollectionSection) : QuickAccessTarget
}

enum class QuickAccessKind {
    ALBUM,
    PLAYLIST,
    ARTIST,
    FAVORITES,
    DOWNLOADED,
}

data class QuickAccessTile(
    val id: String,
    val title: String,
    val artworkUrl: String?,
    val kind: QuickAccessKind,
    val isPinned: Boolean,
    val target: QuickAccessTarget,
)

/**
 * Zone 2 ("Your Mixes"): Ready-to-play 1-tap smart mixes bridging everyday habit and discovery.
 */
enum class SmartMixType(val routeKey: String) {
    DAYLIST("daylist"),
    ON_REPEAT("on_repeat"),
    TIME_CAPSULE("time_capsule"),
    DEEP_CUTS("deep_cuts"),
    JELLYFIN_INSTANT_MIX("instant_mix"),
    GENRE_MIX("genre_mix"),
    DECADE_MIX("decade_mix"),
    ;

    companion object {
        fun fromRouteKey(key: String?): SmartMixType =
            entries.firstOrNull { it.routeKey.equals(key, ignoreCase = true) } ?: ON_REPEAT
    }
}

data class SmartMixPreview(
    val type: SmartMixType,
    val title: String,
    val subtitle: String,
    val trackCount: Int,
    val artworkUrls: List<String>,
    val tracks: List<Track>,
    val seedId: String? = null,
    val seedName: String? = null,
    val fromJellyfinServer: Boolean = false,
)

data class InstantMixSeed(
    val kind: InstantMixSeedKind,
    val id: String,
    val name: String,
)

/**
 * Zone 3 ("From Your Shelf"): Curated Album Spotlight & Discovery Shelves.
 */
enum class SpotlightReason {
    FROM_ARTIST_YOU_LOVE,
    HAVENT_PLAYED_IN_A_WHILE,
    UNPLAYED_IN_CATALOG,
    RECENTLY_ADDED_UNPLAYED,
}

data class AlbumSpotlight(
    val album: Album,
    val reason: SpotlightReason,
    val daysSinceLastPlayed: Int? = null,
)

data class RediscoverAlbumItem(
    val album: Album,
    val lastPlayedAt: Long,
    val daysSinceLastPlayed: Int,
)

enum class ExploreTagKind {
    GENRE,
    DECADE,
}

data class ExploreTag(
    val id: String,
    val label: String,
    val kind: ExploreTagKind,
    val value: String,
    val trackCount: Int,
)

data class HomeFeedSnapshot(
    val quickAccessTiles: List<QuickAccessTile> = emptyList(),
    val daylist: Daylist? = null,
    val smartMixes: List<SmartMixPreview> = emptyList(),
    val instantMixSeed: InstantMixSeed? = null,
    val spotlightCandidates: List<AlbumSpotlight> = emptyList(),
    val exploreTags: List<ExploreTag> = emptyList(),
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val moreFromArtistsYouLove: List<Album> = emptyList(),
    val rediscoverAlbums: List<RediscoverAlbumItem> = emptyList(),
    val unplayedGems: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val recentlyPlayedTracks: List<Track> = emptyList(),
)

/**
 * Pure, deterministic, offline-first discovery engine for the 3-zone Home screen.
 *
 * - **Online:** Uses the full synced Jellyfin catalog cached in Room and enriches the
 *   Instant Mix card with Jellyfin's native `InstantMixApi` when available.
 * - **Offline:** Gracefully shifts Quick Access, Smart Mixes, Album Spotlights, and
 *   Discovery Shelves toward downloaded local tracks/albums so Home stays genuinely playable
 *   even with zero network connectivity.
 */
object HomeDiscoveryEngine {

    private const val QUICK_ACCESS_LIMIT = 6
    private const val MIX_TRACK_LIMIT = 40
    private const val REDISCOVER_MIN_AGE_DAYS = 14
    private const val TIME_CAPSULE_MIN_AGE_DAYS = 21

    fun buildHomeFeed(
        allTracks: List<Track>,
        allAlbums: List<Album>,
        allArtists: List<Artist>,
        playlists: List<Playlist>,
        pinnedKeys: List<String>,
        slot: DaylistSlot,
        daylistRefreshNonce: Long = 0L,
        isOffline: Boolean = false,
        jellyfinInstantMixTracks: List<Track> = emptyList(),
        jellyfinInstantMixFromServer: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        today: LocalDate = slot.date,
    ): HomeFeedSnapshot {
        val activeTracks = if (isOffline) {
            allTracks.filter { it.isDownloaded }.ifEmpty { allTracks }
        } else {
            allTracks
        }
        val downloadedAlbumIds = allTracks
            .asSequence()
            .filter { it.isDownloaded }
            .mapNotNull { it.albumId }
            .toSet()

        val activeAlbums = if (isOffline && downloadedAlbumIds.isNotEmpty()) {
            allAlbums.filter { it.id in downloadedAlbumIds }
        } else {
            allAlbums
        }

        val albumsById = allAlbums.associateBy { it.id }
        val artistsById = allArtists.associateBy { it.id }
        val playlistsById = playlists.associateBy { it.id }

        val albumStats = computeAlbumStats(allTracks)
        val artistStats = computeArtistStats(allTracks, allArtists)

        // --- Zone 1: 2x3 Quick Access Grid ---
        val quickAccessTiles = buildQuickAccessTiles(
            allTracks = allTracks,
            albumsById = albumsById,
            artistsById = artistsById,
            playlists = playlists,
            playlistsById = playlistsById,
            pinnedKeys = pinnedKeys,
            downloadedAlbumIds = downloadedAlbumIds,
            isOffline = isOffline,
        )

        // --- Zone 2: Smart Mixes ---
        val daylist = if (activeTracks.isNotEmpty()) {
            DaylistGenerator.generate(
                allTracks = activeTracks,
                slot = slot,
                refreshNonce = daylistRefreshNonce,
                nowMs = nowMs,
            )
        } else {
            null
        }

        val instantMixSeed = pickInstantMixSeed(
            tracks = activeTracks,
            artistsById = artistsById,
            dayKey = today.toString(),
        )

        val onRepeatTracks = buildOnRepeatTracks(activeTracks, nowMs = nowMs)
        val timeCapsuleTracks = buildTimeCapsuleTracks(activeTracks, nowMs = nowMs, dayKey = today.toString())
        val deepCutsTracks = buildDeepCutsTracks(
            tracks = activeTracks,
            lovedArtistIds = artistStats.lovedArtistIds,
            dayKey = today.toString(),
        )

        val effectiveInstantMixTracks = if (!isOffline && jellyfinInstantMixTracks.isNotEmpty()) {
            jellyfinInstantMixTracks
        } else if (instantMixSeed != null) {
            buildLocalSeedMix(
                tracks = activeTracks,
                seed = instantMixSeed,
                dayKey = today.toString(),
            )
        } else {
            emptyList()
        }

        val smartMixes = buildList {
            if (daylist != null && daylist.tracks.isNotEmpty()) {
                add(
                    SmartMixPreview(
                        type = SmartMixType.DAYLIST,
                        title = "Daylist",
                        subtitle = slot.period.name,
                        trackCount = daylist.tracks.size,
                        artworkUrls = daylist.artworkUrls,
                        tracks = daylist.tracks,
                    ),
                )
            }
            if (onRepeatTracks.size >= 3) {
                add(
                    SmartMixPreview(
                        type = SmartMixType.ON_REPEAT,
                        title = "On Repeat",
                        subtitle = "Your current rotation",
                        trackCount = onRepeatTracks.size,
                        artworkUrls = extractMosaic(onRepeatTracks),
                        tracks = onRepeatTracks,
                    ),
                )
            }
            if (effectiveInstantMixTracks.size >= 3 && instantMixSeed != null) {
                add(
                    SmartMixPreview(
                        type = SmartMixType.JELLYFIN_INSTANT_MIX,
                        title = "${instantMixSeed.name} Mix",
                        subtitle = if (jellyfinInstantMixFromServer && !isOffline) {
                            "Jellyfin Instant Mix"
                        } else {
                            "Local Smart Mix"
                        },
                        trackCount = effectiveInstantMixTracks.size,
                        artworkUrls = extractMosaic(effectiveInstantMixTracks),
                        tracks = effectiveInstantMixTracks,
                        seedId = instantMixSeed.id,
                        seedName = instantMixSeed.name,
                        fromJellyfinServer = jellyfinInstantMixFromServer && !isOffline,
                    ),
                )
            }
            if (timeCapsuleTracks.size >= 3) {
                add(
                    SmartMixPreview(
                        type = SmartMixType.TIME_CAPSULE,
                        title = "Time Capsule",
                        subtitle = "Rediscover old favorites",
                        trackCount = timeCapsuleTracks.size,
                        artworkUrls = extractMosaic(timeCapsuleTracks),
                        tracks = timeCapsuleTracks,
                    ),
                )
            }
            if (deepCutsTracks.size >= 3) {
                add(
                    SmartMixPreview(
                        type = SmartMixType.DEEP_CUTS,
                        title = "Deep Cuts",
                        subtitle = "Unplayed in your catalog",
                        trackCount = deepCutsTracks.size,
                        artworkUrls = extractMosaic(deepCutsTracks),
                        tracks = deepCutsTracks,
                    ),
                )
            }
        }

        // --- Zone 3: Conscious Listening & Discovery ---
        val recentlyAddedAll = activeAlbums.sortedByDescending { it.dateAdded }
        val moreFromLoved = buildMoreFromArtistsYouLove(
            albums = activeAlbums,
            albumStats = albumStats,
            lovedArtistIds = artistStats.lovedArtistIds,
            dayKey = today.toString(),
        )
        val rediscoverAlbums = buildRediscoverAlbums(
            albums = activeAlbums,
            albumStats = albumStats,
            nowMs = nowMs,
        )
        val unplayedGems = buildUnplayedGems(
            albums = activeAlbums,
            albumStats = albumStats,
            dayKey = today.toString(),
        )
        val spotlightCandidates = buildSpotlightCandidates(
            moreFromLoved = moreFromLoved,
            rediscoverAlbums = rediscoverAlbums,
            unplayedGems = unplayedGems,
            recentlyAdded = recentlyAddedAll,
            albumStats = albumStats,
            dayKey = today.toString(),
        )
        val exploreTags = buildExploreTags(activeTracks)

        val recentTracks = activeTracks
            .filter { it.lastPlayedAt != null }
            .sortedByDescending { it.lastPlayedAt }
            .take(5)

        val visiblePlaylists = if (isOffline) {
            playlists.filter { it.isAutoSyncDownloaded }.ifEmpty { playlists }.take(5)
        } else {
            playlists.take(5)
        }

        return HomeFeedSnapshot(
            quickAccessTiles = quickAccessTiles,
            daylist = daylist,
            smartMixes = smartMixes,
            instantMixSeed = instantMixSeed,
            spotlightCandidates = spotlightCandidates,
            exploreTags = exploreTags,
            recentlyAddedAlbums = recentlyAddedAll.take(5),
            moreFromArtistsYouLove = moreFromLoved.take(10),
            rediscoverAlbums = rediscoverAlbums.take(10),
            unplayedGems = unplayedGems.take(10),
            playlists = visiblePlaylists,
            recentlyPlayedTracks = recentTracks,
        )
    }

    fun buildOnRepeatTracks(
        tracks: List<Track>,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = MIX_TRACK_LIMIT,
    ): List<Track> {
        val played = tracks.filter { it.playCount > 0 || it.lastPlayedAt != null }
        if (played.isEmpty()) return emptyList()
        return played.sortedByDescending { track ->
            val recencyBonus = track.lastPlayedAt?.let { last ->
                val ageDays = TimeUnit.MILLISECONDS.toDays((nowMs - last).coerceAtLeast(0L))
                when {
                    ageDays <= 7 -> 6.0
                    ageDays <= 30 -> 3.0
                    ageDays <= 90 -> 1.0
                    else -> 0.0
                }
            } ?: 0.0
            val favBonus = if (track.isFavorite) 2.5 else 0.0
            track.playCount.toDouble() * 2.0 + recencyBonus + favBonus
        }.take(limit)
    }

    fun buildTimeCapsuleTracks(
        tracks: List<Track>,
        nowMs: Long = System.currentTimeMillis(),
        dayKey: String = LocalDate.now().toString(),
        refreshNonce: Long = 0L,
        limit: Int = MIX_TRACK_LIMIT,
    ): List<Track> {
        val cutoffMs = nowMs - TimeUnit.DAYS.toMillis(TIME_CAPSULE_MIN_AGE_DAYS.toLong())
        val nostalgic = tracks.filter { track ->
            val last = track.lastPlayedAt
            (track.isFavorite || track.playCount >= 2) && last != null && last < cutoffMs
        }
        val candidates = if (nostalgic.size >= 5) {
            nostalgic.sortedBy { it.lastPlayedAt }
        } else {
            // Graceful fallback when library history is newer: oldest played tracks & favorites
            tracks.filter { it.lastPlayedAt != null && (it.playCount > 0 || it.isFavorite) }
                .sortedBy { it.lastPlayedAt }
                .take(limit * 2)
        }
        if (candidates.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("time_capsule:$dayKey:$refreshNonce"))
        return candidates.take(limit * 2).shuffled(rng).take(limit)
    }

    fun buildDeepCutsTracks(
        tracks: List<Track>,
        lovedArtistIds: Set<String> = emptySet(),
        dayKey: String = LocalDate.now().toString(),
        refreshNonce: Long = 0L,
        limit: Int = MIX_TRACK_LIMIT,
    ): List<Track> {
        val unplayed = tracks.filter { it.playCount == 0 && it.lastPlayedAt == null }
        if (unplayed.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("deep_cuts:$dayKey:$refreshNonce"))
        val byLovedArtists = unplayed
            .filter { t -> t.artistId in lovedArtistIds || t.resolvedArtists.any { it.id in lovedArtistIds } }
            .sortedBy { it.id }
            .shuffled(rng)
        val others = unplayed
            .filterNot { t -> t.artistId in lovedArtistIds || t.resolvedArtists.any { it.id in lovedArtistIds } }
            .sortedBy { it.id }
            .shuffled(rng)

        val picked = LinkedHashMap<String, Track>(limit)
        val lovedQuota = (limit * 0.55f).toInt().coerceAtLeast(1)
        for (t in byLovedArtists) {
            if (picked.size >= lovedQuota || picked.size >= limit) break
            picked[t.id] = t
        }
        for (t in others) {
            if (picked.size >= limit) break
            picked.putIfAbsent(t.id, t)
        }
        for (t in byLovedArtists) {
            if (picked.size >= limit) break
            picked.putIfAbsent(t.id, t)
        }
        return picked.values.toList().shuffled(rng)
    }

    fun buildGenreTracksLocal(
        tracks: List<Track>,
        genreName: String,
        seedSalt: String = LocalDate.now().toString(),
        limit: Int = MIX_TRACK_LIMIT,
    ): List<Track> {
        val matched = tracks.filter { track ->
            track.genres.any { it.equals(genreName, ignoreCase = true) }
        }
        if (matched.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("genre:$genreName:$seedSalt"))
        return matched.sortedBy { it.id }.shuffled(rng).take(limit)
    }

    fun buildDecadeTracks(
        tracks: List<Track>,
        decadeStartYear: Int,
        seedSalt: String = LocalDate.now().toString(),
        limit: Int = MIX_TRACK_LIMIT,
    ): List<Track> {
        val endYear = decadeStartYear + 9
        val matched = tracks.filter { track ->
            val y = track.year ?: return@filter false
            y in decadeStartYear..endYear
        }
        if (matched.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("decade:$decadeStartYear:$seedSalt"))
        return matched.sortedBy { it.id }.shuffled(rng).take(limit)
    }

    fun buildMoreFromArtistsYouLove(
        albums: List<Album>,
        allTracks: List<Track>,
        allArtists: List<Artist>,
        dayKey: String = LocalDate.now().toString(),
    ): List<Album> {
        val albumStats = computeAlbumStats(allTracks)
        val artistStats = computeArtistStats(allTracks, allArtists)
        return buildMoreFromArtistsYouLove(albums, albumStats, artistStats.lovedArtistIds, dayKey)
    }

    fun buildRediscoverAlbums(
        albums: List<Album>,
        allTracks: List<Track>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RediscoverAlbumItem> {
        val albumStats = computeAlbumStats(allTracks)
        return buildRediscoverAlbums(albums, albumStats, nowMs)
    }

    fun buildUnplayedGems(
        albums: List<Album>,
        allTracks: List<Track>,
        dayKey: String = LocalDate.now().toString(),
    ): List<Album> {
        val albumStats = computeAlbumStats(allTracks)
        return buildUnplayedGems(albums, albumStats, dayKey)
    }

    fun extractMosaic(tracks: List<Track>): List<String> =
        tracks.mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            .distinct()
            .take(4)

    private data class AlbumStats(
        val totalPlays: Int,
        val lastPlayedAt: Long?,
        val favoriteCount: Int,
    )

    private data class ArtistStats(
        val lovedArtistIds: Set<String>,
    )

    private fun computeAlbumStats(tracks: List<Track>): Map<String, AlbumStats> {
        val map = HashMap<String, AlbumStats>()
        for (track in tracks) {
            val albumId = track.albumId ?: continue
            val existing = map[albumId]
            val plays = (existing?.totalPlays ?: 0) + track.playCount
            val lastPlayed = listOfNotNull(existing?.lastPlayedAt, track.lastPlayedAt).maxOrNull()
            val favs = (existing?.favoriteCount ?: 0) + if (track.isFavorite) 1 else 0
            map[albumId] = AlbumStats(totalPlays = plays, lastPlayedAt = lastPlayed, favoriteCount = favs)
        }
        return map
    }

    private fun computeArtistStats(tracks: List<Track>, artists: List<Artist>): ArtistStats {
        val playByArtist = HashMap<String, Int>()
        val favByArtist = HashMap<String, Int>()
        for (track in tracks) {
            val ids = track.resolvedArtists.map { it.id }.ifEmpty { listOfNotNull(track.artistId) }
            for (id in ids) {
                playByArtist[id] = (playByArtist[id] ?: 0) + track.playCount
                if (track.isFavorite) {
                    favByArtist[id] = (favByArtist[id] ?: 0) + 1
                }
            }
        }
        val loved = LinkedHashSet<String>()
        artists.filter { it.isFavorite }.forEach { loved += it.id }
        favByArtist.filterValues { it >= 2 }.keys.forEach { loved += it }
        playByArtist.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .take(25)
            .forEach { loved += it.key }
        return ArtistStats(lovedArtistIds = loved)
    }

    private fun buildQuickAccessTiles(
        allTracks: List<Track>,
        albumsById: Map<String, Album>,
        artistsById: Map<String, Artist>,
        playlists: List<Playlist>,
        playlistsById: Map<String, Playlist>,
        pinnedKeys: List<String>,
        downloadedAlbumIds: Set<String>,
        isOffline: Boolean,
    ): List<QuickAccessTile> {
        val tiles = LinkedHashMap<String, QuickAccessTile>(QUICK_ACCESS_LIMIT)

        // 1) Most recently played albums (up to 3) — highest everyday resume utility
        val recentAlbumIds = allTracks
            .asSequence()
            .filter { it.lastPlayedAt != null && !it.albumId.isNullOrBlank() }
            .filter { !isOffline || it.isDownloaded }
            .groupBy { it.albumId!! }
            .mapValues { (_, list) -> list.maxOf { it.lastPlayedAt ?: 0L } }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

        var recentAlbumsAdded = 0
        for (albumId in recentAlbumIds) {
            if (recentAlbumsAdded >= 3 || tiles.size >= QUICK_ACCESS_LIMIT) break
            val album = albumsById[albumId] ?: continue
            val tileId = LibraryListItem.albumPinKey(album.id)
            tiles[tileId] = QuickAccessTile(
                id = tileId,
                title = album.title,
                artworkUrl = album.artworkUrl,
                kind = QuickAccessKind.ALBUM,
                isPinned = tileId in pinnedKeys,
                target = QuickAccessTarget.AlbumTarget(album.id),
            )
            recentAlbumsAdded++
        }

        // 2) Pinned Library staples (Favorite Songs, Downloaded Songs, pinned playlists/albums/artists)
        val hasFavorites = allTracks.any { it.isFavorite && (!isOffline || it.isDownloaded) }
        val hasDownloaded = allTracks.any { it.isDownloaded }
        val effectivePins = if (isOffline) {
            listOf(UserPreferences.PIN_KEY_DOWNLOADED) + pinnedKeys.filterNot { it == UserPreferences.PIN_KEY_DOWNLOADED }
        } else {
            pinnedKeys
        }

        for (pinKey in effectivePins) {
            if (tiles.size >= QUICK_ACCESS_LIMIT) break
            if (pinKey in tiles) continue
            when {
                pinKey == UserPreferences.PIN_KEY_FAVORITES && hasFavorites -> {
                    tiles[pinKey] = QuickAccessTile(
                        id = pinKey,
                        title = "Favorite Songs",
                        artworkUrl = null,
                        kind = QuickAccessKind.FAVORITES,
                        isPinned = true,
                        target = QuickAccessTarget.CollectionTarget(LibraryCollectionSection.FAVORITES),
                    )
                }

                pinKey == UserPreferences.PIN_KEY_DOWNLOADED && hasDownloaded -> {
                    tiles[pinKey] = QuickAccessTile(
                        id = pinKey,
                        title = "Downloaded Songs",
                        artworkUrl = null,
                        kind = QuickAccessKind.DOWNLOADED,
                        isPinned = true,
                        target = QuickAccessTarget.CollectionTarget(LibraryCollectionSection.DOWNLOADED),
                    )
                }

                pinKey.startsWith("playlist:") -> {
                    val id = pinKey.removePrefix("playlist:")
                    val playlist = playlistsById[id] ?: continue
                    if (isOffline && !playlist.isAutoSyncDownloaded) continue
                    tiles[pinKey] = QuickAccessTile(
                        id = pinKey,
                        title = playlist.name,
                        artworkUrl = playlist.artworkUrl,
                        kind = QuickAccessKind.PLAYLIST,
                        isPinned = true,
                        target = QuickAccessTarget.PlaylistTarget(playlist.id),
                    )
                }

                pinKey.startsWith("album:") -> {
                    val id = pinKey.removePrefix("album:")
                    val album = albumsById[id] ?: continue
                    if (isOffline && album.id !in downloadedAlbumIds) continue
                    tiles[pinKey] = QuickAccessTile(
                        id = pinKey,
                        title = album.title,
                        artworkUrl = album.artworkUrl,
                        kind = QuickAccessKind.ALBUM,
                        isPinned = true,
                        target = QuickAccessTarget.AlbumTarget(album.id),
                    )
                }

                pinKey.startsWith("artist:") -> {
                    if (isOffline) continue
                    val id = pinKey.removePrefix("artist:")
                    val artist = artistsById[id] ?: continue
                    tiles[pinKey] = QuickAccessTile(
                        id = pinKey,
                        title = artist.name,
                        artworkUrl = artist.artworkUrl,
                        kind = QuickAccessKind.ARTIST,
                        isPinned = true,
                        target = QuickAccessTarget.ArtistTarget(artist.id),
                    )
                }
            }
        }

        // 3) Backfill remaining slots up to 6 with playlists, downloaded/favorited albums
        val candidatePlaylists = if (isOffline) {
            playlists.filter { it.isAutoSyncDownloaded }
        } else {
            playlists
        }
        for (playlist in candidatePlaylists) {
            if (tiles.size >= QUICK_ACCESS_LIMIT) break
            val tileId = LibraryListItem.playlistPinKey(playlist.id)
            if (tileId in tiles) continue
            tiles[tileId] = QuickAccessTile(
                id = tileId,
                title = playlist.name,
                artworkUrl = playlist.artworkUrl,
                kind = QuickAccessKind.PLAYLIST,
                isPinned = false,
                target = QuickAccessTarget.PlaylistTarget(playlist.id),
            )
        }

        val candidateAlbums = albumsById.values
            .filter { !isOffline || it.id in downloadedAlbumIds }
            .sortedByDescending { it.isFavorite }
        for (album in candidateAlbums) {
            if (tiles.size >= QUICK_ACCESS_LIMIT) break
            val tileId = LibraryListItem.albumPinKey(album.id)
            if (tileId in tiles) continue
            tiles[tileId] = QuickAccessTile(
                id = tileId,
                title = album.title,
                artworkUrl = album.artworkUrl,
                kind = QuickAccessKind.ALBUM,
                isPinned = false,
                target = QuickAccessTarget.AlbumTarget(album.id),
            )
        }

        return tiles.values.toList()
    }

    private fun pickInstantMixSeed(
        tracks: List<Track>,
        artistsById: Map<String, Artist>,
        dayKey: String,
    ): InstantMixSeed? {
        if (tracks.isEmpty()) return null
        val scoredArtists = tracks
            .asSequence()
            .filter { (it.playCount > 0 || it.isFavorite) && !it.artistId.isNullOrBlank() }
            .groupBy { it.artistId!! }
            .mapNotNull { (artistId, artistTracks) ->
                val name = artistsById[artistId]?.name
                    ?: artistTracks.firstOrNull()?.artistName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val score = artistTracks.sumOf { it.playCount + if (it.isFavorite) 3 else 0 }
                Triple(artistId, name, score)
            }
            .sortedByDescending { it.third }
            .take(8)

        if (scoredArtists.isNotEmpty()) {
            val rng = Random(DaylistGenerator.fnv1a64("instant_seed:$dayKey"))
            val chosen = scoredArtists[rng.nextInt(scoredArtists.size)]
            return InstantMixSeed(
                kind = InstantMixSeedKind.ARTIST,
                id = chosen.first,
                name = chosen.second,
            )
        }

        val fallbackTrack = tracks.firstOrNull { !it.artistId.isNullOrBlank() } ?: return null
        return InstantMixSeed(
            kind = InstantMixSeedKind.ARTIST,
            id = fallbackTrack.artistId!!,
            name = fallbackTrack.artistName,
        )
    }

    private fun buildLocalSeedMix(
        tracks: List<Track>,
        seed: InstantMixSeed,
        dayKey: String,
    ): List<Track> {
        val seedTracks = tracks.filter {
            it.artistId == seed.id || it.resolvedArtists.any { a -> a.id == seed.id }
        }
        val seedGenres = seedTracks.flatMap { it.genres }.map { it.lowercase() }.toSet()
        val seedDecade = seedTracks.mapNotNull { it.year }.firstOrNull()?.let { (it / 10) * 10 }
        val rng = Random(DaylistGenerator.fnv1a64("local_seed_mix:${seed.id}:$dayKey"))

        val sameGenre = if (seedGenres.isNotEmpty()) {
            tracks.filter { t ->
                t.artistId != seed.id && t.genres.any { it.lowercase() in seedGenres }
            }.sortedBy { it.id }.shuffled(rng)
        } else {
            emptyList()
        }
        val sameDecade = if (seedDecade != null) {
            tracks.filter { t ->
                t.artistId != seed.id && t.year != null && (t.year / 10) * 10 == seedDecade
            }.sortedBy { it.id }.shuffled(rng)
        } else {
            emptyList()
        }

        val picked = LinkedHashMap<String, Track>(MIX_TRACK_LIMIT)
        seedTracks.sortedBy { it.id }.shuffled(rng).take(14).forEach { picked[it.id] = it }
        sameGenre.take(20).forEach { picked.putIfAbsent(it.id, it) }
        sameDecade.take(15).forEach { picked.putIfAbsent(it.id, it) }
        if (picked.size < MIX_TRACK_LIMIT) {
            tracks.sortedBy { it.id }.shuffled(rng).forEach { t ->
                if (picked.size < MIX_TRACK_LIMIT) picked.putIfAbsent(t.id, t)
            }
        }
        return picked.values.toList().shuffled(rng).take(MIX_TRACK_LIMIT)
    }

    private fun buildMoreFromArtistsYouLove(
        albums: List<Album>,
        albumStats: Map<String, AlbumStats>,
        lovedArtistIds: Set<String>,
        dayKey: String,
    ): List<Album> {
        if (lovedArtistIds.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("more_from_loved:$dayKey"))
        return albums
            .filter { album ->
                val artistId = album.artistId ?: return@filter false
                val plays = albumStats[album.id]?.totalPlays ?: 0
                artistId in lovedArtistIds && plays <= 1
            }
            .sortedBy { it.id }
            .shuffled(rng)
    }

    private fun buildRediscoverAlbums(
        albums: List<Album>,
        albumStats: Map<String, AlbumStats>,
        nowMs: Long,
    ): List<RediscoverAlbumItem> {
        val playedAlbums = albums.mapNotNull { album ->
            val stats = albumStats[album.id] ?: return@mapNotNull null
            val lastPlayed = stats.lastPlayedAt ?: return@mapNotNull null
            if (stats.totalPlays <= 0 && !album.isFavorite) return@mapNotNull null
            val ageDays = TimeUnit.MILLISECONDS.toDays((nowMs - lastPlayed).coerceAtLeast(0L)).toInt()
            RediscoverAlbumItem(
                album = album,
                lastPlayedAt = lastPlayed,
                daysSinceLastPlayed = ageDays,
            )
        }
        val stale = playedAlbums
            .filter { it.daysSinceLastPlayed >= REDISCOVER_MIN_AGE_DAYS }
            .sortedBy { it.lastPlayedAt }
        return if (stale.isNotEmpty()) {
            stale
        } else {
            playedAlbums.sortedBy { it.lastPlayedAt }
        }
    }

    private fun buildUnplayedGems(
        albums: List<Album>,
        albumStats: Map<String, AlbumStats>,
        dayKey: String,
    ): List<Album> {
        val unplayed = albums.filter { album ->
            val stats = albumStats[album.id]
            stats == null || (stats.totalPlays == 0 && stats.lastPlayedAt == null)
        }
        if (unplayed.isEmpty()) return emptyList()
        val rng = Random(DaylistGenerator.fnv1a64("unplayed_gems:$dayKey"))
        return unplayed.sortedBy { it.id }.shuffled(rng)
    }

    private fun buildSpotlightCandidates(
        moreFromLoved: List<Album>,
        rediscoverAlbums: List<RediscoverAlbumItem>,
        unplayedGems: List<Album>,
        recentlyAdded: List<Album>,
        albumStats: Map<String, AlbumStats>,
        dayKey: String,
    ): List<AlbumSpotlight> {
        val result = LinkedHashMap<String, AlbumSpotlight>()
        val unplayedRecent = recentlyAdded.filter { album ->
            val stats = albumStats[album.id]
            stats == null || stats.totalPlays == 0
        }

        // Interleave diverse discovery reasons so cycling the spotlight feels like flipping through a record shelf
        val maxLen = maxOf(moreFromLoved.size, rediscoverAlbums.size, unplayedGems.size, unplayedRecent.size)
            .coerceAtMost(12)
        for (i in 0 until maxLen) {
            moreFromLoved.getOrNull(i)?.let { album ->
                result.putIfAbsent(
                    album.id,
                    AlbumSpotlight(album = album, reason = SpotlightReason.FROM_ARTIST_YOU_LOVE),
                )
            }
            rediscoverAlbums.getOrNull(i)?.let { item ->
                result.putIfAbsent(
                    item.album.id,
                    AlbumSpotlight(
                        album = item.album,
                        reason = SpotlightReason.HAVENT_PLAYED_IN_A_WHILE,
                        daysSinceLastPlayed = item.daysSinceLastPlayed,
                    ),
                )
            }
            unplayedGems.getOrNull(i)?.let { album ->
                result.putIfAbsent(
                    album.id,
                    AlbumSpotlight(album = album, reason = SpotlightReason.UNPLAYED_IN_CATALOG),
                )
            }
            unplayedRecent.getOrNull(i)?.let { album ->
                result.putIfAbsent(
                    album.id,
                    AlbumSpotlight(album = album, reason = SpotlightReason.RECENTLY_ADDED_UNPLAYED),
                )
            }
            if (result.size >= 24) break
        }

        if (result.isEmpty() && recentlyAdded.isNotEmpty()) {
            val rng = Random(DaylistGenerator.fnv1a64("spotlight_fallback:$dayKey"))
            recentlyAdded.take(12).shuffled(rng).forEach { album ->
                result.putIfAbsent(
                    album.id,
                    AlbumSpotlight(album = album, reason = SpotlightReason.RECENTLY_ADDED_UNPLAYED),
                )
            }
        }
        return result.values.toList()
    }

    private fun buildExploreTags(tracks: List<Track>): List<ExploreTag> {
        val tags = mutableListOf<ExploreTag>()

        // 1) Top synced Jellyfin genres (min 3 tracks)
        val genreCounts = HashMap<String, Pair<String, Int>>()
        for (track in tracks) {
            for (raw in track.genres) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val key = trimmed.lowercase()
                val existing = genreCounts[key]
                genreCounts[key] = (existing?.first ?: trimmed) to ((existing?.second ?: 0) + 1)
            }
        }
        genreCounts.values
            .filter { it.second >= 3 }
            .sortedByDescending { it.second }
            .take(8)
            .forEach { (name, count) ->
                tags += ExploreTag(
                    id = "genre:${name.lowercase()}",
                    label = name,
                    kind = ExploreTagKind.GENRE,
                    value = name,
                    trackCount = count,
                )
            }

        // 2) Decades from track production years (always available even before genre sync!)
        val decadeCounts = HashMap<Int, Int>()
        for (track in tracks) {
            val year = track.year ?: continue
            if (year in 1920..2099) {
                val decade = (year / 10) * 10
                decadeCounts[decade] = (decadeCounts[decade] ?: 0) + 1
            }
        }
        decadeCounts.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.key }
            .take(if (tags.isEmpty()) 8 else 5)
            .forEach { (decade, count) ->
                val label = if (decade >= 2000) "${decade}s" else "${decade % 100}s"
                tags += ExploreTag(
                    id = "decade:$decade",
                    label = label,
                    kind = ExploreTagKind.DECADE,
                    value = decade.toString(),
                    trackCount = count,
                )
            }

        return tags
    }
}
