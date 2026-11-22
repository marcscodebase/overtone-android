package com.musicplayer.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun formatsMinutesAndHoursCorrectly() {
        assertEquals("0:00", Formatters.formatDuration(0L))
        assertEquals("0:05", Formatters.formatDuration(5_000L))
        assertEquals("3:42", Formatters.formatDuration(222_000L))
        assertEquals("1:05:09", Formatters.formatDuration(3_909_000L))
    }

    @Test
    fun formatsHiResLosslessBadges() {
        assertEquals(
            "FLAC • 24-bit/96 kHz",
            Formatters.formatAudioQualityBadge(
                container = "flac",
                codec = "flac",
                bitrate = 2_800_000,
                sampleRate = 96_000,
                bitDepth = 24,
                isLocal = false,
            ),
        )
        assertEquals(
            "FLAC • 16-bit/44.1 kHz • Local",
            Formatters.formatAudioQualityBadge(
                container = "flac",
                codec = "flac",
                bitrate = 1_000_000,
                sampleRate = 44_100,
                bitDepth = 16,
                isLocal = true,
            ),
        )
    }

    @Test
    fun formatsLosslessFallbackWithoutBitDepthAndLossyMp3() {
        assertEquals(
            "ALAC • Lossless",
            Formatters.formatAudioQualityBadge(
                container = "m4a",
                codec = "alac",
                bitrate = 900_000,
                sampleRate = null,
                bitDepth = null,
            ),
        )
        assertEquals(
            "MP3 • 320 kbps • Local",
            Formatters.formatAudioQualityBadge(
                container = "mp3",
                codec = "mp3",
                bitrate = 320_000,
                sampleRate = 44_100,
                bitDepth = null,
                isLocal = true,
            ),
        )
    }
}
