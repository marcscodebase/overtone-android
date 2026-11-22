package com.musicplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthSession(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val username: String,
    val accessToken: String,
)
