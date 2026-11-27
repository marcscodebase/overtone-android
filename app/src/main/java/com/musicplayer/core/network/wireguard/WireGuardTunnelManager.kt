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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Verifies that the target Jellyfin server is directly reachable without WireGuard and
     * (when [expectedServerId] is known) matches the expected Jellyfin server identity.
     */
    fun isExpectedJellyfinServerDirectlyReachable(
        serverUrl: String,
        expectedServerId: String?,
        timeoutMs: Int = 1_500,
    ): Boolean {
        val hostPort = WireGuardTunnelManager.extractHostPort(serverUrl) ?: return false
        return isDirectlyReachable(hostPort.first, hostPort.second, timeoutMs)
    }
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

    override fun isExpectedJellyfinServerDirectlyReachable(
        serverUrl: String,
        expectedServerId: String?,
        timeoutMs: Int,
    ): Boolean = runCatching {
        val endpoint = "${serverUrl.trimEnd('/')}/System/Info/Public"
        val conn = (URL(endpoint).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching false
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val remoteId = json.optString("Id", "").trim()
            val remoteName = json.optString("ServerName", "").trim()
            if (remoteId.isEmpty() && remoteName.isEmpty()) return@runCatching false

            val normalizedExpected = expectedServerId?.trim()?.takeIf {
                it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://")
            }
            if (normalizedExpected != null) {
                remoteId.replace("-", "").equals(normalizedExpected.replace("-", ""), ignoreCase = true)
            } else {
                true
            }
        } finally {
            conn.disconnect()
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
 * the Overtone process without using Android's `VpnService`.
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
    private val lastResolvedIpsByHost = ConcurrentHashMap<String, String>()

    private val _status = MutableStateFlow(WireGuardRuntimeStatus())
    val status: StateFlow<WireGuardRuntimeStatus> = _status.asStateFlow()

    /**
     * Monotonically increments whenever active routing settles onto a new or recovered network
     * path (`CONNECTED` or `DIRECT_LOCAL`), allowing active playback streams to rebind cleanly
     * without hanging on a dead socket from the previous interface.
     */
    private val _routingReadyGeneration = MutableStateFlow(0L)
    val routingReadyGeneration: StateFlow<Long> = _routingReadyGeneration.asStateFlow()

    @Volatile
    private var activeProxy: ActiveWireGuardProxy? = null

    @Volatile
    private var activeConfigFingerprint: String? = null

    @Volatile
    private var activeResolvedEndpoint: String? = null

    @Volatile
    private var activeNetworkHandle: Long = 0L

    private val syncStateLock = Any()
    private var activeSyncCount = 0

    @Volatile
    private var inFlightSync: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingTargetServerUrl: String? = null

    @Volatile
    private var alwaysModeRequiresTunnel: Boolean = false

    @Volatile
    private var initialRoutingEvaluated: Boolean = false

    private val retryJobLock = Any()
    private var errorRetryJob: Job? = null

    init {
        appScope.launch(ioDispatcher) {
            var previousNetworkHandle: Long? = null
            var previousConfig: WireGuardConfig? = null
            combine(
                configStore.config,
                preferencesDataStore.userPreferences.map { it.wireGuardMode }.distinctUntilChanged(),
                networkMonitor.networkState,
                authSessionStore.session.map { it?.let { s -> s.serverUrl to s.serverId } }.distinctUntilChanged(),
            ) { config, mode, netState, serverInfo ->
                RoutingTrigger(
                    config = config,
                    mode = mode,
                    isConnected = netState.isConnected,
                    isMetered = netState.isMetered,
                    isValidated = netState.isValidated,
                    networkHandle = netState.networkHandle,
                    serverUrl = serverInfo?.first,
                    serverId = serverInfo?.second,
                )
            }
                .distinctUntilChanged()
                .collect { trigger ->
                    val networkChanged = previousNetworkHandle != null &&
                        previousNetworkHandle != trigger.networkHandle
                    val configChanged = previousConfig != null && previousConfig != trigger.config
                    previousNetworkHandle = trigger.networkHandle
                    previousConfig = trigger.config

                    // Force a tunnel restart whenever the underlying OS network interface changed
                    // (e.g., Wi-Fi -> Cellular or Wi-Fi A -> Wi-Fi B) so UDP sockets rebind cleanly,
                    // but do NOT restart an already-connected healthy tunnel solely because
                    // NET_CAPABILITY_VALIDATED flipped from false -> true on the same interface.
                    val forceRestart = networkChanged || configChanged

                    syncRoutingInternal(
                        config = trigger.config,
                        mode = trigger.mode,
                        isNetworkConnected = trigger.isConnected,
                        isMetered = trigger.isMetered,
                        networkHandle = trigger.networkHandle,
                        serverUrl = trigger.serverUrl ?: pendingTargetServerUrl,
                        expectedServerId = trigger.serverId,
                        forceRestart = forceRestart,
                    )
                }
        }
    }

    private data class RoutingTrigger(
        val config: WireGuardConfig?,
        val mode: WireGuardMode,
        val isConnected: Boolean,
        val isMetered: Boolean,
        val isValidated: Boolean,
        val networkHandle: Long,
        val serverUrl: String?,
        val serverId: String?,
    )

    fun registerConnectionEvictor(evictor: () -> Unit) {
        connectionEvictors += evictor
    }

    fun activeProxyEndpoint(): ActiveWireGuardProxy? = activeProxy

    fun clearPendingServerUrl() {
        pendingTargetServerUrl = null
    }

    /**
     * Returns true when WireGuard is in ALWAYS mode with a configured profile and no active
     * tunnel proxy is available (fail-closed protection against credential/traffic leaks).
     */
    fun shouldFailClosedForAlwaysMode(): Boolean {
        if (!initialRoutingEvaluated) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(STARTUP_AWAIT_TIMEOUT_MS) {
                        val mode = preferencesDataStore.userPreferences.first().wireGuardMode
                        val config = configStore.getConfig()
                        alwaysModeRequiresTunnel = mode == WireGuardMode.ALWAYS && config != null
                    }
                }
            }
        }
        if (alwaysModeRequiresTunnel && activeProxy == null) {
            awaitReadyProxyIfStarting()
        }
        return alwaysModeRequiresTunnel && activeProxy == null
    }

    /**
     * Suspends briefly if a network / WireGuard transition is currently in progress so callers
     * (such as stream quality/recovery updates) run against the settled route.
     */
    suspend fun awaitRoutingSettled(timeoutMs: Long = STARTUP_AWAIT_TIMEOUT_MS) {
        val deferred = inFlightSync ?: return
        withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
    }

    /**
     * Called on OkHttp IO threads right before choosing a proxy. If a tunnel transition is
     * currently in flight, waits briefly so the very first request after app launch or network
     * switch uses the ready WireGuard tunnel instead of failing prematurely or leaking to a
     * dead proxy port from the previous network interface.
     */
    fun awaitReadyProxyIfStarting(): ActiveWireGuardProxy? {
        val deferred = inFlightSync
        if (deferred != null && !deferred.isCompleted) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(STARTUP_AWAIT_TIMEOUT_MS) {
                        deferred.await()
                    }
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
        val session = authSessionStore.getSession()
        syncRoutingInternal(
            config = config,
            mode = mode,
            isNetworkConnected = net.isConnected,
            isMetered = net.isMetered,
            networkHandle = net.networkHandle,
            serverUrl = serverUrl,
            expectedServerId = session?.takeIf { it.serverUrl == serverUrl }?.serverId,
            forceRestart = false,
        )
    }

    suspend fun syncRoutingNow(forceRestart: Boolean = false) {
        val config = configStore.getConfig()
        val mode = preferencesDataStore.userPreferences.first().wireGuardMode
        val net = networkMonitor.currentNetworkState()
        val session = authSessionStore.getSession()
        val serverUrl = session?.serverUrl ?: pendingTargetServerUrl
        syncRoutingInternal(
            config = config,
            mode = mode,
            isNetworkConnected = net.isConnected,
            isMetered = net.isMetered,
            networkHandle = net.networkHandle,
            serverUrl = serverUrl,
            expectedServerId = session?.serverId,
            forceRestart = forceRestart,
        )
    }

    suspend fun refreshLiveStats() = withContext(ioDispatcher) {
        val proxy = activeProxy ?: return@withContext
        val nativeStatus = nativeEngine.getStatus()
        if (!nativeStatus.running) {
            return@withContext
        }
        _status.update { current ->
            if (current.state != WireGuardTunnelState.CONNECTED) {
                current
            } else {
                current.copy(
                    proxyPort = nativeStatus.proxyPort.takeIf { it > 0 } ?: proxy.port,
                    rxBytes = nativeStatus.rxBytes,
                    txBytes = nativeStatus.txBytes,
                    lastHandshakeEpochSec = nativeStatus.lastHandshakeEpochSec,
                    activeEndpoint = nativeStatus.endpoint?.takeIf { it.isNotBlank() } ?: current.activeEndpoint,
                )
            }
        }
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
            var shouldTearDownAfterTest = false
            var newlyStarted = false
            val startedOk = lifecycleMutex.withLock {
                shouldTearDownAfterTest = mode == WireGuardMode.OFF ||
                    (mode == WireGuardMode.AUTO && _status.value.state == WireGuardTunnelState.DIRECT_LOCAL)
                if (activeProxy == null) {
                    newlyStarted = true
                    startTunnelLocked(
                        config = config,
                        mode = mode,
                        networkHandle = net.networkHandle,
                    )
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

            if (newlyStarted && !shouldTearDownAfterTest) {
                cancelErrorRetry()
                notifyRoutingReady()
            }

            try {
                val targetUrl = targetServerUrl ?: authSessionStore.getSession()?.serverUrl
                val targetHostPort = targetUrl?.let(::extractHostPort)
                if (targetHostPort != null) {
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
            } finally {
                if (shouldTearDownAfterTest) {
                    withContext(NonCancellable) {
                        syncRoutingNow(forceRestart = false)
                    }
                }
            }
        }

    private suspend fun syncRoutingInternal(
        config: WireGuardConfig?,
        mode: WireGuardMode,
        isNetworkConnected: Boolean,
        isMetered: Boolean,
        networkHandle: Long,
        serverUrl: String?,
        expectedServerId: String?,
        forceRestart: Boolean,
    ) = withContext(ioDispatcher) {
        alwaysModeRequiresTunnel = mode == WireGuardMode.ALWAYS && config != null
        initialRoutingEvaluated = true
        val deferred = synchronized(syncStateLock) {
            activeSyncCount += 1
            val existing = inFlightSync
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                CompletableDeferred<Unit>().also { inFlightSync = it }
            }
        }
        var routeBecameReady = false
        var shouldScheduleErrorRetry = false
        try {
            lifecycleMutex.withLock {
                val previousState = _status.value.state
                val previousHandle = activeNetworkHandle

                if (config == null || mode == WireGuardMode.OFF) {
                    cancelErrorRetry(skipCurrentJob = true)
                    val wasRoutingTunnel = activeProxy != null
                    stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DISCONNECTED)
                    activeNetworkHandle = networkHandle
                    if (isNetworkConnected && (wasRoutingTunnel || (previousHandle != 0L && previousHandle != networkHandle))) {
                        routeBecameReady = true
                    }
                    return@withLock
                }

                if (!isNetworkConnected) {
                    cancelErrorRetry(skipCurrentJob = true)
                    stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DISCONNECTED)
                    return@withLock
                }

                if (mode == WireGuardMode.AUTO) {
                    val serverHostPort = serverUrl?.let(::extractHostPort)
                    if (serverUrl != null && serverHostPort != null && shouldProbeDirectly(serverHostPort.first, isMetered)) {
                        val directOk = directProber.isExpectedJellyfinServerDirectlyReachable(
                            serverUrl = serverUrl,
                            expectedServerId = expectedServerId,
                        )
                        if (directOk) {
                            cancelErrorRetry(skipCurrentJob = true)
                            val changedToDirect = previousState != WireGuardTunnelState.DIRECT_LOCAL ||
                                (previousHandle != 0L && previousHandle != networkHandle)
                            stopTunnelLocked(mode = mode, newState = WireGuardTunnelState.DIRECT_LOCAL)
                            activeNetworkHandle = networkHandle
                            if (changedToDirect) {
                                routeBecameReady = true
                            }
                            return@withLock
                        }
                    }
                }

                val fingerprint = computeConfigFingerprint(config)
                val alreadyRunningSame = activeProxy != null &&
                    activeConfigFingerprint == fingerprint &&
                    activeNetworkHandle == networkHandle &&
                    !forceRestart
                if (alreadyRunningSame) {
                    cancelErrorRetry(skipCurrentJob = true)
                    _status.value = _status.value.copy(
                        mode = mode,
                        state = WireGuardTunnelState.CONNECTED,
                        isRoutingThroughTunnel = true,
                        errorMessage = null,
                    )
                    return@withLock
                }

                val started = startTunnelLocked(
                    config = config,
                    mode = mode,
                    networkHandle = networkHandle,
                )
                if (started) {
                    cancelErrorRetry(skipCurrentJob = true)
                    routeBecameReady = true
                } else {
                    shouldScheduleErrorRetry = true
                }
            }
        } finally {
            synchronized(syncStateLock) {
                activeSyncCount = (activeSyncCount - 1).coerceAtLeast(0)
                if (activeSyncCount == 0) {
                    deferred.complete(Unit)
                    if (inFlightSync === deferred) {
                        inFlightSync = null
                    }
                }
            }
        }

        if (routeBecameReady) {
            notifyRoutingReady()
        } else if (shouldScheduleErrorRetry) {
            scheduleErrorRecoveryRetry()
        }
    }

    private suspend fun startTunnelLocked(
        config: WireGuardConfig,
        mode: WireGuardMode,
        networkHandle: Long,
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
        val resolvedIp = resolveEndpointIpWithRetry(endpointHost)
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
        activeNetworkHandle = networkHandle
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

    /**
     * Resolves the WireGuard endpoint hostname with short cooperative retries to survive the
     * brief Android Wi-Fi -> Cellular DNS stabilization window, falling back to the last
     * successfully resolved IP for this host if DNS is still transiently unavailable.
     */
    private suspend fun resolveEndpointIpWithRetry(endpointHost: String): String? {
        for (attemptIdx in DNS_RETRY_DELAYS_MS.indices) {
            val waitMs = DNS_RETRY_DELAYS_MS[attemptIdx]
            if (waitMs > 0L) {
                delay(waitMs)
                if (!networkMonitor.currentNetworkState().isConnected) {
                    return null
                }
            }
            val resolved = directProber.resolveEndpointIp(endpointHost)
            if (!resolved.isNullOrBlank()) {
                lastResolvedIpsByHost[endpointHost.lowercase()] = resolved
                return resolved
            }
        }
        return lastResolvedIpsByHost[endpointHost.lowercase()]
    }

    private fun scheduleErrorRecoveryRetry() {
        synchronized(retryJobLock) {
            if (errorRetryJob?.isActive == true) return
            errorRetryJob = appScope.launch(ioDispatcher) {
                for (delayMs in ERROR_RECOVERY_DELAYS_MS) {
                    delay(delayMs)
                    if (_status.value.state != WireGuardTunnelState.ERROR) return@launch
                    val net = networkMonitor.currentNetworkState()
                    if (!net.isConnected) return@launch
                    val mode = preferencesDataStore.userPreferences.first().wireGuardMode
                    val config = configStore.getConfig()
                    if (config == null || mode == WireGuardMode.OFF) return@launch
                    syncRoutingNow(forceRestart = true)
                    if (_status.value.state != WireGuardTunnelState.ERROR) return@launch
                }
            }
        }
    }

    private suspend fun cancelErrorRetry(skipCurrentJob: Boolean = false) {
        val callerJob = if (skipCurrentJob) currentCoroutineContext()[Job] else null
        synchronized(retryJobLock) {
            val job = errorRetryJob
            if (job != null && job !== callerJob) {
                job.cancel()
                errorRetryJob = null
            } else if (job === callerJob) {
                errorRetryJob = null
            }
        }
    }

    private fun notifyRoutingReady() {
        _routingReadyGeneration.value = _routingReadyGeneration.value + 1L
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

    private fun computeConfigFingerprint(config: WireGuardConfig): String {
        val raw = listOf(
            config.privateKeyBase64,
            config.peerPublicKeyBase64,
            config.presharedKeyBase64.orEmpty(),
            config.endpoint,
            config.addressesDisplay,
            config.dnsDisplay,
            config.allowedIpsDisplay,
            config.mtu.toString(),
            config.persistentKeepaliveSec?.toString().orEmpty(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val STARTUP_AWAIT_TIMEOUT_MS = 6_000L
        private const val TEST_DIAL_TIMEOUT_MS = 6_000
        private val DNS_RETRY_DELAYS_MS = longArrayOf(0L, 350L, 750L, 1_400L)
        private val ERROR_RECOVERY_DELAYS_MS = longArrayOf(3_000L, 6_000L, 12_000L, 20_000L)

        /**
         * On Wi-Fi (unmetered), always try a fast direct probe first so Home LAN works at full
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
            val isIpv6Ula = lower.contains(':') && (lower.startsWith("fc") || lower.startsWith("fd"))
            if (lower.startsWith("10.") || lower.startsWith("192.168.") || isIpv6Ula) {
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
