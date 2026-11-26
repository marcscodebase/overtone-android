package com.musicplayer.feature.home

import com.musicplayer.core.model.Track
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random

enum class DaylistPeriod {
    MORNING,
    EVENING,
}

data class DaylistSlot(
    val date: LocalDate,
    val period: DaylistPeriod,
) {
    val slotKey: String
        get() = "${date}_${period.name}"

    val nextRefreshTimeLabel: String
        get() = when (period) {
            DaylistPeriod.MORNING -> "12:00"
            DaylistPeriod.EVENING -> "00:00"
        }

    companion object {
        fun current(now: ZonedDateTime = ZonedDateTime.now()): DaylistSlot {
            val period = if (now.hour < 12) DaylistPeriod.MORNING else DaylistPeriod.EVENING
            return DaylistSlot(date = now.toLocalDate(), period = period)
        }
    }
}

data class Daylist(
    val slot: DaylistSlot,
    val tracks: List<Track>,
    val artworkUrls: List<String>,
)

/**
 * Generates a deterministic 50-track shuffled "Daylist" from the user's library that stays stable
 * throughout the current half-day window and automatically regenerates twice daily (00:00 & 12:00).
 */
object DaylistGenerator {

    const val TRACK_LIMIT = 50

    fun generate(
        allTracks: List<Track>,
        slot: DaylistSlot,
        limit: Int = TRACK_LIMIT,
    ): Daylist {
        if (allTracks.isEmpty() || limit <= 0) {
            return Daylist(slot = slot, tracks = emptyList(), artworkUrls = emptyList())
        }
        val canonical = allTracks.sortedBy { it.id }
        val seed = fnv1a64("daylist:${slot.slotKey}")
        val selected = canonical.shuffled(Random(seed)).take(limit)
        val mosaicArtworks = selected
            .mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            .distinct()
            .take(4)

        return Daylist(
            slot = slot,
            tracks = selected,
            artworkUrls = mosaicArtworks,
        )
    }

    private fun fnv1a64(input: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
        val prime = 0x100000001b3L
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return hash
    }
}
