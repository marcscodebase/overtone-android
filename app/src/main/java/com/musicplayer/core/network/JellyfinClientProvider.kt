package com.musicplayer.core.network

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds and updates the active single-server Jellyfin [ApiClient].
 * Never logs access tokens or authenticated URLs.
 */
@Singleton
class JellyfinClientProvider @Inject constructor(
    private val jellyfin: Jellyfin,
) {
    @Volatile
    private var activeClient: ApiClient = jellyfin.createApi()

    fun getClient(): ApiClient = activeClient

    @Synchronized
    fun configure(
        baseUrl: String,
        accessToken: String? = null,
    ): ApiClient {
        val normalized = ServerUrlNormalizer.normalize(baseUrl)
            ?: throw IllegalArgumentException("Invalid Jellyfin server URL")
        activeClient = jellyfin.createApi(
            baseUrl = normalized,
            accessToken = accessToken,
        )
        return activeClient
    }

    @Synchronized
    fun clear() {
        activeClient = jellyfin.createApi()
    }
}
