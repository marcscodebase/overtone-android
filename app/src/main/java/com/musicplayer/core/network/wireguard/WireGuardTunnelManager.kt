package com.musicplayer.core.network.wireguard

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.ApplicationScope
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.datastore.WireGuardConfigStore
import com.musicplayer.core.model.WireGuardConfig
import com.musicplayer.core.model.WireGuardConnectionTestResult
import com.musicplayer.core.model.WireGuardMode
import com.musicplayer.core.model.WireGuardRuntimeStatus
import com.musicplayer.core.model.WireGuardTunnelState
import com.musicplayer.core.network.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveWireGuardProxy(
    val port: Int,
    val token: String,
)

interface WireGuardDirectProber {
    fun isDirectlyReachable(host: String, port: Int, timeoutMs: Int = 1_100): Boolean
    fun resolveEndpointIp(host: String): String?
}

@Singleton
class DefaultWireGuardDirectProber @Inject constructor() : WireGuardDirectProber {

    override fun isDirectlyReachable(host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)

    override fun resolveEndpointIp(host: String): String? = runCatching {
        val addresses = InetAddress.getAllByName(host)
        val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        preferred?.hostAddress
    }.getOrNull()
}

/**
 * Manages the embedded userspace WireGuard tunnel (`wireguard-go` + `netstack`) strictly inside
 * the `music-player` process without using Android's `VpnService`.
 */
@Singleton
class WireGuardTunnelManager @Inject constructor(
    private val nativeEngine: WireGuardNativeEngine,
    private val configStore: WireGuardConfigStore,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val authSessionStore: AuthSessionStore,
    private val networkMonitor: NetworkMonitor,
    private val directProber: WireGuardDirectProber,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val lifecycleMutex = Mutex()
    private val connectionEvictors = CopyOnWriteArrayList<() -> Unit>()

    private val _status = MutableStateFlow(WireGuardRuntimeStatus())
    val status: StateFlow<WireGuardRuntimeStatus> = _status.asStateFlow()

    @Volatile
    private var activeProxy: ActiveWireGuardProxy? = null

    @Volatile
    private var activeConfigFingerprint: String? = null

    @Volatile
    private var activeResolvedEndpoint: String? = null

    @Volatile
    private var inFlightSync: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingTargetServerUrl: String? = null

    init {
        appScope.launch(ioDispatcher) {
            combine(
                configStore.config,
                preferencesDataStore.userPreferences.map { it.wireGuardMode }.distinctUntilChanged(),
                networkMonitor.networkState,
                authSessionStore.session.map { it?.serverUrl }.distinctUntilChanged(),
            ) { config, mode, netState, serverUrl ->
                RoutingTrigger(
                    config = config,
                    mode = mode,
                    isConnected = netState.isConnected,
                    isMetered = netState.isMetered,
                    networkHandle = netState.networkHandle,
                    serverUrl = serverUrl,
                )
            }
                .distinctUntilChanged()
                .collect { trigger ->
                    syncRoutingInternal(
                        config = trigger.config,
                        mode = trigger.mode,
                        isNetworkConnected = trigger.isConnected,
                        isMetered = trigger.isMetered,
                        serverUrl = trigger.serverUrl ?: pendingTargetServerUrl,
                        forceRestart = true,
                    )
                }
        }
    }

    private data class RoutingTrigger(
        val config: WireGuardConfig?,
        val mode: WireGuardMode,
        val isConnected: Boolean,
        val isMetered: Boolean,
        val networkHandle: Long,
        val serverUrl: String?,
    )

    fun registerConnectionEvictor(evictor: () -> Unit) {
        connectionEvictors += evictor
    }

    fun activeProxyEndpoint(): ActiveWireGuardProxy? = activeProxy

    /**
     * Called on OkHttp IO threads right before choosing a proxy. If a tunnel transition is
     * currently in flight, waits briefly so the very first request after app launch or network
     * switch uses the ready WireGuard tunnel instead of failing prematurely.
     */
    fun awaitReadyProxyIfStarting(): ActiveWireGuardProxy? {
        val current = activeProxy
        if (current != null) return current
        val deferred = inFlightSync ?: return null
        runCatching {
            runBlocking {
                withTimeoutOrNull(STARTUP_AWAIT_TIMEOUT_MS) {
                    deferred.await()
                }
            }
        }
        return activeProxy
    }

    /**
     * Ensures routing is evaluated for a specific server URL (especially useful on the Login
     * screen before an [AuthSession] has been saved).
     */
    suspend fun prepareForServerUrl(serverUrl: String) {
        pendingTargetServerUrl = serverUrl
        val config = configStore.getConfig()
        val mode = preferencesDataStore.userPreferences.first().wireGuardMode
        val net = networkMonitor.currentNetworkState()
        syncRoutingInternal(
            config = config,
            mode = mode,
            isNetworkConnected = net.isConnected,
            isMetered = net.isMetered,
            serverUrl = serverUrl,
            forceRestart = false,
        )
    }

    suspend fun syncRoutingNow(forceRestart: Boolean = false) {
        val config = configStore.getConfig()
        val mode = preferencesDataStore.userPreferences.first().wireGuardMode
        val net = networkMonitor.currentNetworkState()
        val serverUrl = authSessionStore.getSession()?.serverUrl ?: pendingTargetServerUrl
        syncRoutingInternal(
            config = config,
            mode = mode,
            isNetworkConnected = net.isConnected,
            isMetered = net.isMetered,
            serverUrl = serverUrl,
            forceRestart = forceRestart,
        )
    }

    suspend fun refreshLiveStats() = withContext(ioDispatcher) {
        val proxy = activeProxy ?: return@withContext
        val nativeStatus = nativeEngine.getStatus()
        if (!nativeStatus.running) {
            return@withContext
        }
        val current = _status.value
        _status.value = current.copy(
            proxyPort = nativeStatus.proxyPort.takeIf { it > 0 } ?: proxy.port,
            rxBytes = nativeStatus.rxBytes,
            txBytes = nativeStatus.txBytes,
            lastHandshakeEpochSec = nativeStatus.lastHandshakeEpochSec,
            activeEndpoint = nativeStatus.endpoint?.takeIf { it.isNotBlank() } ?: current.activeEndpoint,
        )
    }

    /**
     * Runs an explicit end-to-end connectivity test using the saved WireGuard profile against
     * either the configured Jellyfin server (if available) or the WireGuard endpoint itself.
     */
    suspend fun testConnection(targetServerUrl: String?): WireGuardConnectionTestResult =
        withContext(ioDispatcher) {
            val config = configStore.getConfig()
                ?: return@withContext WireGuardConnectionTestResult(
                    success = false,
                    viaTunnel = false,
                    message = "No WireGuard configuration imported",
                )

            if (!nativeEngine.isAvailable) {
                return@withContext WireGuardConnectionTestResult(
                    success = false,
                    viaTunnel = false,
                    message = "Embedded WireGuard native library is not available on this device ABI",
                )
            }

            val net = networkMonitor.currentNetworkState()
            if (!net.isConnected) {
                return@withContext WireGuardConnectionTestResult(
                    success = false,
                    viaTunnel = false,
                    message = "Device has no internet connection",
                )
            }

            val mode = preferencesDataStore.userPreferences.first().wireGuardMode
            val shouldTearDownAfterTest = mode == WireGuardMode.OFF ||
                (mode == WireGuardMode.AUTO && _status.value.state == WireGuardTunnelState.DIRECT_LOCAL)

            val startedOk = lifecycleMutex.withLock {
                if (activeProxy == null) {
                    startTunnelLocked(config = config, mode = mode)
                } else {
                    true
                }
            }

            if (!startedOk) {
                return@withContext WireGuardConnectionTestResult(
                    success = false,
                    viaTunnel = true,
                    message = _status.value.errorMessage ?: "Failed to start WireGuard tunnel",
                )
            }

            val targetUrl = targetServerUrl ?: authSessionStore.getSession()?.serverUrl
            val targetHostPort = targetUrl?.let(::extractHostPort)
            val result = if (targetHostPort != null) {
                val (host, port) = targetHostPort
                val formattedTarget = WireGuardConfigParser.formatNumericEndpoint(host, port)
                val dial = nativeEngine.dialTest(formattedTarget, TEST_DIAL_TIMEOUT_MS)
                refreshLiveStats()
                if (dial.success) {
                    WireGuardConnectionTestResult(
                        success = true,
                        viaTunnel = true,
                        latencyMs = dial.latencyMs,
                        message = "Reached $host:$port via WireGuard (${dial.latencyMs} ms)",
                    )
                } else {
                    WireGuardConnectionTestResult(
                        success = false,
                        viaTunnel = true,
                        latencyMs = dial.latencyMs,
                        message = "Tunnel started, but could not reach $host:$port: ${dial.error ?: "timeout"}",
                    )
                }
            } else {
                // No Jellyfin URL saved yet — probe DNS server over the WireGuard tunnel
                val dnsHost = config.dnsServers.firstOrNull()?.substringBefore('/') ?: "1.1.1.1"
                val dial = nativeEngine.dialTest(WireGuardConfigParser.formatNumericEndpoint(dnsHost, 53), TEST_DIAL_TIMEOUT_MS)
                refreshLiveStats()
                val handshakeSec = _status.value.lastHandshakeEpochSec
                if (dial.success || handshakeSec > 0L) {
                    WireGuardConnectionTestResult(
                        success = true,
                        viaTunnel = true,
                        latencyMs = dial.latencyMs,
                        message = "WireGuard tunnel active (${dial.latencyMs} ms)",
                    )
                } else {
                    WireGuardConnectionTestResult(
                        success = false,
                        viaTunnel = true,
                        message = "No handshake with ${config.endpoint} yet. Check UDP port & peer keys.",
                    )
                }
            }

            if (shouldTearDownAfterTest) {
                syncRoutingNow(forceRestart = false)
            }

            result
        }

    private suspend fun syncRoutingInternal(
        config: WireGuardConfig?,
        mode: WireGuardMode,
        isNetworkConnected: Boolean,
        isMetered: Boolean,
        serverUrl: String?,
        forceRestart: Boolean,
    ) = withContext(ioDispatcher) {
        val deferred = CompletableDeferred<Unit>()
        inFlightSync = deferred
        try {
            lifecycleMutex.withLock {
                if (config == null || mode == WireGuardMode.OFF) {
                    stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DISCONNECTED)
                    return@withLock
                }

                if (!isNetworkConnected) {
                    stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DISCONNECTED)
                    return@withLock
                }

                if (mode == WireGuardMode.AUTO) {
                    val serverHostPort = serverUrl?.let(::extractHostPort)
                    if (serverHostPort != null && shouldProbeDirectly(serverHostPort.first, isMetered)) {
                        val directOk = directProber.isDirectlyReachable(
                            host = serverHostPort.first,
                            port = serverHostPort.second,
                        )
                        if (directOk) {
                            stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DIRECT_LOCAL)
                            return@withLock
                        }
                    }
                }

                val fingerprint = computeConfigFingerprint(config)
                val alreadyRunningSame = activeProxy != null &&
                    activeConfigFingerprint == fingerprint &&
                    !forceRestart
                if (alreadyRunningSame) {
                    _status.value = _status.value.copy(
                        mode = mode,
                        state = WireGuardTunnelState.CONNECTED,
                        isRoutingThroughTunnel = true,
                    )
                    return@withLock
                }

                startTunnelLocked(config = config, mode = mode)
            }
        } finally {
            deferred.complete(Unit)
            if (inFlightSync === deferred) {
                inFlightSync = null
            }
        }
    }

    private fun startTunnelLocked(
        config: WireGuardConfig,
        mode: WireGuardMode,
    ): Boolean {
        _status.value = _status.value.copy(
            mode = mode,
            state = WireGuardTunnelState.CONNECTING,
            errorMessage = null,
        )

        val endpointParts = WireGuardConfigParser.splitEndpoint(config.endpoint)
        if (endpointParts == null) {
            stopTunnelLocked(
                mode = mode,
                newState = WireGuardTunnelState.ERROR,
                errorMessage = "Invalid WireGuard endpoint: ${config.endpoint}",
            )
            return false
        }

        val (endpointHost, endpointPort) = endpointParts
        val resolvedIp = directProber.resolveEndpointIp(endpointHost)
        if (resolvedIp == null) {
            stopTunnelLocked(
                mode = mode,
                newState = WireGuardTunnelState.ERROR,
                errorMessage = "Could not resolve WireGuard host: $endpointHost",
            )
            return false
        }

        val numericEndpoint = WireGuardConfigParser.formatNumericEndpoint(resolvedIp, endpointPort)
        val uapi = WireGuardConfigParser.toUapiConfig(config = config, resolvedEndpoint = numericEndpoint)

        val startResult = nativeEngine.startTunnel(
            uapiConfig = uapi,
            localAddressesCsv = config.addresses.joinToString(","),
            dnsServersCsv = config.dnsServers.joinToString(","),
            mtu = config.mtu,
        )

        if (!startResult.success || startResult.port <= 0 || startResult.token.isBlank()) {
            activeProxy = null
            activeConfigFingerprint = null
            activeResolvedEndpoint = null
            _status.value = WireGuardRuntimeStatus(
                state = WireGuardTunnelState.ERROR,
                mode = mode,
                isRoutingThroughTunnel = false,
                errorMessage = startResult.error ?: "Failed to start embedded WireGuard tunnel",
            )
            evictConnections()
            return false
        }

        activeProxy = ActiveWireGuardProxy(port = startResult.port, token = startResult.token)
        activeConfigFingerprint = computeConfigFingerprint(config)
        activeResolvedEndpoint = numericEndpoint
        _status.value = WireGuardRuntimeStatus(
            state = WireGuardTunnelState.CONNECTED,
            mode = mode,
            isRoutingThroughTunnel = true,
            proxyPort = startResult.port,
            activeEndpoint = config.endpoint,
        )
        evictConnections()
        return true
    }

    private fun stopTunnelLocked(
        mode: WireGuardMode,
        newState: WireGuardTunnelState,
        errorMessage: String? = null,
    ) {
        val wasActive = activeProxy != null
        activeProxy = null
        activeConfigFingerprint = null
        activeResolvedEndpoint = null
        if (wasActive) {
            nativeEngine.stopTunnel()
        }
        _status.value = WireGuardRuntimeStatus(
            state = newState,
            mode = mode,
            isRoutingThroughTunnel = false,
            errorMessage = errorMessage,
        )
        if (wasActive) {
            evictConnections()
        }
    }

    private fun evictConnections() {
        connectionEvictors.forEach { evictor ->
            runCatching { evictor() }
        }
    }

    private fun computeConfigFingerprint(config: WireGuardConfig): String =
        "${config.peerPublicKeyBase64}|${config.endpoint}|${config.addressesDisplay}|${config.dnsDisplay}|${config.allowedIpsDisplay}|${config.mtu}"

    companion object {
        private const val STARTUP_AWAIT_TIMEOUT_MS = 4_000L
        private const val TEST_DIAL_TIMEOUT_MS = 6_000

        /**
         * On Wi-Fi (unmetered), always try a fast direct TCP probe first so Home LAN works at full
         * native speed. On cellular (metered), skip direct probing for private RFC1918/ULA/local
         * addresses since they are never directly reachable over mobile data.
         */
        internal fun shouldProbeDirectly(host: String, isMetered: Boolean): Boolean {
            if (!isMetered) return true
            return !isPrivateOrLocalHost(host)
        }

        internal fun isPrivateOrLocalHost(host: String): Boolean {
            val lower = host.trim().lowercase().trim('[', ']')
            if (lower.endsWith(".local") ||
                lower.endsWith(".lan") ||
                lower.endsWith(".home.arpa") ||
                lower.endsWith(".fritz.box") ||
                lower.endsWith(".internal") ||
                lower.endsWith(".localdomain")
            ) {
                return true
            }
            if (lower.startsWith("10.") || lower.startsWith("192.168.") || lower.startsWith("fc") || lower.startsWith("fd")) {
                return true
            }
            if (lower.startsWith("172.")) {
                val secondOctet = lower.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) return true
            }
            return false
        }

        internal fun extractHostPort(rawUrl: String): Pair<String, Int>? {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = when {
                uri.port in 1..65535 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            return host to port
        }
    }
}
