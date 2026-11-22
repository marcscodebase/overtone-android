package com.musicplayer.core.network

import com.musicplayer.core.common.ApplicationScope
import com.musicplayer.core.datastore.UserPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineStatus(
    val manualOfflineMode: Boolean = false,
    val isNetworkConnected: Boolean = true,
) {
    val isOffline: Boolean
        get() = manualOfflineMode || !isNetworkConnected
}

interface OfflineStateProvider {
    val offlineStatus: StateFlow<OfflineStatus>
    suspend fun setManualOfflineMode(enabled: Boolean)
}

@Singleton
class DefaultOfflineStateProvider @Inject constructor(
    private val preferencesDataStore: UserPreferencesDataStore,
    private val networkMonitor: NetworkMonitor,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : OfflineStateProvider {
    override val offlineStatus: StateFlow<OfflineStatus> = combine(
        preferencesDataStore.userPreferences.map { it.offlineModeEnabled }.distinctUntilChanged(),
        networkMonitor.networkState.map { it.isConnected }.distinctUntilChanged(),
    ) { manualOffline, connected ->
        OfflineStatus(
            manualOfflineMode = manualOffline,
            isNetworkConnected = connected,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = OfflineStatus(
                manualOfflineMode = false,
                isNetworkConnected = networkMonitor.networkState.value.isConnected,
            ),
        )

    override suspend fun setManualOfflineMode(enabled: Boolean) {
        preferencesDataStore.setOfflineModeEnabled(enabled)
    }
}
