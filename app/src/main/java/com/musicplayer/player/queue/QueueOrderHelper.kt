package com.musicplayer.player.queue

import com.musicplayer.core.model.Track
import kotlin.random.Random

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

    /**
     * Shuffles a collection for playback so shuffle mode never gets stuck starting on track #1.
     * When [avoidFirstTrackId] is provided and there is another distinct track available, ensures
     * the new first track differs from [avoidFirstTrackId] so tapping Shuffle again immediately
     * plays a fresh song and builds a new shuffled queue.
     */
    fun shuffleForPlayback(
        tracks: List<Track>,
        avoidFirstTrackId: String? = null,
        random: Random = Random.Default,
    ): List<Track> {
        if (tracks.size <= 1) return tracks
        val shuffled = tracks.shuffled(random).toMutableList()
        if (avoidFirstTrackId != null && shuffled.first().id == avoidFirstTrackId) {
            val candidateIndices = shuffled.indices.filter { it > 0 && shuffled[it].id != avoidFirstTrackId }
            if (candidateIndices.isNotEmpty()) {
                val swapIndex = candidateIndices[random.nextInt(candidateIndices.size)]
                val first = shuffled[0]
                shuffled[0] = shuffled[swapIndex]
                shuffled[swapIndex] = first
            }
        }
        return shuffled
    }

    /**
     * Reshuffles ONLY the upcoming queue tracks without touching the currently playing track or
     * playback history. Preserves manual "Next in Queue" tracks when context tracks are available
     * to shuffle; if only manual tracks exist (size >= 2), shuffles those instead.
     */
    fun reshuffleUpcoming(
        queue: List<Track>,
        currentIndex: Int,
        manualQueueCount: Int,
        random: Random = Random.Default,
    ): QueueMutationResult {
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            return QueueMutationResult(queue, currentIndex, clampManualCount(manualQueueCount, currentIndex, queue.size))
        }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        val manualStart = currentIndex + 1
        val contextStart = (manualStart + clampedManual).coerceAtMost(queue.size)
        val contextTracks = queue.subList(contextStart, queue.size)

        if (contextTracks.size >= 2) {
            val reshuffledContext = shuffleDistinctOrder(contextTracks, random)
            val updated = buildList(queue.size) {
                addAll(queue.subList(0, contextStart))
                addAll(reshuffledContext)
            }
            return QueueMutationResult(
                queue = updated,
                currentIndex = currentIndex,
                manualQueueCount = clampedManual,
            )
        }

        val manualTracks = queue.subList(manualStart, contextStart)
        if (manualTracks.size >= 2) {
            val reshuffledManual = shuffleDistinctOrder(manualTracks, random)
            val updated = buildList(queue.size) {
                addAll(queue.subList(0, manualStart))
                addAll(reshuffledManual)
                addAll(contextTracks)
            }
            return QueueMutationResult(
                queue = updated,
                currentIndex = currentIndex,
                manualQueueCount = clampedManual,
            )
        }

        return QueueMutationResult(
            queue = queue,
            currentIndex = currentIndex,
            manualQueueCount = clampedManual,
        )
    }

    /**
     * Restores upcoming context tracks back to their original unshuffled order (when toggling
     * shuffle off), leaving the active track and manual "Next in Queue" tracks untouched.
     */
    fun restoreContextOrder(
        queue: List<Track>,
        currentIndex: Int,
        manualQueueCount: Int,
        originalContextTrackIds: List<String>,
    ): QueueMutationResult {
        if (queue.isEmpty() || currentIndex !in queue.indices || originalContextTrackIds.isEmpty()) {
            return QueueMutationResult(queue, currentIndex, clampManualCount(manualQueueCount, currentIndex, queue.size))
        }
        val clampedManual = clampManualCount(manualQueueCount, currentIndex, queue.size)
        val contextStart = (currentIndex + 1 + clampedManual).coerceAtMost(queue.size)
        val contextTracks = queue.subList(contextStart, queue.size)
        if (contextTracks.size < 2) {
            return QueueMutationResult(queue, currentIndex, clampedManual)
        }

        val orderIndexById = HashMap<String, Int>(originalContextTrackIds.size)
        originalContextTrackIds.forEachIndexed { idx, id ->
            orderIndexById.putIfAbsent(id, idx)
        }
        val restoredContext = contextTracks.sortedBy { orderIndexById[it.id] ?: Int.MAX_VALUE }
        val updated = buildList(queue.size) {
            addAll(queue.subList(0, contextStart))
            addAll(restoredContext)
        }
        return QueueMutationResult(
            queue = updated,
            currentIndex = currentIndex,
            manualQueueCount = clampedManual,
        )
    }

    private fun shuffleDistinctOrder(tracks: List<Track>, random: Random): List<Track> {
        if (tracks.size < 2) return tracks
        val originalIds = tracks.map { it.id }
        if (originalIds.toSet().size < 2) return tracks
        repeat(5) {
            val candidate = tracks.shuffled(random)
            if (candidate.map { it.id } != originalIds) {
                return candidate
            }
        }
        // Fallback guarantee: rotate by 1 if random kept identical order
        return tracks.drop(1) + tracks.first()
    }

    private fun clampManualCount(count: Int, currentIndex: Int, queueSize: Int): Int {
        if (currentIndex !in 0 until queueSize) return 0
        val remainingAfterCurrent = (queueSize - 1 - currentIndex).coerceAtLeast(0)
        return count.coerceIn(0, remainingAfterCurrent)
    }
}
