package com.musicplayer.player.queue

import com.musicplayer.core.model.Track

data class QueueMutationResult(
    val queue: List<Track>,
    val currentIndex: Int,
)

/**
 * Pure queue ordering operations shared between UI previews, controller state, and unit tests.
 */
object QueueOrderHelper {

    fun insertNext(
        queue: List<Track>,
        currentIndex: Int,
        track: Track,
    ): QueueMutationResult {
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            return QueueMutationResult(queue = listOf(track), currentIndex = 0)
        }
        val insertIndex = (currentIndex + 1).coerceAtMost(queue.size)
        val updated = queue.toMutableList().apply { add(insertIndex, track) }
        return QueueMutationResult(queue = updated, currentIndex = currentIndex)
    }

    fun append(
        queue: List<Track>,
        currentIndex: Int,
        tracks: List<Track>,
    ): QueueMutationResult {
        if (tracks.isEmpty()) return QueueMutationResult(queue, currentIndex)
        if (queue.isEmpty()) {
            return QueueMutationResult(queue = tracks, currentIndex = 0)
        }
        return QueueMutationResult(
            queue = queue + tracks,
            currentIndex = currentIndex.coerceIn(0, (queue.size + tracks.size - 1)),
        )
    }

    fun removeAt(
        queue: List<Track>,
        currentIndex: Int,
        removeIndex: Int,
    ): QueueMutationResult {
        if (removeIndex !in queue.indices) {
            return QueueMutationResult(queue, currentIndex)
        }
        val updated = queue.toMutableList().apply { removeAt(removeIndex) }
        if (updated.isEmpty()) {
            return QueueMutationResult(queue = emptyList(), currentIndex = -1)
        }
        val newCurrent = when {
            removeIndex < currentIndex -> currentIndex - 1
            removeIndex == currentIndex -> currentIndex.coerceAtMost(updated.lastIndex)
            else -> currentIndex
        }
        return QueueMutationResult(queue = updated, currentIndex = newCurrent)
    }

    fun move(
        queue: List<Track>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int,
    ): QueueMutationResult {
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) {
            return QueueMutationResult(queue, currentIndex)
        }
        val updated = queue.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)

        val newCurrent = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return QueueMutationResult(queue = updated, currentIndex = newCurrent)
    }
}
