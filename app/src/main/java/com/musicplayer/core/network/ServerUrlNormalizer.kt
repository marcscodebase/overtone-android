package com.musicplayer.core.network

import java.net.URI

/**
 * Normalizes user-entered Jellyfin server addresses supporting HTTPS, HTTP, LAN IPs,
 * Tailscale hostnames, custom ports, and reverse-proxy base paths.
 */
object ServerUrlNormalizer {

    fun normalize(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) {
            val schemePrefix = trimmed.substringBefore("://").lowercase()
            if (schemePrefix != "http" && schemePrefix != "https") return null
            trimmed
        } else {
            val lower = trimmed.lowercase()
            val preferHttp = lower.startsWith("localhost") ||
                lower.startsWith("127.") ||
                lower.startsWith("10.") ||
                lower.startsWith("192.168.") ||
                lower.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) ||
                lower.startsWith("100.")
            val scheme = if (preferHttp) "http://" else "https://"
            scheme + trimmed
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null

        return withScheme.trimEnd('/')
    }
}
