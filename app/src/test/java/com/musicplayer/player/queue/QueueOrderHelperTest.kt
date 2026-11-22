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
}
