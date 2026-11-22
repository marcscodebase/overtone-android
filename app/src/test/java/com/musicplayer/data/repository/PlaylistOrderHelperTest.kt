package com.musicplayer.data.repository

import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderHelperTest {

    private fun ref(itemId: String, position: Int) = PlaylistTrackCrossRef(
        playlistId = "pl-1",
        trackId = "track-$itemId",
        playlistItemId = itemId,
        position = position,
    )

    @Test
    fun reorderMovesItemAndReindexesPositions() {
        val initial = listOf(ref("a", 0), ref("b", 1), ref("c", 2), ref("d", 3))
        val reordered = PlaylistOrderHelper.reorder(initial, fromIndex = 3, toIndex = 1)

        assertEquals(listOf("a", "d", "b", "c"), reordered.map { it.playlistItemId })
        assertEquals(listOf(0, 1, 2, 3), reordered.map { it.position })
    }

    @Test
    fun removeByItemIdRemovesOnlyTargetEntryAndReindexes() {
        val initial = listOf(ref("a", 0), ref("b", 1), ref("c", 2))
        val updated = PlaylistOrderHelper.removeByItemId(initial, "b")

        assertEquals(listOf("a", "c"), updated.map { it.playlistItemId })
        assertEquals(listOf(0, 1), updated.map { it.position })
    }
}
