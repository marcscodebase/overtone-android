package com.musicplayer.core.network

import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.Track
import com.musicplayer.player.engine.StreamUrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModePolicyTest {

    @Test
    fun `offline status reflects manual toggle and network loss`() {
        assertFalse(OfflineStatus(manualOfflineMode = false, isNetworkConnected = true).isOffline)
        assertTrue(OfflineStatus(manualOfflineMode = true, isNetworkConnected = true).isOffline)
        assertTrue(OfflineStatus(manualOfflineMode = false, isNetworkConnected = false).isOffline)
        assertTrue(OfflineStatus(manualOfflineMode = true, isNetworkConnected = false).isOffline)
    }

    @Test
    fun `stream url resolver blocks remote track when offline`() {
        val remoteTrack = sampleTrack(isDownloaded = false, localUri = null)

        val resolvedOffline = StreamUrlResolver.resolveForContext(
            track = remoteTrack,
            baseUrl = "https://jellyfin.local",
            isMetered = false,
            cellularBitrateCap = CellularBitrateCap.ORIGINAL,
            isOffline = true,
        )

        assertNull(resolvedOffline)
    }

    @Test
    fun `stream url resolver plays downloaded track locally even when offline`() {
        val localTrack = sampleTrack(
            isDownloaded = true,
            localUri = "content://media/external/audio/media/42",
        )

        val resolved = StreamUrlResolver.resolveForContext(
            track = localTrack,
            baseUrl = null,
            isOffline = true,
        )

        assertNotNull(resolved)
        assertTrue(resolved!!.isLocal)
        assertEquals("content://media/external/audio/media/42", resolved.uri)
    }

    private fun sampleTrack(isDownloaded: Boolean, localUri: String?) = Track(
        id = "t1",
        title = "Airbag",
        artistId = "ar1",
        artistName = "Radiohead",
        albumId = "al1",
        albumName = "OK Computer",
        trackNumber = 1,
        discNumber = 1,
        durationMs = 284_000L,
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
        isDownloaded = isDownloaded,
        localUri = localUri,
    )
}
