package com.musicplayer.core.model

import kotlinx.serialization.Serializable

enum class WireGuardMode {
    OFF,
    AUTO,
    ALWAYS,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): WireGuardMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

@Serializable
data class WireGuardConfig(
    val profileName: String = "Home Network",
    val privateKeyBase64: String,
    val addresses: List<String>,
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = DEFAULT_MTU,
    val peerPublicKeyBase64: String,
    val presharedKeyBase64: String? = null,
    val endpoint: String,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val persistentKeepaliveSec: Int? = DEFAULT_KEEPALIVE_SEC,
) {
    val peerPublicKeyPreview: String
        get() = if (peerPublicKeyBase64.length > 14) {
            "${peerPublicKeyBase64.take(8)}…${peerPublicKeyBase64.takeLast(4)}"
        } else {
            peerPublicKeyBase64
        }

    val addressesDisplay: String
        get() = addresses.joinToString(", ")

    val dnsDisplay: String
        get() = dnsServers.joinToString(", ")

    val allowedIpsDisplay: String
        get() = allowedIps.joinToString(", ")

    companion object {
        const val DEFAULT_MTU = 1280
        const val DEFAULT_KEEPALIVE_SEC = 25
    }
}

enum class WireGuardTunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DIRECT_LOCAL,
    ERROR,
}

data class WireGuardRuntimeStatus(
    val state: WireGuardTunnelState = WireGuardTunnelState.DISCONNECTED,
    val mode: WireGuardMode = WireGuardMode.OFF,
    val isRoutingThroughTunnel: Boolean = false,
    val proxyPort: Int? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastHandshakeEpochSec: Long = 0L,
    val activeEndpoint: String? = null,
    val errorMessage: String? = null,
)

data class WireGuardConnectionTestResult(
    val success: Boolean,
    val viaTunnel: Boolean,
    val latencyMs: Long? = null,
    val message: String,
)
