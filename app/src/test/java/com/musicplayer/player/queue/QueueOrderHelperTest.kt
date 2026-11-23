package com.musicplayer.player.queue

import com.musicplayer.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOrderHelperTest {

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artistId = null,
        artistName = "Artist",
        albumId = null,
        albumName = "Album",
        trackNumber = null,
        discNumber = null,
        durationMs = 180_000L,
        container = "flac",
        codec = "flac",
        bitrate = null,
        sampleRate = 44_100,
        bitDepth = 16,
        isFavorite = false,
        playCount = 0,
        lastPlayedAt = null,
        hasLyrics = false,
        artworkUrl = null,
    )

    @Test
    fun insertNextPlacesTrackImmediatelyAfterCurrentIndex() {
        val initial = listOf(track("1"), track("2"), track("3"))
        val result = QueueOrderHelper.insertNext(initial, currentIndex = 0, track = track("next"))

        assertEquals(listOf("1", "next", "2", "3"), result.queue.map { it.id })
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun appendAddsTracksToEndWithoutShiftingCurrentIndex() {
        val initial = listOf(track("1"), track("2"))
        val result = QueueOrderHelper.append(initial, currentIndex = 1, tracks = listOf(track("3"), track("4")))

        assertEquals(listOf("1", "2", "3", "4"), result.queue.map { it.id })
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun removeAtAdjustsCurrentIndexWhenRemovingBeforeCurrent() {
        val initial = listOf(track("1"), track("2"), track("3"))
        val result = QueueOrderHelper.removeAt(initial, currentIndex = 2, removeIndex = 0)

        assertEquals(listOf("2", "3"), result.queue.map { it.id })
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun moveUpdatesCurrentIndexAccurately() {
        val initial = listOf(track("1"), track("2"), track("3"), track("4"))

        val movedActive = QueueOrderHelper.move(initial, currentIndex = 1, fromIndex = 1, toIndex = 3)
        assertEquals(listOf("1", "3", "4", "2"), movedActive.queue.map { it.id })
        assertEquals(3, movedActive.currentIndex)

        val movedAroundActive = QueueOrderHelper.move(initial, currentIndex = 1, fromIndex = 3, toIndex = 0)
        assertEquals(listOf("4", "1", "2", "3"), movedAroundActive.queue.map { it.id })
        assertEquals(2, movedAroundActive.currentIndex)
    }

    @Test
    fun spotifyStyleManualQueuePrioritizesAddedTracksBeforeContext() {
        val albumQueue = listOf(track("a1"), track("a2"), track("a3"))
        // Playing a1 (index 0), user swipes right on q1 then q2
        val afterQ1 = QueueOrderHelper.enqueueManual(albumQueue, currentIndex = 0, manualQueueCount = 0, tracks = listOf(track("q1")))
        assertEquals(listOf("a1", "q1", "a2", "a3"), afterQ1.queue.map { it.id })
        assertEquals(1, afterQ1.manualQueueCount)

        val afterQ2 = QueueOrderHelper.enqueueManual(afterQ1.queue, afterQ1.currentIndex, afterQ1.manualQueueCount, listOf(track("q2")))
        assertEquals(listOf("a1", "q1", "q2", "a2", "a3"), afterQ2.queue.map { it.id })
        assertEquals(2, afterQ2.manualQueueCount)

        // Play Next puts p1 at the very top of Next in Queue
        val afterPlayNext = QueueOrderHelper.insertNext(afterQ2.queue, afterQ2.currentIndex, track("p1"), afterQ2.manualQueueCount)
        assertEquals(listOf("a1", "p1", "q1", "q2", "a2", "a3"), afterPlayNext.queue.map { it.id })
        assertEquals(3, afterPlayNext.manualQueueCount)

        // Clear manual queue removes only p1/q1/q2 while keeping album context a2/a3
        val cleared = QueueOrderHelper.clearManualQueue(afterPlayNext.queue, afterPlayNext.currentIndex, afterPlayNext.manualQueueCount)
        assertEquals(listOf("a1", "a2", "a3"), cleared.queue.map { it.id })
        assertEquals(0, cleared.manualQueueCount)
    }

    @Test
    fun movingBetweenManualAndContextSectionsUpdatesManualCount() {
        val queue = listOf(track("now"), track("m1"), track("m2"), track("c1"), track("c2"))
        // Move context track c1 (index 3) up into manual section (index 1)
        val promoted = QueueOrderHelper.move(queue, currentIndex = 0, fromIndex = 3, toIndex = 1, manualQueueCount = 2)
        assertEquals(listOf("now", "c1", "m1", "m2", "c2"), promoted.queue.map { it.id })
        assertEquals(3, promoted.manualQueueCount)

        // Move manual track m2 (index 3) down into context section (index 4)
        val demoted = QueueOrderHelper.move(promoted.queue, currentIndex = 0, fromIndex = 3, toIndex = 4, manualQueueCount = 3)
        assertEquals(listOf("now", "c1", "m1", "c2", "m2"), demoted.queue.map { it.id })
        assertEquals(2, demoted.manualQueueCount)
    }
}
