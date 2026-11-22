package com.musicplayer.player.engine

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
    fun prefersLocalMediaStoreUriWhenTrackIsDownloaded() {
        val resolved = StreamUrlResolver.resolveForBaseUrl(
            track = sampleTrack(isDownloaded = true, localUri = "content://media/external/audio/media/42"),
            baseUrl = "https://music.example.com",
        )

        assertEquals(
            ResolvedAudioSource("content://media/external/audio/media/42", isLocal = true),
            resolved,
        )
    }

    @Test
    fun resolvesLosslessDirectPlayUrlWithoutQueryToken() {
        val resolved = StreamUrlResolver.resolveForBaseUrl(
            track = sampleTrack(),
            baseUrl = "http://192.168.1.20:8096/",
        )

        assertEquals(
            ResolvedAudioSource("http://192.168.1.20:8096/Audio/track-123/stream?static=true", isLocal = false),
            resolved,
        )
        assertFalse(resolved!!.uri.contains("api_key"))
    }

    @Test
    fun returnsNullWhenOfflineAndNotDownloaded() {
        assertNull(StreamUrlResolver.resolveForBaseUrl(sampleTrack(), baseUrl = null))
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
