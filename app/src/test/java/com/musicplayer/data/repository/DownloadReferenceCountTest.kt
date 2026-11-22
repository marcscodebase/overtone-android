package com.musicplayer.data.repository

import com.musicplayer.core.common.Formatters
import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.model.DownloadOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadReferenceCountTest {

    @Test
    fun `deleting album preserves tracks shared with playlist or single track download`() {
        val refs = listOf(
            // Track 1: only in album A -> should be orphaned when album A removed
            DownloadRefEntity("t1", DownloadOwnerType.ALBUM, "albumA"),
            // Track 2: in album A AND playlist P -> must be preserved
            DownloadRefEntity("t2", DownloadOwnerType.ALBUM, "albumA"),
            DownloadRefEntity("t2", DownloadOwnerType.PLAYLIST, "playlistP"),
            // Track 3: in album A AND single-track download -> must be preserved
            DownloadRefEntity("t3", DownloadOwnerType.ALBUM, "albumA"),
            DownloadRefEntity("t3", DownloadOwnerType.TRACK, "t3"),
            // Track 4: only in playlist P -> unaffected
            DownloadRefEntity("t4", DownloadOwnerType.PLAYLIST, "playlistP"),
        )

        val orphans = DownloadRefCleanupPolicy.findOrphanedTrackIdsAfterRemovingOwner(
            allRefs = refs,
            ownerType = DownloadOwnerType.ALBUM,
            ownerId = "albumA",
        )

        assertEquals(setOf("t1"), orphans)
    }

    @Test
    fun `deleting final remaining owner orphans shared track`() {
        val refsAfterAlbumRemoved = listOf(
            DownloadRefEntity("t2", DownloadOwnerType.PLAYLIST, "playlistP"),
            DownloadRefEntity("t4", DownloadOwnerType.PLAYLIST, "playlistP"),
        )

        val orphans = DownloadRefCleanupPolicy.findOrphanedTrackIdsAfterRemovingOwner(
            allRefs = refsAfterAlbumRemoved,
            ownerType = DownloadOwnerType.PLAYLIST,
            ownerId = "playlistP",
        )

        assertEquals(setOf("t2", "t4"), orphans)
    }

    @Test
    fun `removing single track ref keeps file if playlist still references it`() {
        val refs = listOf(
            DownloadRefEntity("t1", DownloadOwnerType.TRACK, "t1"),
            DownloadRefEntity("t1", DownloadOwnerType.PLAYLIST, "p1"),
        )

        assertFalse(
            DownloadRefCleanupPolicy.isTrackOrphanedAfterRemovingRef(
                allRefs = refs,
                trackId = "t1",
                ownerType = DownloadOwnerType.TRACK,
                ownerId = "t1",
            ),
        )
    }

    @Test
    fun `removing last single track ref marks track orphaned`() {
        val refs = listOf(
            DownloadRefEntity("t1", DownloadOwnerType.TRACK, "t1"),
        )

        assertTrue(
            DownloadRefCleanupPolicy.isTrackOrphanedAfterRemovingRef(
                allRefs = refs,
                trackId = "t1",
                ownerType = DownloadOwnerType.TRACK,
                ownerId = "t1",
            ),
        )
    }

    @Test
    fun `formatBytes formats bytes kilobytes megabytes and gigabytes`() {
        assertEquals("512 B", Formatters.formatBytes(512L))
        assertEquals("10 KB", Formatters.formatBytes(10 * 1024L))
        assertEquals("12.5 MB", Formatters.formatBytes((12.5 * 1024 * 1024).toLong()))
        assertEquals("1.50 GB", Formatters.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
