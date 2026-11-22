package com.musicplayer.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlNormalizerTest {

    @Test
    fun preservesExplicitHttpsAndStripsTrailingSlash() {
        assertEquals(
            "https://music.example.com",
            ServerUrlNormalizer.normalize("  https://music.example.com/  "),
        )
    }

    @Test
    fun preservesExplicitHttpWithPortAndSubpath() {
        assertEquals(
            "http://192.168.1.42:8096/jellyfin",
            ServerUrlNormalizer.normalize("http://192.168.1.42:8096/jellyfin/"),
        )
    }

    @Test
    fun defaultsLanIpToHttp() {
        assertEquals(
            "http://192.168.1.10:8096",
            ServerUrlNormalizer.normalize("192.168.1.10:8096"),
        )
        assertEquals(
            "http://100.64.0.12:8096",
            ServerUrlNormalizer.normalize("100.64.0.12:8096"),
        )
    }

    @Test
    fun defaultsPublicAndTailscaleHostnameToHttps() {
        assertEquals(
            "https://jellyfin.tailnet.ts.net",
            ServerUrlNormalizer.normalize("jellyfin.tailnet.ts.net"),
        )
    }

    @Test
    fun rejectsBlankAndUnsupportedSchemes() {
        assertNull(ServerUrlNormalizer.normalize("   "))
        assertNull(ServerUrlNormalizer.normalize("ftp://music.example.com"))
    }
}
