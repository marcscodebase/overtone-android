package com.musicplayer.feature.library.detail

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track
import java.time.Instant
import java.time.ZoneOffset

enum class ChronologicalOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    ;

    fun toggle(): ChronologicalOrder = when (this) {
        NEWEST_FIRST -> OLDEST_FIRST
        OLDEST_FIRST -> NEWEST_FIRST
    }
}

enum class ArtistCollectionSection(val routeKey: String) {
    FAVORITES("favorites"),
    ALL_SONGS("songs"),
    ALL_ALBUMS("albums"),
    ;

    companion object {
        fun fromRouteKey(key: String?): ArtistCollectionSection =
            entries.firstOrNull { it.routeKey.equals(key, ignoreCase = true) } ?: ALL_SONGS
    }
}

object ArtistCatalogHelper {

    fun effectiveReleaseYear(track: Track, albumsById: Map<String, Album>): Int? {
        track.year?.takeIf { it > 0 }?.let { return it }
        albumsById[track.albumId]?.year?.takeIf { it > 0 }?.let { return it }
        val premiere = effectivePremiereDate(track, albumsById) ?: return null
        return Instant.ofEpochMilli(premiere).atZone(ZoneOffset.UTC).year
    }

    fun effectivePremiereDate(track: Track, albumsById: Map<String, Album>): Long? =
        track.premiereDate ?: albumsById[track.albumId]?.premiereDate

    fun topTracks(
        tracks: List<Track>,
        albumsById: Map<String, Album>,
        limit: Int = 10,
    ): List<Track> {
        if (limit <= 0 || tracks.isEmpty()) return emptyList()
        return tracks
            .sortedWith(
                compareByDescending<Track> { it.playCount }
                    .thenByDescending { it.lastPlayedAt ?: 0L }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { effectiveReleaseYear(it, albumsById) ?: Int.MIN_VALUE }
                    .thenByDescending { effectivePremiereDate(it, albumsById) ?: Long.MIN_VALUE }
                    .thenBy { it.title.lowercase() },
            )
            .take(limit)
    }

    fun favoriteTracks(
        tracks: List<Track>,
        albumsById: Map<String, Album>,
    ): List<Track> = tracks
        .filter { it.isFavorite }
        .sortedWith(
            compareByDescending<Track> { it.playCount }
                .thenByDescending { effectiveReleaseYear(it, albumsById) ?: Int.MIN_VALUE }
                .thenByDescending { effectivePremiereDate(it, albumsById) ?: Long.MIN_VALUE }
                .thenBy { it.title.lowercase() },
        )

    fun chronologicalTracks(
        tracks: List<Track>,
        albumsById: Map<String, Album>,
        order: ChronologicalOrder,
    ): List<Track> {
        val comparator = Comparator<Track> { a, b ->
            val aYear = effectiveReleaseYear(a, albumsById)
            val bYear = effectiveReleaseYear(b, albumsById)
            val aDate = effectivePremiereDate(a, albumsById)
            val bDate = effectivePremiereDate(b, albumsById)

            val aKnown = aYear != null || aDate != null
            val bKnown = bYear != null || bDate != null
            if (aKnown != bKnown) {
                return@Comparator if (aKnown) -1 else 1
            }

            if (aKnown) {
                val yearCmp = compareNullableChrono(aYear, bYear, order)
                if (yearCmp != 0) return@Comparator yearCmp

                val dateCmp = compareNullableChrono(aDate, bDate, order)
                if (dateCmp != 0) return@Comparator dateCmp
            }

            val albumCmp = a.albumName.compareTo(b.albumName, ignoreCase = true)
            if (albumCmp != 0) return@Comparator albumCmp

            val discCmp = (a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
            if (discCmp != 0) return@Comparator discCmp

            val trackNumCmp = (a.trackNumber ?: 0).compareTo(b.trackNumber ?: 0)
            if (trackNumCmp != 0) return@Comparator trackNumCmp

            a.title.compareTo(b.title, ignoreCase = true)
        }
        return tracks.sortedWith(comparator)
    }

    fun chronologicalAlbums(
        albums: List<Album>,
        order: ChronologicalOrder,
    ): List<Album> {
        val comparator = Comparator<Album> { a, b ->
            val aYear = a.year?.takeIf { it > 0 }
                ?: a.premiereDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
            val bYear = b.year?.takeIf { it > 0 }
                ?: b.premiereDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
            val aDate = a.premiereDate
            val bDate = b.premiereDate

            val aKnown = aYear != null || aDate != null
            val bKnown = bYear != null || bDate != null
            if (aKnown != bKnown) {
                return@Comparator if (aKnown) -1 else 1
            }

            if (aKnown) {
                val yearCmp = compareNullableChrono(aYear, bYear, order)
                if (yearCmp != 0) return@Comparator yearCmp

                val dateCmp = compareNullableChrono(aDate, bDate, order)
                if (dateCmp != 0) return@Comparator dateCmp
            }

            a.title.compareTo(b.title, ignoreCase = true)
        }
        return albums.sortedWith(comparator)
    }

    private fun <T : Comparable<T>> compareNullableChrono(
        a: T?,
        b: T?,
        order: ChronologicalOrder,
    ): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        return when (order) {
            ChronologicalOrder.NEWEST_FIRST -> b.compareTo(a)
            ChronologicalOrder.OLDEST_FIRST -> a.compareTo(b)
        }
    }
}
