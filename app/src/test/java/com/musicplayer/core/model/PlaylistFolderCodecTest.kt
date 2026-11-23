package com.musicplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistFolderCodecTest {

    @Test
    fun normalizesUndashedJellyfinIdsToCanonicalUuid() {
        val undashed = "1234567890abcdef1234567890abcdef"
        val canonical = "12345678-90ab-cdef-1234-567890abcdef"
        assertEquals(canonical, PlaylistFolderCodec.normalizeJellyfinId(undashed))
        assertEquals(canonical, PlaylistFolderCodec.normalizeJellyfinId(canonical.uppercase()))
    }

    @Test
    fun parsesNamingConventionWithoutTags() {
        val parsed = PlaylistFolderCodec.parse(rawName = "Workout / Gym Hits", tags = emptyList())
        assertEquals("Gym Hits", parsed.displayName)
        assertEquals("Workout", parsed.folderName)
    }

    @Test
    fun parsesJellyfinFolderTagWithoutNamePrefix() {
        val parsed = PlaylistFolderCodec.parse(
            rawName = "Leg Day",
            tags = listOf("favorite-mix", "folder:Workout"),
        )
        assertEquals("Leg Day", parsed.displayName)
        assertEquals("Workout", parsed.folderName)
    }

    @Test
    fun parsesCombinedNamingConventionAndFolderTag() {
        val parsed = PlaylistFolderCodec.parse(
            rawName = "Workout / Cardio",
            tags = listOf("folder:Workout"),
        )
        assertEquals("Cardio", parsed.displayName)
        assertEquals("Workout", parsed.folderName)
    }

    @Test
    fun doesNotSplitSlashesWithoutSurroundingSpaces() {
        val parsed = PlaylistFolderCodec.parse(rawName = "80s/90s Rock & AC/DC", tags = emptyList())
        assertEquals("80s/90s Rock & AC/DC", parsed.displayName)
        assertNull(parsed.folderName)
    }

    @Test
    fun formatsRemoteNameAndUpdatesTagsWhilePreservingOtherTags() {
        assertEquals("Workout / Gym", PlaylistFolderCodec.formatRemoteName("Gym", "Workout"))
        assertEquals("Gym", PlaylistFolderCodec.formatRemoteName("Gym", null))

        val added = PlaylistFolderCodec.buildUpdatedTags(
            existingTags = listOf("high-energy", "folder:OldFolder"),
            folderName = "Workout",
        )
        assertEquals(listOf("high-energy", "folder:Workout"), added)

        val cleared = PlaylistFolderCodec.buildUpdatedTags(
            existingTags = listOf("high-energy", "folder:Workout"),
            folderName = null,
        )
        assertEquals(listOf("high-energy"), cleared)
    }

    @Test
    fun groupsPlaylistsIntoSortedFoldersAndRootPlaylists() {
        val playlists = listOf(
            Playlist("1", "Chill", 5, false, null, folderName = null),
            Playlist("2", "Cardio", 10, false, null, folderName = "Workout"),
            Playlist("3", "Techno", 8, false, null, folderName = "Genres"),
            Playlist("4", "Gym", 12, false, null, folderName = "workout"),
        )
        val (folders, root) = PlaylistFolderCodec.groupPlaylists(playlists)
        assertEquals(listOf("Genres", "Workout"), folders.map { it.name })
        assertEquals(listOf("Cardio", "Gym"), folders[1].playlists.map { it.name })
        assertEquals(22, folders[1].totalTrackCount)
        assertEquals(listOf("Chill"), root.map { it.name })
    }
}
