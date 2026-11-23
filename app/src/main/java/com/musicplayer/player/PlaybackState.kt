package com.musicplayer.player

import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

data class PlaybackState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val manualQueueCount: Int = 0,
    val queueContextLabel: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isLocalPlayback: Boolean = false,
    val transcodedBitrateKbps: Int? = null,
    val streamQualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
    val errorMessage: String? = null,
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val manualQueueTracks: List<Track>
        get() {
            if (currentIndex !in queue.indices || manualQueueCount <= 0) return emptyList()
            val start = (currentIndex + 1).coerceAtMost(queue.size)
            val end = (start + manualQueueCount).coerceAtMost(queue.size)
            return if (start < end) queue.subList(start, end) else emptyList()
        }

    val contextUpNextTracks: List<Track>
        get() {
            if (currentIndex !in queue.indices) return emptyList()
            val start = (currentIndex + 1 + manualQueueCount.coerceAtLeast(0)).coerceAtMost(queue.size)
            return if (start < queue.size) queue.subList(start, queue.size) else emptyList()
        }
}
