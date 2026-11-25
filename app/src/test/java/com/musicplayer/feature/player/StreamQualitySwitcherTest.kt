package com.musicplayer.feature.player

import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.player.engine.StreamUrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualitySwitcherTest {

    @Test
    fun `online menu shows auto plus all concrete streaming options`() {
        val menu = QualitySwitcherMenuBuilder.build(
            currentSelection = StreamQualitySelection.AUTO,
            currentActiveBadgeLabel = "MP3 • 320 kbps",
            isTrackDownloaded = false,
            isOffline = false,
            highQualitySubtitle = "FLAC • 16-bit/44.1 kHz",
            localQualitySubtitle = "FLAC • Local",
        )

        assertNotNull(menu.autoOption)
        assertTrue(menu.autoOption!!.isSelected)
        assertEquals("Active: MP3 • 320 kbps", menu.autoOption.subtitle)
        assertEquals(
            listOf(
                StreamQualitySelection.HIGH,
                StreamQualitySelection.MP3_320,
                StreamQualitySelection.MP3_256,
                StreamQualitySelection.MP3_192,
                StreamQualitySelection.MP3_128,
            ),
            menu.options.map { it.selection },
        )
        assertTrue(menu.options.none { it.isSelected })
    }

    @Test
    fun `downloaded track includes local option alongside auto and all bitrates`() {
        val menu = QualitySwitcherMenuBuilder.build(
            currentSelection = StreamQualitySelection.MP3_192,
            currentActiveBadgeLabel = "MP3 • 192 kbps",
            isTrackDownloaded = true,
            isOffline = false,
            highQualitySubtitle = "FLAC • Lossless",
            localQualitySubtitle = "FLAC • Lossless • Local",
        )

        assertFalse(menu.autoOption!!.isSelected)
        assertEquals(
            listOf(
                StreamQualitySelection.LOCAL,
                StreamQualitySelection.HIGH,
                StreamQualitySelection.MP3_320,
                StreamQualitySelection.MP3_256,
                StreamQualitySelection.MP3_192,
                StreamQualitySelection.MP3_128,
            ),
            menu.options.map { it.selection },
        )
        assertTrue(menu.options.first { it.selection == StreamQualitySelection.MP3_192 }.isSelected)
    }

    @Test
    fun `offline local playback hides auto and remote streaming options`() {
        val menu = QualitySwitcherMenuBuilder.build(
            currentSelection = StreamQualitySelection.AUTO,
            currentActiveBadgeLabel = "FLAC • Local",
            isTrackDownloaded = true,
            isOffline = true,
            highQualitySubtitle = "FLAC • Lossless",
            localQualitySubtitle = "FLAC • Local",
        )

        assertNull(menu.autoOption)
        assertEquals(listOf(StreamQualitySelection.LOCAL), menu.options.map { it.selection })
        assertTrue(menu.options.single().isSelected)
    }

    @Test
    fun `stream url resolver honors explicit lossless, all mp3 bitrates, local and auto`() {
        val downloadedTrack = Track(
            id = "t1",
            title = "Song",
            artistId = null,
            artistName = "Artist",
            albumId = null,
            albumName = "Album",
            trackNumber = 1,
            discNumber = 1,
            durationMs = 200_000L,
            container = "flac",
            codec = "flac",
            bitrate = 950_000,
            sampleRate = 44_100,
            bitDepth = 16,
            isFavorite = false,
            playCount = 0,
            lastPlayedAt = null,
            hasLyrics = false,
            artworkUrl = null,
            isDownloaded = true,
            localUri = "content://media/external/audio/media/99",
        )

        val forcedHigh = StreamUrlResolver.resolveForContext(
            track = downloadedTrack,
            baseUrl = "https://jellyfin.local",
            qualitySelection = StreamQualitySelection.HIGH,
        )
        assertFalse(forcedHigh!!.isLocal)
        assertNull(forcedHigh.transcodedBitrateKbps)
        assertTrue(forcedHigh.uri.endsWith("/Audio/t1/stream?static=true"))

        for ((selection, expectedKbps) in listOf(
            StreamQualitySelection.MP3_320 to 320,
            StreamQualitySelection.MP3_256 to 256,
            StreamQualitySelection.MP3_192 to 192,
            StreamQualitySelection.MP3_128 to 128,
        )) {
            val resolved = StreamUrlResolver.resolveForContext(
                track = downloadedTrack,
                baseUrl = "https://jellyfin.local",
                qualitySelection = selection,
            )
            assertFalse(resolved!!.isLocal)
            assertEquals(expectedKbps, resolved.transcodedBitrateKbps)
            assertTrue(resolved.uri.contains("maxStreamingBitrate=${expectedKbps * 1000}"))
        }

        val forcedLocal = StreamUrlResolver.resolveForContext(
            track = downloadedTrack,
            baseUrl = "https://jellyfin.local",
            qualitySelection = StreamQualitySelection.LOCAL,
        )
        assertTrue(forcedLocal!!.isLocal)
        assertEquals("content://media/external/audio/media/99", forcedLocal.uri)

        // Streaming-only track in AUTO mode respects both cellular cap and output device cap
        val remoteOnly = downloadedTrack.copy(isDownloaded = false, localUri = null)
        val autoCellular = StreamUrlResolver.resolveForContext(
            track = remoteOnly,
            baseUrl = "https://jellyfin.local",
            isMetered = true,
            cellularBitrateCap = CellularBitrateCap.KBPS_256,
            outputDeviceMaxBitrateKbps = 320,
            qualitySelection = StreamQualitySelection.AUTO,
        )
        assertEquals(256, autoCellular?.transcodedBitrateKbps)
    }
}
