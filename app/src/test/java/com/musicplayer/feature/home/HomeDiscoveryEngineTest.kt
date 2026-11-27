package com.musicplayer.feature.home

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class HomeDiscoveryEngineTest {

    private val nowMs = 1_800_000_000_000L
    private val today = LocalDate.of(2026, 11, 27)
    private val slot = DaylistSlot(today, DaylistPeriod.AFTERNOON)

    private fun track(
        id: String,
        albumId: String = "alb-$id",
        artistId: String = "art-$id",
        artistName: String = "Artist $artistId",
        playCount: Int = 0,
        lastPlayedDaysAgo: Int? = null,
        isFavorite: Boolean = false,
        isDownloaded: Boolean = false,
        year: Int? = 2020,
        genres: List<String> = emptyList(),
    ) = Track(
        id = id,
        title = "Track $id",
        artistId = artistId,
        artistName = artistName,
        albumId = albumId,
        albumName = "Album $albumId",
        trackNumber = 1,
        discNumber = 1,
        durationMs = 210_000L,
        container = "flac",
        codec = "flac",
        bitrate = null,
        sampleRate = 44_100,
        bitDepth = 16,
        isFavorite = isFavorite,
        playCount = playCount,
        lastPlayedAt = lastPlayedDaysAgo?.let { nowMs - TimeUnit.DAYS.toMillis(it.toLong()) },
        hasLyrics = false,
        artworkUrl = "https://img/$albumId",
        isDownloaded = isDownloaded,
        year = year,
        genres = genres,
    )

    private fun album(
        id: String,
        title: String = "Album $id",
        artistId: String = "art-$id",
        artistName: String = "Artist $artistId",
        dateAdded: Long = 100L,
        year: Int? = 2020,
        isFavorite: Boolean = false,
    ) = Album(
        id = id,
        title = title,
        artistId = artistId,
        artistName = artistName,
        year = year,
        trackCount = 10,
        isFavorite = isFavorite,
        dateAdded = dateAdded,
        artworkUrl = "https://img/$id",
    )

    private fun artist(id: String, name: String, isFavorite: Boolean = false) = Artist(
        id = id,
        name = name,
        albumCount = 2,
        isFavorite = isFavorite,
        artworkUrl = null,
    )

    private fun playlist(id: String, name: String, autoSync: Boolean = false) = Playlist(
        id = id,
        name = name,
        trackCount = 15,
        isAutoSyncDownloaded = autoSync,
        artworkUrl = null,
    )

    @Test
    fun `builds 3-zone home feed with quick access, smart mixes, spotlight, genres and discovery shelves`() {
        val artists = listOf(
            artist("art-loved", "Pink Floyd", isFavorite = true),
            artist("art-old", "Radiohead", isFavorite = true),
            artist("art-new", "Khruangbin", isFavorite = false),
        )
        val albums = listOf(
            album("alb-recent", "Dark Side", artistId = "art-loved", artistName = "Pink Floyd", dateAdded = 500L, year = 1973),
            album("alb-loved-unplayed", "Animals", artistId = "art-loved", artistName = "Pink Floyd", dateAdded = 400L, year = 1977),
            album("alb-rediscover", "OK Computer", artistId = "art-old", artistName = "Radiohead", dateAdded = 300L, year = 1997),
            album("alb-unplayed", "Mordechai", artistId = "art-new", artistName = "Khruangbin", dateAdded = 200L, year = 2020),
        )
        val tracks = buildList {
            // Heavy rotation recent tracks on alb-recent (loved artist)
            repeat(5) { i ->
                add(
                    track(
                        id = "t-recent-$i",
                        albumId = "alb-recent",
                        artistId = "art-loved",
                        artistName = "Pink Floyd",
                        playCount = 10 + i,
                        lastPlayedDaysAgo = 1,
                        isFavorite = true,
                        year = 1973,
                        genres = listOf("Progressive Rock", "Rock"),
                    ),
                )
            }
            // Old favorite tracks (60 days ago) for Time Capsule + Rediscover Albums
            repeat(5) { i ->
                add(
                    track(
                        id = "t-old-$i",
                        albumId = "alb-rediscover",
                        artistId = "art-old",
                        artistName = "Radiohead",
                        playCount = 8,
                        lastPlayedDaysAgo = 60,
                        isFavorite = true,
                        year = 1997,
                        genres = listOf("Alternative Rock", "Rock"),
                    ),
                )
            }
            // Unplayed album by loved artist -> More from Artists You Love + Deep Cuts
            repeat(4) { i ->
                add(
                    track(
                        id = "t-loved-unplayed-$i",
                        albumId = "alb-loved-unplayed",
                        artistId = "art-loved",
                        artistName = "Pink Floyd",
                        playCount = 0,
                        year = 1977,
                        genres = listOf("Progressive Rock"),
                    ),
                )
            }
            // Completely unplayed album by another artist -> Unplayed Gems + Deep Cuts
            repeat(4) { i ->
                add(
                    track(
                        id = "t-unplayed-$i",
                        albumId = "alb-unplayed",
                        artistId = "art-new",
                        artistName = "Khruangbin",
                        playCount = 0,
                        year = 2020,
                        genres = listOf("Psychedelic Soul"),
                    ),
                )
            }
        }
        val playlists = listOf(
            playlist("pl-1", "Late Night Drive"),
            playlist("pl-2", "Focus Flow"),
        )

        val feed = HomeDiscoveryEngine.buildHomeFeed(
            allTracks = tracks,
            allAlbums = albums,
            allArtists = artists,
            playlists = playlists,
            pinnedKeys = UserPreferences.DEFAULT_PINNED_LIBRARY_KEYS,
            slot = slot,
            isOffline = false,
            nowMs = nowMs,
            today = today,
        )

        // Zone 1: Quick Access includes most recently played album + pinned Favorite Songs + playlists
        assertTrue(feed.quickAccessTiles.isNotEmpty())
        assertTrue(feed.quickAccessTiles.size <= 6)
        assertEquals(QuickAccessTarget.AlbumTarget("alb-recent"), feed.quickAccessTiles.first().target)
        assertTrue(feed.quickAccessTiles.any { it.kind == QuickAccessKind.FAVORITES })

        // Zone 2: Smart Mixes contain Daylist, On Repeat, Instant Mix, Time Capsule & Deep Cuts
        val mixTypes = feed.smartMixes.map { it.type }.toSet()
        assertTrue(SmartMixType.DAYLIST in mixTypes)
        assertTrue(SmartMixType.ON_REPEAT in mixTypes)
        assertTrue(SmartMixType.JELLYFIN_INSTANT_MIX in mixTypes)
        assertTrue(SmartMixType.TIME_CAPSULE in mixTypes)
        assertTrue(SmartMixType.DEEP_CUTS in mixTypes)

        // Zone 3: Discovery shelves & Spotlight & Genre/Era tags
        assertTrue(feed.spotlightCandidates.isNotEmpty())
        assertTrue(feed.moreFromArtistsYouLove.any { it.id == "alb-loved-unplayed" })
        assertEquals("alb-rediscover", feed.rediscoverAlbums.first().album.id)
        assertTrue(feed.rediscoverAlbums.first().daysSinceLastPlayed >= 59)
        assertTrue(feed.unplayedGems.any { it.id == "alb-unplayed" })
        assertTrue(feed.exploreTags.any { it.kind == ExploreTagKind.GENRE && it.label == "Rock" })
        assertTrue(feed.exploreTags.any { it.kind == ExploreTagKind.DECADE })
    }

    @Test
    fun `offline mode prioritizes downloaded tracks and albums across all three zones`() {
        val albums = listOf(
            album("alb-dl", "Offline Album", artistId = "art-1", dateAdded = 100L),
            album("alb-cloud", "Cloud Only Album", artistId = "art-2", dateAdded = 200L),
        )
        val tracks = buildList {
            repeat(6) { i ->
                add(
                    track(
                        id = "dl-$i",
                        albumId = "alb-dl",
                        artistId = "art-1",
                        playCount = if (i < 3) 4 else 0,
                        lastPlayedDaysAgo = if (i < 3) 30 else null,
                        isFavorite = i == 0,
                        isDownloaded = true,
                        genres = listOf("Jazz"),
                    ),
                )
            }
            repeat(6) { i ->
                add(
                    track(
                        id = "cloud-$i",
                        albumId = "alb-cloud",
                        artistId = "art-2",
                        playCount = 20,
                        lastPlayedDaysAgo = 1,
                        isFavorite = true,
                        isDownloaded = false,
                        genres = listOf("Pop"),
                    ),
                )
            }
        }
        val playlists = listOf(
            playlist("pl-dl", "Flight Mix", autoSync = true),
            playlist("pl-cloud", "Online Only", autoSync = false),
        )

        val feed = HomeDiscoveryEngine.buildHomeFeed(
            allTracks = tracks,
            allAlbums = albums,
            allArtists = listOf(artist("art-1", "Miles Davis"), artist("art-2", "Cloud Artist")),
            playlists = playlists,
            pinnedKeys = UserPreferences.DEFAULT_PINNED_LIBRARY_KEYS,
            slot = slot,
            isOffline = true,
            nowMs = nowMs,
            today = today,
        )

        // All smart mixes only contain downloaded tracks
        assertTrue(feed.smartMixes.isNotEmpty())
        feed.smartMixes.forEach { mix ->
            assertTrue("Mix ${mix.type} must only include downloaded tracks offline", mix.tracks.all { it.isDownloaded })
            assertFalse(mix.fromJellyfinServer)
        }
        // Quick access excludes non-downloaded cloud album and cloud-only playlist
        assertFalse(feed.quickAccessTiles.any { it.target == QuickAccessTarget.AlbumTarget("alb-cloud") })
        assertFalse(feed.quickAccessTiles.any { it.target == QuickAccessTarget.PlaylistTarget("pl-cloud") })
        assertTrue(feed.quickAccessTiles.any { it.kind == QuickAccessKind.DOWNLOADED })
        // Album shelves only surface downloaded albums
        assertTrue(feed.recentlyAddedAlbums.all { it.id == "alb-dl" })
    }
}
