package com.musicplayer.feature.home

import com.musicplayer.core.model.Track
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random

enum class DaylistPeriod {
    LATE_NIGHT,
    MORNING,
    AFTERNOON,
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
            DaylistPeriod.LATE_NIGHT -> "06:00"
            DaylistPeriod.MORNING -> "12:00"
            DaylistPeriod.AFTERNOON -> "18:00"
            DaylistPeriod.EVENING -> "00:00"
        }

    companion object {
        fun current(now: ZonedDateTime = ZonedDateTime.now()): DaylistSlot {
            val period = when (now.hour) {
                in 0..5 -> DaylistPeriod.LATE_NIGHT
                in 6..11 -> DaylistPeriod.MORNING
                in 12..17 -> DaylistPeriod.AFTERNOON
                else -> DaylistPeriod.EVENING
            }
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
 * throughout the current 6-hour window and automatically regenerates 4 times daily
 * (00:00, 06:00, 12:00 & 18:00), or on-demand via a manual refresh nonce.
 */
object DaylistGenerator {

    const val TRACK_LIMIT = 50

    fun generate(
        allTracks: List<Track>,
        slot: DaylistSlot,
        refreshNonce: Long = 0L,
        limit: Int = TRACK_LIMIT,
    ): Daylist {
        if (allTracks.isEmpty() || limit <= 0) {
            return Daylist(slot = slot, tracks = emptyList(), artworkUrls = emptyList())
        }
        val canonical = allTracks.sortedBy { it.id }
        val seedInput = if (refreshNonce > 0L) {
            "daylist:${slot.slotKey}:$refreshNonce"
        } else {
            "daylist:${slot.slotKey}"
        }
        val seed = fnv1a64(seedInput)
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
