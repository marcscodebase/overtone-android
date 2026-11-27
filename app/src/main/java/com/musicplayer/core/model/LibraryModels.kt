package com.musicplayer.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackArtist(
    val id: String,
    val name: String,
)

object TrackArtistsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(artists: List<TrackArtist>): String? {
        if (artists.isEmpty()) return null
        return runCatching { json.encodeToString(artists) }.getOrNull()
    }

    fun decode(raw: String?): List<TrackArtist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<TrackArtist>>(raw) }.getOrDefault(emptyList())
    }
}

object GenresCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(genres: List<String>): String? {
        val cleaned = genres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (cleaned.isEmpty()) return null
        return runCatching { json.encodeToString(cleaned) }.getOrNull()
    }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }
}

enum class InstantMixSeedKind {
    TRACK,
    ALBUM,
    ARTIST,
    PLAYLIST,
    GENRE,
}

data class InstantMixResult(
    val tracks: List<Track>,
    val fromJellyfinServer: Boolean,
)

data class Track(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
    val artists: List<TrackArtist> = emptyList(),
    val albumId: String?,
    val albumName: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val container: String?,
    val codec: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val hasLyrics: Boolean,
    val artworkUrl: String?,
    val isDownloaded: Boolean = false,
    val localUri: String? = null,
    val year: Int? = null,
    val premiereDate: Long? = null,
    val genres: List<String> = emptyList(),
) {
    val resolvedArtists: List<TrackArtist>
        get() = if (artists.isNotEmpty()) {
            artists
        } else {
            val id = artistId?.takeIf { it.isNotBlank() }
            if (id != null) listOf(TrackArtist(id = id, name = artistName)) else emptyList()
        }
}

data class Album(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
    val year: Int?,
    val trackCount: Int,
    val isFavorite: Boolean,
    val dateAdded: Long,
    val artworkUrl: String?,
    val premiereDate: Long? = null,
    val genres: List<String> = emptyList(),
)

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val isFavorite: Boolean,
    val artworkUrl: String?,
)

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val isAutoSyncDownloaded: Boolean,
    val artworkUrl: String?,
    val folderName: String? = null,
)

data class PlaylistTrackEntry(
    val track: Track,
    val playlistItemId: String,
    val position: Int,
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val isOfflineFallback: Boolean = false,
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}
