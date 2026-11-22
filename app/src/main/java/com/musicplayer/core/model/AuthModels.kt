package com.musicplayer.core.model

data class ServerPublicInfo(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val version: String?,
    val quickConnectEnabled: Boolean,
)

data class QuickConnectSession(
    val secret: String,
    val code: String,
)

data class MusicLibrary(
    val id: String,
    val name: String,
    val imageTag: String?,
)
