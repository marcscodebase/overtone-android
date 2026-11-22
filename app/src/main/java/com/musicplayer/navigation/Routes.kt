package com.musicplayer.navigation

import kotlinx.serialization.Serializable

@Serializable
data object MainDestination

@Serializable
data object SearchDestination

@Serializable
data class AlbumDetailDestination(val albumId: String)

@Serializable
data class ArtistDetailDestination(val artistId: String)

@Serializable
data class PlaylistDetailDestination(val playlistId: String)

@Serializable
data object SettingsDestination

@Serializable
data object StorageManagerDestination
