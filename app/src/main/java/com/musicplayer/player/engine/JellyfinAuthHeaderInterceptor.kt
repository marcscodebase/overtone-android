package com.musicplayer.player.engine

import com.musicplayer.core.network.JellyfinClientProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamically injects Jellyfin MediaBrowser Authorization & token headers onto playback
 * and download requests whose exact origin (scheme, host, port) matches the configured
 * Jellyfin server, and strips them on any cross-origin request or redirect.
 */
@Singleton
class JellyfinAuthHeaderInterceptor @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val api = clientProvider.getClient()
        val baseUrl = api.baseUrl?.toHttpUrlOrNull()
        val token = api.accessToken

        val isSameOrigin = baseUrl != null &&
            original.url.scheme.equals(baseUrl.scheme, ignoreCase = true) &&
            original.url.host.equals(baseUrl.host, ignoreCase = true) &&
            original.url.port == baseUrl.port

        if (!isSameOrigin || token.isNullOrBlank()) {
            val sanitized = original.newBuilder()
                .removeHeader("Authorization")
                .removeHeader("X-Emby-Token")
                .build()
            return chain.proceed(sanitized)
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
