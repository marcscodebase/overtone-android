package com.musicplayer.core.network.wireguard

import com.musicplayer.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WireGuardConfigParserTest {

    private val validPrivateKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val validPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 40).toByte() })
    private val validPsk = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 90).toByte() })

    @Test
    fun `parses standard wg-quick conf with comments, preshared key and multiple CIDRs`() {
        val raw = """
            # Exported from FritzBox / wg-easy
            [Interface]
            PrivateKey = $validPrivateKey
            Address = 10.8.0.2/24, fd42:42:42::2/64
            DNS = 192.168.178.1, 1.1.1.1
            MTU = 1380

            [Peer]
            PublicKey = $validPublicKey
            PresharedKey = $validPsk
            Endpoint = home.myfritz.net:51820 ; dynamic dns
            AllowedIPs = 192.168.178.0/24, 10.8.0.0/24
            PersistentKeepalive = 25
        """.trimIndent()

        val result = WireGuardConfigParser.parse(raw, defaultProfileName = "FritzBox Home")
        assertTrue(result is AppResult.Success)
        val cfg = (result as AppResult.Success).data

        assertEquals("FritzBox Home", cfg.profileName)
        assertEquals(validPrivateKey, cfg.privateKeyBase64)
        assertEquals(listOf("10.8.0.2/24", "fd42:42:42::2/64"), cfg.addresses)
        assertEquals(listOf("192.168.178.1", "1.1.1.1"), cfg.dnsServers)
        assertEquals(1380, cfg.mtu)
        assertEquals(validPublicKey, cfg.peerPublicKeyBase64)
        assertEquals(validPsk, cfg.presharedKeyBase64)
        assertEquals("home.myfritz.net:51820", cfg.endpoint)
        assertEquals(listOf("192.168.178.0/24", "10.8.0.0/24"), cfg.allowedIps)
        assertEquals(25, cfg.persistentKeepaliveSec)

        val uapi = WireGuardConfigParser.toUapiConfig(cfg, resolvedEndpoint = "203.0.113.10:51820")
        assertTrue(uapi.contains("private_key=${WireGuardConfigParser.base64KeyToHex(validPrivateKey)}"))
        assertTrue(uapi.contains("public_key=${WireGuardConfigParser.base64KeyToHex(validPublicKey)}"))
        assertTrue(uapi.contains("preshared_key=${WireGuardConfigParser.base64KeyToHex(validPsk)}"))
        assertTrue(uapi.contains("endpoint=203.0.113.10:51820"))
        assertTrue(uapi.contains("allowed_ip=192.168.178.0/24"))
        assertTrue(uapi.contains("persistent_keepalive_interval=25"))
    }

    @Test
    fun `handles IPv6 bracketed endpoints and rejects invalid keys`() {
        assertEquals("2001:db8::1" to 51820, WireGuardConfigParser.splitEndpoint("[2001:db8::1]:51820"))
        assertEquals("[2001:db8::1]:51820", WireGuardConfigParser.formatNumericEndpoint("2001:db8::1", 51820))
        assertNull(WireGuardConfigParser.splitEndpoint("missing-port.example.com"))
        assertNull(WireGuardConfigParser.base64KeyToHex("not-a-valid-32-byte-key"))

        val invalidConf = """
            [Interface]
            PrivateKey = shortkey
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $validPublicKey
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        val parsed = WireGuardConfigParser.parse(invalidConf)
        assertTrue(parsed is AppResult.Error)
        assertNotNull((parsed as AppResult.Error).error.message)
    }
}
