package com.musicplayer.player.engine

import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.Track
import com.musicplayer.player.PlaybackState
import com.musicplayer.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlResolverTest {

    private fun sampleTrack(
        isDownloaded: Boolean = false,
        localUri: String? = null,
    ) = Track(
        id = "track-123",
        title = "Time",
        artistId = "artist-1",
        artistName = "Pink Floyd",
        albumId = "album-1",
        albumName = "The Dark Side of the Moon",
        trackNumber = 4,
        discNumber = 1,
        durationMs = 413_000L,
        container = "flac",
        codec = "flac",
        bitrate = 2_000_000,
        sampleRate = 96_000,
        bitDepth = 24,
        isFavorite = true,
        playCount = 3,
        lastPlayedAt = null,
        hasLyrics = false,
        artworkUrl = null,
        isDownloaded = isDownloaded,
        localUri = localUri,
    )

    @Test
    fun prefersLocalMediaStoreUriEvenOnMeteredNetworkWithCap() {
        val resolved = StreamUrlResolver.resolveForContext(
            track = sampleTrack(isDownloaded = true, localUri = "content://media/external/audio/media/42"),
            baseUrl = "https://music.example.com",
            isMetered = true,
            cellularBitrateCap = CellularBitrateCap.KBPS_128,
        )

        assertEquals(
            ResolvedAudioSource("content://media/external/audio/media/42", isLocal = true),
            resolved,
        )
    }

    @Test
    fun alwaysStreamsLosslessDirectPlayOnWifiEvenWhenCellularCapConfigured() {
        val resolved = StreamUrlResolver.resolveForContext(
            track = sampleTrack(),
            baseUrl = "http://192.168.1.20:8096/",
            isMetered = false,
            cellularBitrateCap = CellularBitrateCap.KBPS_128,
        )

        assertEquals(
            ResolvedAudioSource(
                uri = "http://192.168.1.20:8096/Audio/track-123/stream?static=true",
                isLocal = false,
                transcodedBitrateKbps = null,
            ),
            resolved,
        )
        assertFalse(resolved!!.uri.contains("api_key"))
    }

    @Test
    fun streamsLosslessOnMobileDataByDefaultWhenCapIsOriginal() {
        val resolved = StreamUrlResolver.resolveForContext(
            track = sampleTrack(),
            baseUrl = "https://music.example.com",
            isMetered = true,
            cellularBitrateCap = CellularBitrateCap.ORIGINAL,
        )

        assertEquals(
            ResolvedAudioSource(
                uri = "https://music.example.com/Audio/track-123/stream?static=true",
                isLocal = false,
                transcodedBitrateKbps = null,
            ),
            resolved,
        )
    }

    @Test
    fun appliesBitrateCapOnMobileDataWithoutQueryToken() {
        val resolved = StreamUrlResolver.resolveForContext(
            track = sampleTrack(),
            baseUrl = "https://music.example.com",
            isMetered = true,
            cellularBitrateCap = CellularBitrateCap.KBPS_192,
        )

        assertEquals(192, resolved?.transcodedBitrateKbps)
        assertFalse(resolved!!.isLocal)
        assertTrue(resolved.uri.contains("/Audio/track-123/universal?"))
        assertTrue(resolved.uri.contains("maxStreamingBitrate=192000"))
        assertFalse(resolved.uri.contains("api_key"))
    }

    @Test
    fun returnsNullWhenOfflineAndNotDownloaded() {
        assertNull(StreamUrlResolver.resolveForContext(sampleTrack(), baseUrl = null))
    }

    @Test
    fun repeatModeCyclesAndProgressFractionClamps() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())

        val state = PlaybackState(positionMs = 50_000L, durationMs = 200_000L)
        assertEquals(0.25f, state.progressFraction, 0.001f)
        assertTrue(PlaybackState(positionMs = 300_000L, durationMs = 200_000L).progressFraction == 1f)
    }
}
