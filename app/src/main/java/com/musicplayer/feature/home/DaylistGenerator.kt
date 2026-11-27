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
 * Generates a deterministic 50-track "Daylist" from the user's library that stays stable
 * throughout the current 6-hour window and automatically regenerates 4 times daily
 * (00:00, 06:00, 12:00 & 18:00), or on-demand via a manual refresh nonce.
 *
 * When listening history or favorites exist, blends ~45% familiar favorites/rotation,
 * ~30% rediscovery (older played tracks), and ~25% fresh unplayed catalog cuts; otherwise
 * falls back cleanly to a deterministic full-library shuffle.
 */
object DaylistGenerator {

    const val TRACK_LIMIT = 50
    private const val REDISCOVERY_AGE_MS = 21L * 24L * 60L * 60L * 1000L

    fun generate(
        allTracks: List<Track>,
        slot: DaylistSlot,
        refreshNonce: Long = 0L,
        limit: Int = TRACK_LIMIT,
        nowMs: Long = System.currentTimeMillis(),
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
        val rng = Random(fnv1a64(seedInput))

        val rediscoveryCutoff = nowMs - REDISCOVERY_AGE_MS
        val familiar = mutableListOf<Track>()
        val rediscovery = mutableListOf<Track>()
        val unplayed = mutableListOf<Track>()

        for (track in canonical) {
            val lastPlayed = track.lastPlayedAt
            when {
                (track.isFavorite || track.playCount >= 2) &&
                    lastPlayed != null && lastPlayed < rediscoveryCutoff -> rediscovery += track
                track.isFavorite || track.playCount > 0 -> familiar += track
                else -> unplayed += track
            }
        }

        val hasHistorySignal = familiar.isNotEmpty() || rediscovery.isNotEmpty()
        val selected = if (!hasHistorySignal) {
            canonical.shuffled(rng).take(limit)
        } else {
            val targetFamiliar = (limit * 0.45f).toInt().coerceAtLeast(1)
            val targetRediscovery = (limit * 0.30f).toInt()
            val targetUnplayed = (limit - targetFamiliar - targetRediscovery).coerceAtLeast(0)

            val picked = LinkedHashMap<String, Track>(limit)
            fun pickFrom(bucket: List<Track>, quota: Int) {
                if (quota <= 0 || bucket.isEmpty() || picked.size >= limit) return
                val shuffled = bucket.shuffled(rng)
                var added = 0
                for (track in shuffled) {
                    if (added >= quota || picked.size >= limit) break
                    if (picked.putIfAbsent(track.id, track) == null) {
                        added++
                    }
                }
            }

            pickFrom(familiar, targetFamiliar)
            pickFrom(rediscovery, targetRediscovery)
            pickFrom(unplayed, targetUnplayed)
            if (picked.size < limit) {
                pickFrom(canonical, limit - picked.size)
            }
            // Interleave buckets cleanly so familiar, rediscovery & fresh cuts flow naturally
            picked.values.toList().shuffled(rng)
        }

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

    internal fun fnv1a64(input: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
        val prime = 0x100000001b3L
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return hash
    }
}
