package com.musicplayer.feature.player

import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.player.engine.StreamUrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualitySwitcherTest {

    @Test
    fun `streaming high without download shows only low quality alternative`() {
        val menu = QualitySwitcherMenuBuilder.build(
            isLocalPlayback = false,
            transcodedBitrateKbps = null,
            isTrackDownloaded = false,
            isOffline = false,
            highQualitySubtitle = "FLAC • 16-bit/44.1 kHz",
            lowQualityBitrateKbps = 128,
            localQualitySubtitle = "FLAC • Local",
        )

        assertEquals(StreamQualitySelection.HIGH, menu.active.selection)
        assertEquals(listOf(StreamQualitySelection.LOW), menu.alternatives.map { it.selection })
        assertEquals("MP3 • 128 kbps", menu.alternatives.first().subtitle)
    }

    @Test
    fun `streaming low without download shows only high quality alternative`() {
        val menu = QualitySwitcherMenuBuilder.build(
            isLocalPlayback = false,
            transcodedBitrateKbps = 128,
            isTrackDownloaded = false,
            isOffline = false,
            highQualitySubtitle = "FLAC • 16-bit/44.1 kHz",
            lowQualityBitrateKbps = 128,
            localQualitySubtitle = "FLAC • Local",
        )

        assertEquals(StreamQualitySelection.LOW, menu.active.selection)
        assertEquals(listOf(StreamQualitySelection.HIGH), menu.alternatives.map { it.selection })
    }

    @Test
    fun `downloaded track always adds local option alongside high and low`() {
        val whenHigh = QualitySwitcherMenuBuilder.build(
            isLocalPlayback = false,
            transcodedBitrateKbps = null,
            isTrackDownloaded = true,
            isOffline = false,
            highQualitySubtitle = "FLAC • Lossless",
            lowQualityBitrateKbps = 192,
            localQualitySubtitle = "MP3 • 320 kbps • Local",
        )
        assertEquals(
            listOf(StreamQualitySelection.LOW, StreamQualitySelection.LOCAL),
            whenHigh.alternatives.map { it.selection },
        )

        val whenLocal = QualitySwitcherMenuBuilder.build(
            isLocalPlayback = true,
            transcodedBitrateKbps = 320,
            isTrackDownloaded = true,
            isOffline = false,
            highQualitySubtitle = "FLAC • Lossless",
            lowQualityBitrateKbps = 192,
            localQualitySubtitle = "MP3 • 320 kbps • Local",
        )
        assertEquals(StreamQualitySelection.LOCAL, whenLocal.active.selection)
        assertEquals(
            listOf(StreamQualitySelection.HIGH, StreamQualitySelection.LOW),
            whenLocal.alternatives.map { it.selection },
        )
    }

    @Test
    fun `offline local playback hides remote streaming alternatives`() {
        val menu = QualitySwitcherMenuBuilder.build(
            isLocalPlayback = true,
            transcodedBitrateKbps = null,
            isTrackDownloaded = true,
            isOffline = true,
            highQualitySubtitle = "FLAC • Lossless",
            lowQualityBitrateKbps = 128,
            localQualitySubtitle = "FLAC • Local",
        )

        assertEquals(StreamQualitySelection.LOCAL, menu.active.selection)
        assertTrue(menu.alternatives.isEmpty())
    }

    @Test
    fun `stream url resolver honors explicit high low and local selections`() {
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
        assertEquals(null, forcedHigh.transcodedBitrateKbps)
        assertTrue(forcedHigh.uri.endsWith("/Audio/t1/stream?static=true"))

        val forcedLow = StreamUrlResolver.resolveForContext(
            track = downloadedTrack,
            baseUrl = "https://jellyfin.local",
            lowQualityBitrate = Mp3Bitrate.KBPS_128,
            qualitySelection = StreamQualitySelection.LOW,
        )
        assertFalse(forcedLow!!.isLocal)
        assertEquals(128, forcedLow.transcodedBitrateKbps)
        assertTrue(forcedLow.uri.contains("maxStreamingBitrate=128000"))

        val forcedLocal = StreamUrlResolver.resolveForContext(
            track = downloadedTrack,
            baseUrl = "https://jellyfin.local",
            qualitySelection = StreamQualitySelection.LOCAL,
        )
        assertTrue(forcedLocal!!.isLocal)
        assertEquals("content://media/external/audio/media/99", forcedLocal.uri)
    }
}
