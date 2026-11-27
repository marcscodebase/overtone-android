package com.musicplayer.feature.library

import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryListBuilderTest {

    private fun album(
        id: String,
        title: String,
        artist: String = "Artist",
        isFavorite: Boolean = false,
        dateAdded: Long = 100L,
    ) = Album(
        id = id,
        title = title,
        artistId = "art-$artist",
        artistName = artist,
        year = 2022,
        trackCount = 10,
        isFavorite = isFavorite,
        dateAdded = dateAdded,
        artworkUrl = null,
    )

    private fun artist(
        id: String,
        name: String,
        isFavorite: Boolean = false,
    ) = Artist(
        id = id,
        name = name,
        albumCount = 3,
        isFavorite = isFavorite,
        artworkUrl = null,
    )

    private fun playlist(
        id: String,
        name: String,
        autoSync: Boolean = false,
    ) = Playlist(
        id = id,
        name = name,
        trackCount = 12,
        isAutoSyncDownloaded = autoSync,
        artworkUrl = null,
        folderName = null,
    )

    @Test
    fun `includes only curated albums and artists while keeping pinned items first in pin order`() {
        val albums = listOf(
            album("a-fav", "Zebra Album", artist = "Beta", isFavorite = true, dateAdded = 200L),
            album("a-dl", "Downloaded Album", artist = "Alpha", isFavorite = false, dateAdded = 300L),
            album("a-server-only", "Unfavorited Server Album", isFavorite = false, dateAdded = 400L),
        )
        val artists = listOf(
            artist("art-fav", "Favorite Artist", isFavorite = true),
            artist("art-server-only", "Random Server Artist", isFavorite = false),
        )
        val playlists = listOf(playlist("p1", "Roadtrip"))
        val folders = listOf(PlaylistFolderGroup("Workout", listOf(playlist("p2", "Gym"))))

        val items = LibraryListBuilder.build(
            favoriteTrackCount = 15,
            downloadedTrackCount = 8,
            downloadedAlbumIds = setOf("a-dl"),
            playlistFolders = folders,
            rootPlaylists = playlists,
            allAlbums = albums,
            allArtists = artists,
            activeFilter = null,
            sort = LibrarySort.ALPHABETICAL,
            pinnedKeys = listOf(
                LibraryListItem.playlistPinKey("p1"),
                UserPreferences.PIN_KEY_FAVORITES,
                UserPreferences.PIN_KEY_DOWNLOADED,
            ),
        )

        // Exact pin order respected at the top
        assertEquals(
            listOf(
                LibraryListItem.playlistPinKey("p1"),
                UserPreferences.PIN_KEY_FAVORITES,
                UserPreferences.PIN_KEY_DOWNLOADED,
            ),
            items.take(3).map { it.pinKey },
        )
        assertTrue(items.take(3).all { it.isPinned })

        // Unfavorited/un-downloaded server items must NOT clutter personal Library
        val keys = items.map { it.pinKey }
        assertTrue(LibraryListItem.albumPinKey("a-fav") in keys)
        assertTrue(LibraryListItem.albumPinKey("a-dl") in keys)
        assertFalse(LibraryListItem.albumPinKey("a-server-only") in keys)
        assertTrue(LibraryListItem.artistPinKey("art-fav") in keys)
        assertFalse(LibraryListItem.artistPinKey("art-server-only") in keys)
    }

    @Test
    fun `applies filter chips and sort options accurately`() {
        val albums = listOf(
            album("a1", "Zeta", artist = "Aaron", isFavorite = true, dateAdded = 100L),
            album("a2", "Alpha", artist = "Zoe", isFavorite = true, dateAdded = 500L),
        )

        val byAlpha = LibraryListBuilder.build(
            favoriteTrackCount = 1,
            downloadedTrackCount = 0,
            downloadedAlbumIds = emptySet(),
            playlistFolders = emptyList(),
            rootPlaylists = emptyList(),
            allAlbums = albums,
            allArtists = emptyList(),
            activeFilter = LibraryFilter.ALBUMS,
            sort = LibrarySort.ALPHABETICAL,
            pinnedKeys = emptyList(),
        )
        assertEquals(listOf("Alpha", "Zeta"), byAlpha.map { it.title })

        val byRecent = LibraryListBuilder.build(
            favoriteTrackCount = 1,
            downloadedTrackCount = 0,
            downloadedAlbumIds = emptySet(),
            playlistFolders = emptyList(),
            rootPlaylists = emptyList(),
            allAlbums = albums,
            allArtists = emptyList(),
            activeFilter = LibraryFilter.ALBUMS,
            sort = LibrarySort.RECENTLY_ADDED,
            pinnedKeys = emptyList(),
        )
        assertEquals(listOf("Alpha", "Zeta"), byRecent.map { it.title })

        val byCreator = LibraryListBuilder.build(
            favoriteTrackCount = 1,
            downloadedTrackCount = 0,
            downloadedAlbumIds = emptySet(),
            playlistFolders = emptyList(),
            rootPlaylists = emptyList(),
            allAlbums = albums,
            allArtists = emptyList(),
            activeFilter = LibraryFilter.ALBUMS,
            sort = LibrarySort.CREATOR,
            pinnedKeys = emptyList(),
        )
        assertEquals(listOf("Zeta", "Alpha"), byCreator.map { it.title })
    }

    @Test
    fun `sorts unpinned favorite songs and downloaded songs alphabetically among other items`() {
        val items = LibraryListBuilder.build(
            favoriteTrackCount = 10,
            downloadedTrackCount = 5,
            downloadedAlbumIds = emptySet(),
            playlistFolders = emptyList(),
            rootPlaylists = listOf(
                playlist("p-a", "Ambient"),
                playlist("p-e", "Electronic"),
                playlist("p-z", "Zen"),
            ),
            allAlbums = emptyList(),
            allArtists = emptyList(),
            activeFilter = null,
            sort = LibrarySort.ALPHABETICAL,
            pinnedKeys = emptyList(),
        )

        assertEquals(
            listOf("Ambient", "Downloaded Songs", "Electronic", "Favorite Songs", "Zen"),
            items.map { it.title },
        )
    }
}
