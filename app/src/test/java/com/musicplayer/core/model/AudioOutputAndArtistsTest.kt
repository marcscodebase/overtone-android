package com.musicplayer.core.model

import com.musicplayer.player.engine.StreamUrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputAndArtistsTest {

    @Test
    fun encodesAndDecodesMultipleTrackArtists() {
        val artists = listOf(
            TrackArtist(id = "a1", name = "Daft Punk"),
            TrackArtist(id = "a2", name = "Pharrell Williams"),
        )
        val encoded = TrackArtistsCodec.encode(artists)
        assertEquals(artists, TrackArtistsCodec.decode(encoded))
    }

    @Test
    fun trackResolvedArtistsFallsBackToSingleArtistId() {
        val legacyTrack = Track(
            id = "t1",
            title = "Around the World",
            artistId = "a1",
            artistName = "Daft Punk",
            artists = emptyList(),
            albumId = "alb1",
            albumName = "Homework",
            trackNumber = 1,
            discNumber = 1,
            durationMs = 240_000L,
            container = "flac",
            codec = "flac",
            bitrate = 950_000,
            sampleRate = 44100,
            bitDepth = 16,
            isFavorite = false,
            playCount = 0,
            lastPlayedAt = null,
            hasLyrics = false,
            artworkUrl = null,
        )
        assertEquals(listOf(TrackArtist("a1", "Daft Punk")), legacyTrack.resolvedArtists)
    }

    @Test
    fun evaluatesOutputCapabilitiesAndAutoCap() {
        val usbDac = AudioOutputDevice(
            id = 1,
            name = "FiiO KA13",
            type = AudioOutputType.USB_AUDIO,
            maxSampleRateHz = 192_000,
            supportsHiResPcm = true,
            isActive = true,
        )
        val btHeadphones = AudioOutputDevice(
            id = 2,
            name = "WH-1000XM5",
            type = AudioOutputType.BLUETOOTH_A2DP,
            isActive = true,
        )

        val usbCaps = AudioOutputQualityPolicy.evaluate(usbDac)
        assertTrue(usbCaps.isLosslessCapable)
        assertNull(usbCaps.maxBitrateKbps)
        assertEquals("Lossless (24-bit/192 kHz)", usbCaps.maxQualityLabel)

        val standardBtCaps = AudioOutputQualityPolicy.evaluate(btHeadphones)
        assertFalse(standardBtCaps.isLosslessCapable)
        assertEquals(320, standardBtCaps.maxBitrateKbps)
        assertEquals(
            320,
            AudioOutputQualityPolicy.resolveAutoBitrateCapKbps(
                autoMatchEnabled = true,
                activeDevice = btHeadphones,
            ),
        )
        // Default disabled returns null (no cap)
        assertNull(
            AudioOutputQualityPolicy.resolveAutoBitrateCapKbps(
                autoMatchEnabled = false,
                activeDevice = btHeadphones,
            ),
        )

        // Marking BT headphones as Hi-Res / LDAC unlocks Lossless even with auto-match enabled
        val hiResCaps = AudioOutputQualityPolicy.evaluate(
            btHeadphones,
            hiResBluetoothDevices = setOf(btHeadphones.preferenceKey),
        )
        assertTrue(hiResCaps.isLosslessCapable)
        assertNull(hiResCaps.maxBitrateKbps)
    }

    @Test
    fun streamUrlResolverRespectsOutputDeviceCapAndManualOverrides() {
        val track = Track(
            id = "track-1",
            title = "Song",
            artistId = "a1",
            artistName = "Artist",
            albumId = "alb1",
            albumName = "Album",
            trackNumber = 1,
            discNumber = 1,
            durationMs = 200_000L,
            container = "flac",
            codec = "flac",
            bitrate = 1_000_000,
            sampleRate = 44100,
            bitDepth = 16,
            isFavorite = false,
            playCount = 0,
            lastPlayedAt = null,
            hasLyrics = false,
            artworkUrl = null,
        )

        val autoBt = StreamUrlResolver.resolveForContext(
            track = track,
            baseUrl = "https://jellyfin.local",
            outputDeviceMaxBitrateKbps = 320,
            qualitySelection = StreamQualitySelection.AUTO,
        )
        assertEquals(320, autoBt?.transcodedBitrateKbps)

        // Tighter cellular cap wins over 320 kbps Bluetooth cap
        val cellularTighter = StreamUrlResolver.resolveForContext(
            track = track,
            baseUrl = "https://jellyfin.local",
            isMetered = true,
            cellularBitrateCap = CellularBitrateCap.KBPS_192,
            outputDeviceMaxBitrateKbps = 320,
            qualitySelection = StreamQualitySelection.AUTO,
        )
        assertEquals(192, cellularTighter?.transcodedBitrateKbps)

        // Manual High Quality override bypasses output cap
        val manualHigh = StreamUrlResolver.resolveForContext(
            track = track,
            baseUrl = "https://jellyfin.local",
            outputDeviceMaxBitrateKbps = 320,
            qualitySelection = StreamQualitySelection.HIGH,
        )
        assertNull(manualHigh?.transcodedBitrateKbps)
    }
}
