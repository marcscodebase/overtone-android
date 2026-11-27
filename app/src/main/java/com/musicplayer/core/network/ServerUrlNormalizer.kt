package com.musicplayer.core.network

import java.net.URI

/**
 * Normalizes user-entered Jellyfin server addresses supporting HTTPS, HTTP, LAN IPs,
 * Tailscale hostnames, custom ports, and reverse-proxy base paths.
 */
object ServerUrlNormalizer {

    private val IPV4_PRIVATE_OR_CGNAT = Regex(
        "^(127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d+\\.\\d+)$",
    )

    fun normalize(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) {
            val schemePrefix = trimmed.substringBefore("://").lowercase()
            if (schemePrefix != "http" && schemePrefix != "https") return null
            trimmed
        } else {
            val hostCandidate = trimmed
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .let { authority ->
                    if (authority.startsWith("[")) {
                        authority.substringAfter('[').substringBefore(']')
                    } else {
                        authority.substringBefore(':')
                    }
                }
                .lowercase()
            val preferHttp = hostCandidate == "localhost" ||
                hostCandidate == "::1" ||
                IPV4_PRIVATE_OR_CGNAT.matches(hostCandidate)
            val scheme = if (preferHttp) "http://" else "https://"
            scheme + trimmed
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null

        return withScheme.trimEnd('/')
    }
}
