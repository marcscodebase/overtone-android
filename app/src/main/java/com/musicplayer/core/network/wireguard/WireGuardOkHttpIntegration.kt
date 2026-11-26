package com.musicplayer.core.network.wireguard

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Dynamic OkHttp [ProxySelector] that routes all `music-player` HTTP/HTTPS traffic through our
 * in-process loopback WireGuard proxy whenever the tunnel is active, and falls back directly to
 * [Proxy.NO_PROXY] when disconnected or on the direct home LAN.
 */
class WireGuardProxySelector(
    private val tunnelManager: WireGuardTunnelManager,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host?.lowercase().orEmpty()
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            return listOf(Proxy.NO_PROXY)
        }
        val active = tunnelManager.awaitReadyProxyIfStarting() ?: return listOf(Proxy.NO_PROXY)
        return listOf(
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved("127.0.0.1", active.port),
            ),
        )
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Handled at OkHttp / Repository layer
    }
}

/**
 * Supplies the per-session random 256-bit credential to our loopback WireGuard proxy upon a
 * `407 Proxy Authentication Required` challenge.
 */
class WireGuardProxyAuthenticator(
    private val tunnelManager: WireGuardTunnelManager,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("Proxy-Authorization") != null) {
            return null
        }
        val active = tunnelManager.activeProxyEndpoint() ?: return null
        val credential = Credentials.basic("wg", active.token)
        return response.request.newBuilder()
            .header("Proxy-Authorization", credential)
            .build()
    }
}
