package com.musicplayer.navigation

import kotlinx.serialization.Serializable

@Serializable
data object MainDestination

@Serializable
data object SearchDestination

@Serializable
data object DaylistDestination

@Serializable
data class HomeCollectionDestination(val section: String)

@Serializable
data class LibraryCollectionDestination(val section: String)

@Serializable
data class AlbumDetailDestination(val albumId: String)

@Serializable
data class ArtistDetailDestination(val artistId: String)

@Serializable
data class ArtistCollectionDestination(
    val artistId: String,
    val section: String,
)

@Serializable
data class PlaylistDetailDestination(val playlistId: String)

@Serializable
data object SettingsDestination

@Serializable
data object StorageManagerDestination

@Serializable
data object WireGuardDestination
