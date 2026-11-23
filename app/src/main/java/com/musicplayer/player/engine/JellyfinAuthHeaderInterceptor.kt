package com.musicplayer.player.engine

import com.musicplayer.core.network.JellyfinClientProvider
import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamically injects Jellyfin MediaBrowser Authorization & token headers onto playback
 * and download requests so access tokens never appear in query URLs or logs.
 */
@Singleton
class JellyfinAuthHeaderInterceptor @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val api = clientProvider.getClient()
        val token = api.accessToken
        if (token.isNullOrBlank()) {
            return chain.proceed(original)
        }

        val authHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = token,
        )

        val authorized = original.newBuilder()
            .header("Authorization", authHeader)
            .header("X-Emby-Token", token)
            .build()
        return chain.proceed(authorized)
    }
}
