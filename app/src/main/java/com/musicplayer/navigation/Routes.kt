package com.musicplayer.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination

@Serializable
data object SearchDestination

@Serializable
data object LibraryDestination

@Serializable
data class AlbumDetailDestination(val albumId: String)

@Serializable
data class ArtistDetailDestination(val artistId: String)

@Serializable
data class PlaylistDetailDestination(val playlistId: String)

@Serializable
data object SettingsDestination
