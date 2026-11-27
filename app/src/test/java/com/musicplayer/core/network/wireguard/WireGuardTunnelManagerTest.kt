package com.musicplayer.core.network.wireguard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.datastore.WireGuardConfigStore
import com.musicplayer.core.model.AuthSession
import com.musicplayer.core.model.WireGuardConfig
import com.musicplayer.core.model.WireGuardMode
import com.musicplayer.core.model.WireGuardTunnelState
import com.musicplayer.core.network.NetworkMonitor
import com.musicplayer.core.network.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Proxy
import java.net.URI
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardTunnelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleConfig = WireGuardConfig(
        profileName = "Home",
        privateKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        addresses = listOf("10.8.0.2/32"),
        dnsServers = listOf("192.168.178.1"),
        peerPublicKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
        endpoint = "home.myfritz.net:51820",
        allowedIps = listOf("192.168.178.0/24"),
    )

    @Test
    fun `auto mode uses direct LAN at home and activates tunnel when away`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val prefsStore = UserPreferencesDataStore(
                PreferenceDataStoreFactory.create(scope = appScope) {
                    File(tempFolder.root, "wg_prefs.preferences_pb")
                },
            )
            val configStore = FakeWireGuardConfigStore(sampleConfig)
            val authStore = FakeAuthSessionStore("http://192.168.178.50:8096")
            val networkMonitor = FakeNetworkMonitor(NetworkState(isConnected = true, isUnmetered = true, networkHandle = 101L))
            val prober = FakeDirectProber(directReachable = true, resolvedIp = "203.0.113.5")
            val nativeEngine = FakeNativeEngine()

            val manager = WireGuardTunnelManager(
                nativeEngine = nativeEngine,
                configStore = configStore,
                preferencesDataStore = prefsStore,
                authSessionStore = authStore,
                networkMonitor = networkMonitor,
                directProber = prober,
                appScope = appScope,
                ioDispatcher = dispatcher,
            )

            var evictions = 0
            manager.registerConnectionEvictor { evictions++ }

            // 1. Enable AUTO on home Wi-Fi -> direct LAN bypasses WireGuard
            prefsStore.setWireGuardMode(WireGuardMode.AUTO)
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.DIRECT_LOCAL, manager.status.value.state)
            assertFalse(manager.status.value.isRoutingThroughTunnel)
            assertNull(manager.activeProxyEndpoint())
            assertEquals(0, nativeEngine.startCount)

            // 2. Switch from Home Wi-Fi (101) to Hotel Wi-Fi (202, still unmetered) -> direct probe fails -> WireGuard starts!
            prober.directReachable = false
            networkMonitor.setNetwork(NetworkState(isConnected = true, isUnmetered = true, networkHandle = 202L))
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)
            assertTrue(manager.status.value.isRoutingThroughTunnel)
            assertNotNull(manager.activeProxyEndpoint())
            assertEquals(1, nativeEngine.startCount)
            assertTrue(evictions >= 1)

            // 3. Switch to cellular (303) -> restarts WireGuard on new network interface
            networkMonitor.setNetwork(NetworkState(isConnected = true, isUnmetered = false, networkHandle = 303L))
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)
            assertEquals(2, nativeEngine.startCount)

            // ProxySelector routes remote requests via loopback proxy, skips 127.0.0.1
            val selector = WireGuardProxySelector(manager)
            val remoteProxy = selector.select(URI("http://192.168.178.50:8096/System/Info")).single()
            assertEquals(Proxy.Type.HTTP, remoteProxy.type())
            val loopbackProxy = selector.select(URI("http://127.0.0.1:8080/health")).single()
            assertEquals(Proxy.NO_PROXY, loopbackProxy)

            // 3. Test connection reaches Jellyfin through tunnel
            val testRes = manager.testConnection("http://192.168.178.50:8096")
            assertTrue(testRes.success)
            assertTrue(testRes.viaTunnel)
            assertEquals("192.168.178.50:8096", nativeEngine.lastDialHostPort)

            // 4. Turn OFF -> stops tunnel cleanly
            prefsStore.setWireGuardMode(WireGuardMode.OFF)
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.DISCONNECTED, manager.status.value.state)
            assertNull(manager.activeProxyEndpoint())
            assertEquals(1, nativeEngine.stopCount)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `private host detection skips cellular direct probe for RFC1918 and local domains`() {
        assertFalse(WireGuardTunnelManager.shouldProbeDirectly("192.168.178.50", isMetered = true))
        assertFalse(WireGuardTunnelManager.shouldProbeDirectly("10.0.0.12", isMetered = true))
        assertFalse(WireGuardTunnelManager.shouldProbeDirectly("172.20.1.5", isMetered = true))
        assertFalse(WireGuardTunnelManager.shouldProbeDirectly("jellyfin.fritz.box", isMetered = true))
        assertTrue(WireGuardTunnelManager.shouldProbeDirectly("192.168.178.50", isMetered = false))
        assertTrue(WireGuardTunnelManager.shouldProbeDirectly("music.example.com", isMetered = true))
    }

    @Test
    fun `retries transient DNS failures during wifi to cellular handoff and bumps routing generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val prefsStore = UserPreferencesDataStore(
                PreferenceDataStoreFactory.create(scope = appScope) {
                    File(tempFolder.root, "wg_handoff_prefs.preferences_pb")
                },
            )
            val configStore = FakeWireGuardConfigStore(sampleConfig)
            val authStore = FakeAuthSessionStore("http://192.168.178.50:8096")
            val networkMonitor = FakeNetworkMonitor(
                NetworkState(isConnected = true, isUnmetered = true, networkHandle = 101L, isValidated = true),
            )
            // First 2 DNS lookups fail (simulating Android cellular handoff before DNS is ready), 3rd succeeds
            val prober = FlakyDnsProber(
                directReachable = true,
                dnsResponses = ArrayDeque(listOf(null, null, "203.0.113.99")),
            )
            val nativeEngine = FakeNativeEngine()

            val manager = WireGuardTunnelManager(
                nativeEngine = nativeEngine,
                configStore = configStore,
                preferencesDataStore = prefsStore,
                authSessionStore = authStore,
                networkMonitor = networkMonitor,
                directProber = prober,
                appScope = appScope,
                ioDispatcher = dispatcher,
            )

            prefsStore.setWireGuardMode(WireGuardMode.AUTO)
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.DIRECT_LOCAL, manager.status.value.state)
            val genOnWifi = manager.routingReadyGeneration.value

            // Leave home: Wi-Fi -> Cellular (initially unvalidated, DNS briefly fails twice then resolves)
            prober.directReachable = false
            networkMonitor.setNetwork(
                NetworkState(isConnected = true, isUnmetered = false, networkHandle = 404L, isValidated = false),
            )
            advanceUntilIdle()

            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)
            assertTrue(manager.status.value.isRoutingThroughTunnel)
            assertTrue(manager.routingReadyGeneration.value > genOnWifi)
            assertEquals(1, nativeEngine.startCount)

            // When Android subsequently marks the SAME cellular handle as validated, healthy tunnel does NOT restart
            networkMonitor.setNetwork(
                NetworkState(isConnected = true, isUnmetered = false, networkHandle = 404L, isValidated = true),
            )
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)
            assertEquals(1, nativeEngine.startCount)

            // Return home: Cellular -> Home Wi-Fi -> seamlessly returns to DIRECT_LOCAL and bumps routing generation
            val genOnCellular = manager.routingReadyGeneration.value
            prober.directReachable = true
            networkMonitor.setNetwork(
                NetworkState(isConnected = true, isUnmetered = true, networkHandle = 505L, isValidated = true),
            )
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.DIRECT_LOCAL, manager.status.value.state)
            assertFalse(manager.status.value.isRoutingThroughTunnel)
            assertTrue(manager.routingReadyGeneration.value > genOnCellular)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `falls back to cached endpoint IP when DNS is transiently down on subsequent handoff`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val prefsStore = UserPreferencesDataStore(
                PreferenceDataStoreFactory.create(scope = appScope) {
                    File(tempFolder.root, "wg_cache_prefs.preferences_pb")
                },
            )
            val configStore = FakeWireGuardConfigStore(sampleConfig)
            val authStore = FakeAuthSessionStore("http://192.168.178.50:8096")
            val networkMonitor = FakeNetworkMonitor(
                NetworkState(isConnected = true, isUnmetered = false, networkHandle = 10L, isValidated = true),
            )
            val prober = FlakyDnsProber(
                directReachable = false,
                dnsResponses = ArrayDeque(listOf("203.0.113.42")),
            )
            val nativeEngine = FakeNativeEngine()

            val manager = WireGuardTunnelManager(
                nativeEngine = nativeEngine,
                configStore = configStore,
                preferencesDataStore = prefsStore,
                authSessionStore = authStore,
                networkMonitor = networkMonitor,
                directProber = prober,
                appScope = appScope,
                ioDispatcher = dispatcher,
            )

            prefsStore.setWireGuardMode(WireGuardMode.AUTO)
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)

            // Subsequent network switch while DNS returns null every time -> cached IP keeps tunnel connected!
            networkMonitor.setNetwork(
                NetworkState(isConnected = true, isUnmetered = false, networkHandle = 20L, isValidated = false),
            )
            advanceUntilIdle()
            assertEquals(WireGuardTunnelState.CONNECTED, manager.status.value.state)
            assertEquals(2, nativeEngine.startCount)
        } finally {
            appScope.cancel()
        }
    }

    private class FlakyDnsProber(
        var directReachable: Boolean,
        private val dnsResponses: ArrayDeque<String?>,
    ) : WireGuardDirectProber {
        override fun isDirectlyReachable(host: String, port: Int, timeoutMs: Int): Boolean = directReachable
        override fun resolveEndpointIp(host: String): String? =
            if (dnsResponses.isNotEmpty()) dnsResponses.removeFirst() else null
    }

    private class FakeWireGuardConfigStore(initial: WireGuardConfig?) : WireGuardConfigStore {
        private val flow = MutableStateFlow(initial)
        override val config: Flow<WireGuardConfig?> = flow
        override suspend fun getConfig(): WireGuardConfig? = flow.value
        override suspend fun saveConfig(config: WireGuardConfig) { flow.value = config }
        override suspend fun clearConfig() { flow.value = null }
    }

    private class FakeAuthSessionStore(serverUrl: String) : AuthSessionStore {
        private val flow = MutableStateFlow<AuthSession?>(
            AuthSession(serverUrl, "s1", "Home Jellyfin", "u1", "marc", "tok"),
        )
        override val session: Flow<AuthSession?> = flow
        override suspend fun getSession(): AuthSession? = flow.value
        override suspend fun saveSession(session: AuthSession) { flow.value = session }
        override suspend fun clearSession() { flow.value = null }
    }

    private class FakeNetworkMonitor(initial: NetworkState) : NetworkMonitor {
        private val flow = MutableStateFlow(initial)
        override val networkState: StateFlow<NetworkState> = flow
        override fun currentNetworkState(): NetworkState = flow.value
        fun setNetwork(state: NetworkState) { flow.value = state }
    }

    private class FakeDirectProber(
        var directReachable: Boolean,
        var resolvedIp: String?,
    ) : WireGuardDirectProber {
        override fun isDirectlyReachable(host: String, port: Int, timeoutMs: Int): Boolean = directReachable
        override fun resolveEndpointIp(host: String): String? = resolvedIp
    }

    private class FakeNativeEngine : WireGuardNativeEngine {
        override val isAvailable: Boolean = true
        var startCount = 0
        var stopCount = 0
        var lastDialHostPort: String? = null
        override fun startTunnel(uapiConfig: String, localAddressesCsv: String, dnsServersCsv: String, mtu: Int): NativeStartResult {
            startCount++
            return NativeStartResult(success = true, port = 43210, token = "secret-token-xyz")
        }
        override fun stopTunnel(): NativeStatusResult {
            stopCount++
            return NativeStatusResult(running = false)
        }
        override fun getStatus(): NativeStatusResult = NativeStatusResult(
            running = true,
            proxyPort = 43210,
            rxBytes = 2048,
            txBytes = 1024,
            lastHandshakeEpochSec = System.currentTimeMillis() / 1000L,
        )
        override fun dialTest(hostPort: String, timeoutMs: Int): NativeDialTestResult {
            lastDialHostPort = hostPort
            return NativeDialTestResult(success = true, latencyMs = 19)
        }
    }
}
