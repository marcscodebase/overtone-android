package com.musicplayer.feature.library.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMembershipDiffTest {

    @Test
    fun `calculates added and removed playlists accurately`() {
        val diff = PlaylistMembershipDiff.calculate(
            initial = setOf("p1", "p2"),
            target = setOf("p2", "p3", "p4"),
        )

        assertEquals(setOf("p3", "p4"), diff.playlistIdsToAdd)
        assertEquals(setOf("p1"), diff.playlistIdsToRemove)
    }

    @Test
    fun `returns empty diff when selection unchanged`() {
        val diff = PlaylistMembershipDiff.calculate(
            initial = setOf("p1"),
            target = setOf("p1"),
        )

        assertTrue(diff.playlistIdsToAdd.isEmpty())
        assertTrue(diff.playlistIdsToRemove.isEmpty())
    }
}
