package com.musicplayer.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStorePathBuilderTest {

    @Test
    fun buildsCleanLosslessPathWithPaddedTrackNumber() {
        val target = MediaStorePathBuilder.build(
            artistName = "Pink Floyd",
            albumName = "The Dark Side of the Moon",
            title = "Time",
            trackNumber = 4,
            discNumber = 1,
            extension = "FLAC",
        )

        assertEquals("Music/Overtone/Pink Floyd/The Dark Side of the Moon/", target.relativePath)
        assertEquals("04 - Time.flac", target.displayName)
        assertEquals("audio/flac", target.mimeType)
    }

    @Test
    fun includesDiscPrefixAndSanitizesForbiddenCharacters() {
        val target = MediaStorePathBuilder.build(
            artistName = "AC/DC",
            albumName = "Live: 1991?",
            title = "Back/In*Black",
            trackNumber = 3,
            discNumber = 2,
            extension = ".mp3",
        )

        assertEquals("Music/Overtone/AC_DC/Live_ 1991_/", target.relativePath)
        assertEquals("2-03 - Back_In_Black.mp3", target.displayName)
        assertEquals("audio/mpeg", target.mimeType)
    }

    @Test
    fun omitsTrackPrefixWhenTrackNumberMissing() {
        val target = MediaStorePathBuilder.build(
            artistName = "",
            albumName = "...",
            title = "Single Track",
            trackNumber = null,
            discNumber = null,
            extension = "mp3",
        )

        assertEquals("Music/Overtone/Unknown Artist/Unknown Album/", target.relativePath)
        assertEquals("Single Track.mp3", target.displayName)
    }
}
