package com.musicplayer.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.musicplayer.core.common.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkState(
    val isConnected: Boolean,
    val isUnmetered: Boolean,
) {
    val isMetered: Boolean
        get() = isConnected && !isUnmetered
}

interface NetworkMonitor {
    val networkState: StateFlow<NetworkState>
    fun currentNetworkState(): NetworkState = networkState.value
}

@Singleton
class ConnectivityManagerNetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun currentNetworkState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState(
            isConnected = false,
            isUnmetered = false,
        )
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkState(isConnected = false, isUnmetered = false)
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) {
            return NetworkState(isConnected = false, isUnmetered = false)
        }
        // Check both ConnectivityManager.isActiveNetworkMetered (which inspects the underlying
        // physical transport even behind a VPN such as Tailscale) and explicit TRANSPORT_CELLULAR
        // (some 5G carriers mark cellular as NOT_METERED even though the user wants mobile-data caps).
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isMetered = connectivityManager.isActiveNetworkMetered || isCellular
        return NetworkState(
            isConnected = true,
            isUnmetered = !isMetered,
        )
    }

    override val networkState: StateFlow<NetworkState> = callbackFlow {
        trySend(currentNetworkState())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentNetworkState())
            }

            override fun onLost(network: Network) {
                trySend(currentNetworkState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(currentNetworkState())
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = currentNetworkState(),
        )
}
