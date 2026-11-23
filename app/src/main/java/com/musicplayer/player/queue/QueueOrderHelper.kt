package com.musicplayer.player.queue

import com.musicplayer.core.model.Track

data class QueueMutationResult(
    val queue: List<Track>,
    val currentIndex: Int,
    val manualQueueCount: Int = 0,
)

/**
 * Pure Spotify-style queue ordering operations shared between UI, controller state, and tests.
 *
 * Queue layout:
 * - `0 until currentIndex`: history
 * - `currentIndex`: Now Playing
 * - `(currentIndex + 1)..(currentIndex + manualQueueCount)`: "Next in Queue" (manual priority)
 * - `(currentIndex + 1 + manualQueueCount) until queue.size`: "Next from <context>"
 */
object QueueOrderHelper {

    fun insertNext(
        queue: List<Track>,
        currentIndex: Int,
        track: Track,
        manualQueueCount: Int = 0,
    ): QueueMutationResult {
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            return QueueMutationResult(queue = listOf(track), currentIndex = 0, manualQueueCount = 0)
        }
        val insertIndex = (currentIndex + 1).coerceAtMost(queue.size)
        val updated = queue.toMutableList().apply { add(insertIndex, track) }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        return QueueMutationResult(
            queue = updated,
            currentIndex = currentIndex,
            manualQueueCount = clampedManual + 1,
        )
    }

    /**
     * Spotify-style "Add to Queue": appends tracks to the end of the manual "Next in Queue"
     * section (immediately before "Next from <context>").
     */
    fun enqueueManual(
        queue: List<Track>,
        currentIndex: Int,
        manualQueueCount: Int,
        tracks: List<Track>,
    ): QueueMutationResult {
        if (tracks.isEmpty()) {
            return QueueMutationResult(queue, currentIndex, clampManualCount(manualQueueCount, currentIndex, queue.size))
        }
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            return QueueMutationResult(queue = tracks, currentIndex = 0, manualQueueCount = 0)
        }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        val insertIndex = (currentIndex + 1 + clampedManual).coerceAtMost(queue.size)
        val updated = queue.toMutableList().apply { addAll(insertIndex, tracks) }
        return QueueMutationResult(
            queue = updated,
            currentIndex = currentIndex,
            manualQueueCount = clampedManual + tracks.size,
        )
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
        manualQueueCount: Int = 0,
    ): QueueMutationResult {
        if (removeIndex !in queue.indices) {
            return QueueMutationResult(queue, currentIndex, clampManualCount(manualQueueCount, currentIndex, queue.size))
        }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        val wasInManual = removeIndex in (currentIndex + 1)..(currentIndex + clampedManual)
        val updated = queue.toMutableList().apply { removeAt(removeIndex) }
        if (updated.isEmpty()) {
            return QueueMutationResult(queue = emptyList(), currentIndex = -1, manualQueueCount = 0)
        }
        val newCurrent = when {
            removeIndex < currentIndex -> currentIndex - 1
            removeIndex == currentIndex -> currentIndex.coerceAtMost(updated.lastIndex)
            else -> currentIndex
        }
        val newManual = when {
            wasInManual -> (clampedManual - 1).coerceAtLeast(0)
            removeIndex == currentIndex -> (clampedManual - 1).coerceAtLeast(0)
            else -> clampedManual
        }
        return QueueMutationResult(
            queue = updated,
            currentIndex = newCurrent,
            manualQueueCount = clampManualCount(newManual, newCurrent, updated.size),
        )
    }

    fun move(
        queue: List<Track>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int,
        manualQueueCount: Int = 0,
    ): QueueMutationResult {
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) {
            return QueueMutationResult(queue, currentIndex, clampManualCount(manualQueueCount, currentIndex, queue.size))
        }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        val manualStart = currentIndex + 1
        val manualEndInclusive = currentIndex + clampedManual
        val fromInManual = clampedManual > 0 && fromIndex in manualStart..manualEndInclusive

        val updated = queue.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)

        val newCurrent = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        val newManual = if (fromIndex > currentIndex && toIndex > currentIndex) {
            when {
                // Moved from manual section into context section
                fromInManual && toIndex > manualEndInclusive -> (clampedManual - 1).coerceAtLeast(0)
                // Moved from context section into manual section
                !fromInManual && clampedManual > 0 && toIndex <= manualEndInclusive -> clampedManual + 1
                else -> clampedManual
            }
        } else {
            clampedManual
        }

        return QueueMutationResult(
            queue = updated,
            currentIndex = newCurrent,
            manualQueueCount = clampManualCount(newManual, newCurrent, updated.size),
        )
    }

    fun clearManualQueue(
        queue: List<Track>,
        currentIndex: Int,
        manualQueueCount: Int,
    ): QueueMutationResult {
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        if (clampedManual <= 0 || currentIndex !in queue.indices) {
            return QueueMutationResult(queue, currentIndex, 0)
        }
        val start = currentIndex + 1
        val endExclusive = (start + clampedManual).coerceAtMost(queue.size)
        val updated = queue.toMutableList().apply {
            subList(start, endExclusive).clear()
        }
        return QueueMutationResult(
            queue = updated,
            currentIndex = currentIndex,
            manualQueueCount = 0,
        )
    }

    fun adjustManualCountOnIndexChange(
        oldIndex: Int,
        newIndex: Int,
        manualQueueCount: Int,
        queueSize: Int,
    ): Int {
        if (manualQueueCount <= 0 || newIndex !in 0 until queueSize) return 0
        val adjusted = when {
            newIndex == oldIndex -> manualQueueCount
            newIndex > oldIndex -> (manualQueueCount - (newIndex - oldIndex)).coerceAtLeast(0)
            else -> 0
        }
        return clampManualCount(adjusted, newIndex, queueSize)
    }

    private fun clampManualCount(count: Int, currentIndex: Int, queueSize: Int): Int {
        if (currentIndex !in 0 until queueSize) return 0
        val remainingAfterCurrent = (queueSize - 1 - currentIndex).coerceAtLeast(0)
        return count.coerceIn(0, remainingAfterCurrent)
    }
}
