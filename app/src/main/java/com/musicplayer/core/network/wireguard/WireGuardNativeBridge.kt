package com.musicplayer.core.network.wireguard

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class NativeStartResult(
    val success: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val error: String? = null,
)

@Serializable
data class NativeStatusResult(
    val running: Boolean = false,
    val proxyPort: Int = 0,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastHandshakeEpochSec: Long = 0L,
    val endpoint: String? = null,
    val error: String? = null,
)

@Serializable
data class NativeDialTestResult(
    val success: Boolean = false,
    val latencyMs: Long = 0L,
    val error: String? = null,
)

interface WireGuardNativeEngine {
    val isAvailable: Boolean
    fun startTunnel(uapiConfig: String, localAddressesCsv: String, dnsServersCsv: String, mtu: Int): NativeStartResult
    fun stopTunnel(): NativeStatusResult
    fun getStatus(): NativeStatusResult
    fun dialTest(hostPort: String, timeoutMs: Int): NativeDialTestResult
}

@Keep
object WireGuardNativeBridge {

    val isLoaded: Boolean = runCatching {
        System.loadLibrary("wguserspace")
        true
    }.getOrDefault(false)

    @Keep
    @JvmStatic
    external fun nativeStartTunnel(
        uapiConfig: String,
        localAddressesCsv: String,
        dnsServersCsv: String,
        mtu: Int,
    ): String

    @Keep
    @JvmStatic
    external fun nativeStopTunnel(): String

    @Keep
    @JvmStatic
    external fun nativeGetStatus(): String

    @Keep
    @JvmStatic
    external fun nativeDialTest(hostPort: String, timeoutMs: Int): String
}

@Singleton
class JniWireGuardNativeEngine @Inject constructor(
    private val json: Json,
) : WireGuardNativeEngine {

    override val isAvailable: Boolean
        get() = WireGuardNativeBridge.isLoaded

    override fun startTunnel(
        uapiConfig: String,
        localAddressesCsv: String,
        dnsServersCsv: String,
        mtu: Int,
    ): NativeStartResult {
        if (!isAvailable) {
            return NativeStartResult(
                success = false,
                error = "Embedded WireGuard native library is unavailable on this ABI",
            )
        }
        return runCatching {
            val raw = WireGuardNativeBridge.nativeStartTunnel(uapiConfig, localAddressesCsv, dnsServersCsv, mtu)
            json.decodeFromString<NativeStartResult>(raw)
        }.getOrElse { err ->
            NativeStartResult(success = false, error = err.message ?: "Failed to start WireGuard tunnel")
        }
    }

    override fun stopTunnel(): NativeStatusResult {
        if (!isAvailable) return NativeStatusResult(running = false)
        return runCatching {
            val raw = WireGuardNativeBridge.nativeStopTunnel()
            json.decodeFromString<NativeStatusResult>(raw)
        }.getOrElse { err ->
            NativeStatusResult(running = false, error = err.message)
        }
    }

    override fun getStatus(): NativeStatusResult {
        if (!isAvailable) return NativeStatusResult(running = false)
        return runCatching {
            val raw = WireGuardNativeBridge.nativeGetStatus()
            json.decodeFromString<NativeStatusResult>(raw)
        }.getOrElse { err ->
            NativeStatusResult(running = false, error = err.message)
        }
    }

    override fun dialTest(hostPort: String, timeoutMs: Int): NativeDialTestResult {
        if (!isAvailable) {
            return NativeDialTestResult(success = false, error = "WireGuard engine not available")
        }
        return runCatching {
            val raw = WireGuardNativeBridge.nativeDialTest(hostPort, timeoutMs)
            json.decodeFromString<NativeDialTestResult>(raw)
        }.getOrElse { err ->
            NativeDialTestResult(success = false, error = err.message ?: "Dial test failed")
        }
    }
}
