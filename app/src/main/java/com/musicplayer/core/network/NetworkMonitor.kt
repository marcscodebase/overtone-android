package com.musicplayer.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
}

@Singleton
class ConnectivityManagerNetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val networkState: StateFlow<NetworkState> = callbackFlow {
        fun currentState(): NetworkState {
            val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState(
                isConnected = false,
                isUnmetered = false,
            )
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return NetworkState(isConnected = false, isUnmetered = false)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            return NetworkState(isConnected = hasInternet, isUnmetered = isUnmetered)
        }

        trySend(currentState())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentState())
            }

            override fun onLost(network: Network) {
                trySend(currentState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(currentState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = NetworkState(isConnected = false, isUnmetered = false),
        )
}
