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
    fun `slot splits day into morning and evening halves`() {
        val morning = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 9, 30, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.MORNING, morning.period)
        assertEquals("12:00", morning.nextRefreshTimeLabel)

        val evening = DaylistSlot.current(ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC))
        assertEquals(DaylistPeriod.EVENING, evening.period)
        assertEquals("00:00", evening.nextRefreshTimeLabel)
    }

    @Test
    fun `generates 50 tracks deterministically per slot and regenerates twice daily`() {
        val library = (1..120).map { track("track-${it.toString().padStart(3, '0')}") }
        val morningSlot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.MORNING)
        val eveningSlot = DaylistSlot(LocalDate.of(2026, 11, 26), DaylistPeriod.EVENING)
        val nextDayMorning = DaylistSlot(LocalDate.of(2026, 11, 27), DaylistPeriod.MORNING)

        val morningA = DaylistGenerator.generate(library, morningSlot)
        val morningB = DaylistGenerator.generate(library.reversed(), morningSlot)
        val evening = DaylistGenerator.generate(library, eveningSlot)
        val nextMorning = DaylistGenerator.generate(library, nextDayMorning)

        assertEquals(50, morningA.tracks.size)
        assertEquals(morningA.tracks.map { it.id }, morningB.tracks.map { it.id })
        assertNotEquals(morningA.tracks.map { it.id }, evening.tracks.map { it.id })
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
