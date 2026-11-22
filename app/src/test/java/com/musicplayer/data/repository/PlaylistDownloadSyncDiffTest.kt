package com.musicplayer.data.repository

import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.model.DownloadOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDownloadSyncDiffTest {

    @Test
    fun `diff queues added or missing tracks and unrefs removed tracks`() {
        val diff = PlaylistDownloadDiffCalculator.calculate(
            remoteTrackIds = setOf("t1", "t2", "t3", "t4"),
            existingPlaylistRefTrackIds = setOf("t1", "t2", "t5"),
            // t1 is already completed; t2 was ref'd earlier but failed/not completed
            completedTrackIds = setOf("t1", "t5"),
        )

        assertEquals(setOf("t2", "t3", "t4"), diff.trackIdsToDownload)
        assertEquals(setOf("t5"), diff.trackIdsToUnref)
    }

    @Test
    fun `diff is empty when remote matches existing completed refs`() {
        val diff = PlaylistDownloadDiffCalculator.calculate(
            remoteTrackIds = setOf("t1", "t2"),
            existingPlaylistRefTrackIds = setOf("t1", "t2"),
            completedTrackIds = setOf("t1", "t2"),
        )

        assertTrue(diff.trackIdsToDownload.isEmpty())
        assertTrue(diff.trackIdsToUnref.isEmpty())
    }

    @Test
    fun `pruning removed playlist tracks preserves tracks shared with album or other playlist`() {
        val allRefsBeforeSync = listOf(
            // t1 stays in playlist P1
            DownloadRefEntity("t1", DownloadOwnerType.PLAYLIST, "P1"),
            // t2 removed from P1, NOT referenced anywhere else -> should be pruned
            DownloadRefEntity("t2", DownloadOwnerType.PLAYLIST, "P1"),
            // t3 removed from P1, STILL referenced by Album A1 -> must be preserved
            DownloadRefEntity("t3", DownloadOwnerType.PLAYLIST, "P1"),
            DownloadRefEntity("t3", DownloadOwnerType.ALBUM, "A1"),
        )

        val diff = PlaylistDownloadDiffCalculator.calculate(
            remoteTrackIds = setOf("t1"),
            existingPlaylistRefTrackIds = setOf("t1", "t2", "t3"),
            completedTrackIds = setOf("t1", "t2", "t3"),
        )
        assertEquals(setOf("t2", "t3"), diff.trackIdsToUnref)

        val remainingRefsAfterUnref = allRefsBeforeSync.filterNot {
            it.ownerType == DownloadOwnerType.PLAYLIST &&
                it.ownerId == "P1" &&
                it.trackId in diff.trackIdsToUnref
        }
        val stillReferencedTracks = remainingRefsAfterUnref.map { it.trackId }.toSet()
        val orphanedToDelete = diff.trackIdsToUnref - stillReferencedTracks

        assertEquals(setOf("t2"), orphanedToDelete)
    }
}
