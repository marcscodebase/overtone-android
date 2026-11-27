package com.musicplayer.feature.library

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.core.model.UserPreferences

enum class LibraryCollectionSection(val routeKey: String) {
    FAVORITES("favorites"),
    DOWNLOADED("downloaded"),
    ;

    companion object {
        fun fromRouteKey(key: String?): LibraryCollectionSection =
            entries.firstOrNull { it.routeKey.equals(key, ignoreCase = true) } ?: FAVORITES
    }
}

enum class LibraryFilter {
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    DOWNLOADED,
}

enum class LibrarySort {
    ALPHABETICAL,
    RECENTLY_ADDED,
    CREATOR,
}

sealed interface LibraryListItem {
    val pinKey: String
    val title: String
    val creator: String
    val dateAdded: Long
    val isPinned: Boolean
    val hasDownloadedContent: Boolean

    data class BuiltInCollection(
        val section: LibraryCollectionSection,
        override val title: String,
        val trackCount: Int,
        override val isPinned: Boolean,
    ) : LibraryListItem {
        override val pinKey: String = when (section) {
            LibraryCollectionSection.FAVORITES -> UserPreferences.PIN_KEY_FAVORITES
            LibraryCollectionSection.DOWNLOADED -> UserPreferences.PIN_KEY_DOWNLOADED
        }
        override val creator: String = ""
        override val dateAdded: Long = 0L
        override val hasDownloadedContent: Boolean = section == LibraryCollectionSection.DOWNLOADED
    }

    data class PlaylistFolderItem(
        val folder: PlaylistFolderGroup,
        override val isPinned: Boolean,
        override val hasDownloadedContent: Boolean,
    ) : LibraryListItem {
        override val pinKey: String = folderPinKey(folder.name)
        override val title: String = folder.name
        override val creator: String = ""
        override val dateAdded: Long = 0L
    }

    data class PlaylistItem(
        val playlist: Playlist,
        override val isPinned: Boolean,
        override val hasDownloadedContent: Boolean,
    ) : LibraryListItem {
        override val pinKey: String = playlistPinKey(playlist.id)
        override val title: String = playlist.name
        override val creator: String = ""
        override val dateAdded: Long = 0L
    }

    data class AlbumItem(
        val album: Album,
        override val isPinned: Boolean,
        override val hasDownloadedContent: Boolean,
    ) : LibraryListItem {
        override val pinKey: String = albumPinKey(album.id)
        override val title: String = album.title
        override val creator: String = album.artistName
        override val dateAdded: Long = album.dateAdded
    }

    data class ArtistItem(
        val artist: Artist,
        override val isPinned: Boolean,
    ) : LibraryListItem {
        override val pinKey: String = artistPinKey(artist.id)
        override val title: String = artist.name
        override val creator: String = artist.name
        override val dateAdded: Long = 0L
        override val hasDownloadedContent: Boolean = false
    }

    companion object {
        fun folderPinKey(folderName: String): String = "folder:${folderName.trim().lowercase()}"
        fun playlistPinKey(playlistId: String): String = "playlist:$playlistId"
        fun albumPinKey(albumId: String): String = "album:$albumId"
        fun artistPinKey(artistId: String): String = "artist:$artistId"
    }
}

/**
 * Pure builder for the unified Spotify-style personal Library list.
 * Combines built-in collections (Favorites, Downloaded), playlists/folders, and curated
 * (favorited or downloaded) albums and artists, with pinning and flexible sorting.
 */
object LibraryListBuilder {

    fun build(
        favoriteTrackCount: Int,
        downloadedTrackCount: Int,
        downloadedAlbumIds: Set<String>,
        playlistFolders: List<PlaylistFolderGroup>,
        rootPlaylists: List<Playlist>,
        allAlbums: List<Album>,
        allArtists: List<Artist>,
        activeFilter: LibraryFilter?,
        sort: LibrarySort,
        pinnedKeys: List<String>,
        favoritesTitle: String = "Favorite Songs",
        downloadedTitle: String = "Downloaded Songs",
    ): List<LibraryListItem> {
        val pinOrder = pinnedKeys.withIndex().associate { (idx, key) -> key to idx }
        val candidates = mutableListOf<LibraryListItem>()

        val includeCollections = activeFilter == null || activeFilter == LibraryFilter.DOWNLOADED
        if (includeCollections) {
            if (activeFilter == null && (favoriteTrackCount > 0 || UserPreferences.PIN_KEY_FAVORITES in pinOrder)) {
                candidates += LibraryListItem.BuiltInCollection(
                    section = LibraryCollectionSection.FAVORITES,
                    title = favoritesTitle,
                    trackCount = favoriteTrackCount,
                    isPinned = UserPreferences.PIN_KEY_FAVORITES in pinOrder,
                )
            }
            if (downloadedTrackCount > 0 ||
                activeFilter == LibraryFilter.DOWNLOADED ||
                UserPreferences.PIN_KEY_DOWNLOADED in pinOrder
            ) {
                candidates += LibraryListItem.BuiltInCollection(
                    section = LibraryCollectionSection.DOWNLOADED,
                    title = downloadedTitle,
                    trackCount = downloadedTrackCount,
                    isPinned = UserPreferences.PIN_KEY_DOWNLOADED in pinOrder,
                )
            }
        }

        if (activeFilter == null || activeFilter == LibraryFilter.PLAYLISTS || activeFilter == LibraryFilter.DOWNLOADED) {
            playlistFolders.forEach { folder ->
                val hasDownloaded = folder.playlists.any { it.isAutoSyncDownloaded }
                if (activeFilter != LibraryFilter.DOWNLOADED || hasDownloaded) {
                    val key = LibraryListItem.folderPinKey(folder.name)
                    candidates += LibraryListItem.PlaylistFolderItem(
                        folder = folder,
                        isPinned = key in pinOrder,
                        hasDownloadedContent = hasDownloaded,
                    )
                }
            }
            rootPlaylists.forEach { playlist ->
                val hasDownloaded = playlist.isAutoSyncDownloaded
                if (activeFilter != LibraryFilter.DOWNLOADED || hasDownloaded) {
                    val key = LibraryListItem.playlistPinKey(playlist.id)
                    candidates += LibraryListItem.PlaylistItem(
                        playlist = playlist,
                        isPinned = key in pinOrder,
                        hasDownloadedContent = hasDownloaded,
                    )
                }
            }
        }

        if (activeFilter == null || activeFilter == LibraryFilter.ALBUMS || activeFilter == LibraryFilter.DOWNLOADED) {
            allAlbums.forEach { album ->
                val isDownloaded = album.id in downloadedAlbumIds
                val key = LibraryListItem.albumPinKey(album.id)
                val isPinned = key in pinOrder
                val inCuratedLibrary = album.isFavorite || isDownloaded || isPinned
                if (inCuratedLibrary && (activeFilter != LibraryFilter.DOWNLOADED || isDownloaded)) {
                    candidates += LibraryListItem.AlbumItem(
                        album = album,
                        isPinned = isPinned,
                        hasDownloadedContent = isDownloaded,
                    )
                }
            }
        }

        if (activeFilter == null || activeFilter == LibraryFilter.ARTISTS) {
            allArtists.forEach { artist ->
                val key = LibraryListItem.artistPinKey(artist.id)
                val isPinned = key in pinOrder
                if (artist.isFavorite || isPinned) {
                    candidates += LibraryListItem.ArtistItem(
                        artist = artist,
                        isPinned = isPinned,
                    )
                }
            }
        }

        val (pinned, unpinned) = candidates.partition { it.isPinned }
        val sortedPinned = pinned.sortedBy { pinOrder[it.pinKey] ?: Int.MAX_VALUE }
        val sortedUnpinned = when (sort) {
            LibrarySort.ALPHABETICAL -> unpinned.sortedBy { it.title.lowercase() }

            LibrarySort.RECENTLY_ADDED -> unpinned.sortedWith(
                compareByDescending<LibraryListItem> { it.dateAdded }
                    .thenBy { it.title.lowercase() },
            )

            LibrarySort.CREATOR -> unpinned.sortedWith(
                compareBy<LibraryListItem> { it.creator.ifBlank { it.title }.lowercase() }
                    .thenBy { it.title.lowercase() },
            )
        }

        return sortedPinned + sortedUnpinned
    }
}
