package com.musicplayer.feature.home

import com.musicplayer.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DaylistGeneratorTest {

    private fun track(id: String, artwork: String? = "https://img/$id") = Track(
        id = id,
        title = "Track $id",
        artistId = "a1",
        artistName = "Artist",
        albumId = "alb-$id",
        albumName = "Album $id",
        trackNumber = 1,
        discNumber = 1,
        durationMs = 200_000L,
        container = "flac",
        codec = "flac",
        bitrate = null,
        sampleRate = 44_100,
        bitDepth = 16,
        isFavorite = false,
        playCount = 0,
        lastPlayedAt = null,
        hasLyrics = false,
        artworkUrl = artwork,
    )

    @Test
    fun `slot splits day into four 6-hour windows`() {
        val lateNight = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 2, 15, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.LATE_NIGHT, lateNight.period)
        assertEquals("06:00", lateNight.nextRefreshTimeLabel)

        val morning = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 9, 30, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.MORNING, morning.period)
        assertEquals("12:00", morning.nextRefreshTimeLabel)

        val afternoon = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.AFTERNOON, afternoon.period)
        assertEquals("18:00", afternoon.nextRefreshTimeLabel)

        val evening = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 20, 45, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.EVENING, evening.period)
        assertEquals("00:00", evening.nextRefreshTimeLabel)
    }

    @Test
    fun `generates 50 tracks deterministically per slot and changes on 6h rollover or manual refresh`() {
        val library = (1..120).map { track("track-${it.toString().padStart(3, '0')}") }
        val morningSlot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.MORNING)
        val afternoonSlot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.AFTERNOON)
        val eveningSlot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.EVENING)
        val nextDayMorning = DaylistSlot(LocalDate.of(2026, 11, 27), DaylistPeriod.MORNING)

        val morningA = DaylistGenerator.generate(library, morningSlot)
        val morningB = DaylistGenerator.generate(library.reversed(), morningSlot)
        val morningRefreshed = DaylistGenerator.generate(library, morningSlot, refreshNonce = 1L)
        val morningRefreshedTwice = DaylistGenerator.generate(library, morningSlot, refreshNonce = 2L)
        val afternoon = DaylistGenerator.generate(library, afternoonSlot)
        val evening = DaylistGenerator.generate(library, eveningSlot)
        val nextMorning = DaylistGenerator.generate(library, nextDayMorning)

        assertEquals(50, morningA.tracks.size)
        assertEquals(morningA.tracks.map { it.id }, morningB.tracks.map { it.id })
        assertNotEquals(morningA.tracks.map { it.id }, morningRefreshed.tracks.map { it.id })
        assertNotEquals(morningRefreshed.tracks.map { it.id }, morningRefreshedTwice.tracks.map { it.id })
        assertNotEquals(morningA.tracks.map { it.id }, afternoon.tracks.map { it.id })
        assertNotEquals(afternoon.tracks.map { it.id }, evening.tracks.map { it.id })
        assertNotEquals(morningA.tracks.map { it.id }, nextMorning.tracks.map { it.id })
        assertEquals(4, morningA.artworkUrls.size)
    }

    @Test
    fun `handles libraries smaller than 50 tracks and deduplicates mosaic artworks`() {
        val smallLibrary = listOf(
            track("1", artwork = "https://img/same"),
            track("2", artwork = "https://img/same"),
            track("3", artwork = "https://img/other"),
        )
        val slot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.MORNING)
        val daylist = DaylistGenerator.generate(smallLibrary, slot)

        assertEquals(3, daylist.tracks.size)
        assertEquals(setOf("https://img/same", "https://img/other"), daylist.artworkUrls.toSet())
    }
}
