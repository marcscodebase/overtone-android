package com.musicplayer.feature.library.detail

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ArtistCatalogHelperTest {

    private fun epoch(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun album(
        id: String,
        title: String,
        year: Int?,
        premiereDate: Long? = null,
    ) = Album(
        id = id,
        title = title,
        artistId = "artist-1",
        artistName = "Artist",
        year = year,
        trackCount = 10,
        isFavorite = false,
        dateAdded = 0L,
        artworkUrl = null,
        premiereDate = premiereDate,
    )

    private fun track(
        id: String,
        title: String,
        albumId: String? = null,
        albumName: String = "Album",
        trackNumber: Int? = 1,
        discNumber: Int? = 1,
        isFavorite: Boolean = false,
        playCount: Int = 0,
        lastPlayedAt: Long? = null,
        year: Int? = null,
        premiereDate: Long? = null,
    ) = Track(
        id = id,
        title = title,
        artistId = "artist-1",
        artistName = "Artist",
        albumId = albumId,
        albumName = albumName,
        trackNumber = trackNumber,
        discNumber = discNumber,
        durationMs = 200_000L,
        container = "flac",
        codec = "flac",
        bitrate = null,
        sampleRate = 44_100,
        bitDepth = 16,
        isFavorite = isFavorite,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        hasLyrics = false,
        artworkUrl = null,
        year = year,
        premiereDate = premiereDate,
    )

    @Test
    fun `topTracks ranks by playCount then fills remaining slots up to limit`() {
        val tracks = listOf(
            track(id = "t1", title = "Alpha", playCount = 0, year = 2020),
            track(id = "t2", title = "Most Played", playCount = 18, lastPlayedAt = 100L),
            track(id = "t3", title = "Second Played", playCount = 5, lastPlayedAt = 200L),
            track(id = "t4", title = "Favorite Unplayed", playCount = 0, isFavorite = true, year = 2022),
            track(id = "t5", title = "Newest Unplayed", playCount = 0, year = 2024),
            track(id = "t6", title = "Older Unplayed", playCount = 0, year = 2018),
        )

        val top5 = ArtistCatalogHelper.topTracks(tracks, emptyMap(), limit = 5)
        assertEquals(listOf("t2", "t3", "t4", "t5", "t1"), top5.map { it.id })

        val top10 = ArtistCatalogHelper.topTracks(tracks, emptyMap(), limit = 10)
        assertEquals(6, top10.size)
        assertEquals("t6", top10.last().id)
    }

    @Test
    fun `favoriteTracks returns only favorites sorted by playCount then recency`() {
        val tracks = listOf(
            track(id = "f1", title = "Fav Old", isFavorite = true, playCount = 2, year = 2019),
            track(id = "n1", title = "Not Fav", isFavorite = false, playCount = 50, year = 2024),
            track(id = "f2", title = "Fav Hot", isFavorite = true, playCount = 9, year = 2021),
            track(id = "f3", title = "Fav New", isFavorite = true, playCount = 2, year = 2024),
        )

        val favorites = ArtistCatalogHelper.favoriteTracks(tracks, emptyMap())
        assertEquals(listOf("f2", "f3", "f1"), favorites.map { it.id })
    }

    @Test
    fun `chronologicalTracks falls back to album year and supports newest and oldest first`() {
        val albums = listOf(
            album(id = "a1", title = "Debut", year = 2018, premiereDate = epoch(2018, 3, 1)),
            album(id = "a2", title = "Late 2023", year = 2023, premiereDate = epoch(2023, 11, 15)),
            album(id = "a3", title = "Early 2023", year = 2023, premiereDate = epoch(2023, 2, 10)),
        )
        val albumsById = albums.associateBy { it.id }

        val tracks = listOf(
            track(id = "u", title = "Unknown Release", albumId = null, albumName = "Loose"),
            track(id = "d2", title = "Debut #2", albumId = "a1", albumName = "Debut", trackNumber = 2),
            track(id = "d1", title = "Debut #1", albumId = "a1", albumName = "Debut", trackNumber = 1),
            track(id = "e1", title = "Early 2023 #1", albumId = "a3", albumName = "Early 2023", trackNumber = 1),
            track(id = "l1", title = "Late 2023 #1", albumId = "a2", albumName = "Late 2023", trackNumber = 1),
        )

        val newest = ArtistCatalogHelper.chronologicalTracks(tracks, albumsById, ChronologicalOrder.NEWEST_FIRST)
        assertEquals(listOf("l1", "e1", "d1", "d2", "u"), newest.map { it.id })

        val oldest = ArtistCatalogHelper.chronologicalTracks(tracks, albumsById, ChronologicalOrder.OLDEST_FIRST)
        assertEquals(listOf("d1", "d2", "e1", "l1", "u"), oldest.map { it.id })
    }

    @Test
    fun `chronologicalAlbums sorts by year and premiereDate with unknown years last`() {
        val albums = listOf(
            album(id = "unknown", title = "No Year", year = null, premiereDate = null),
            album(id = "2020", title = "Mid", year = 2020, premiereDate = epoch(2020, 6, 1)),
            album(id = "2024b", title = "Nov 2024", year = 2024, premiereDate = epoch(2024, 11, 1)),
            album(id = "2024a", title = "Jan 2024", year = 2024, premiereDate = epoch(2024, 1, 15)),
        )

        val newest = ArtistCatalogHelper.chronologicalAlbums(albums, ChronologicalOrder.NEWEST_FIRST)
        assertEquals(listOf("2024b", "2024a", "2020", "unknown"), newest.map { it.id })

        val oldest = ArtistCatalogHelper.chronologicalAlbums(albums, ChronologicalOrder.OLDEST_FIRST)
        assertEquals(listOf("2020", "2024a", "2024b", "unknown"), oldest.map { it.id })
    }
}
