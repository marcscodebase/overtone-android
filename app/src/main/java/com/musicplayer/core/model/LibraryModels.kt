package com.musicplayer.core.model

data class Track(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
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
)

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
