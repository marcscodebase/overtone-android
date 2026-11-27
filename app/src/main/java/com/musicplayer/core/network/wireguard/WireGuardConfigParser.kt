package com.musicplayer.core.network.wireguard

import com.musicplayer.core.common.AppError
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.WireGuardConfig
import java.util.Base64

/**
 * Parses and validates standard `wg-quick` / FritzBox / wg-easy / PiVPN `.conf` files and
 * translates them into WireGuard UAPI format for the embedded userspace engine.
 *
 * Security note: Never log raw config text, private keys, preshared keys, or UAPI payloads.
 */
object WireGuardConfigParser {

    private const val WG_KEY_BYTE_LENGTH = 32

    fun parse(
        rawConfig: String,
        defaultProfileName: String = "Home Network",
    ): AppResult<WireGuardConfig> {
        val cleaned = rawConfig.trim()
        if (cleaned.isEmpty()) {
            return AppResult.Error(AppError.Validation("WireGuard configuration is empty"))
        }

        var currentSection = ""
        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        val dnsServers = mutableListOf<String>()
        var mtu = WireGuardConfig.DEFAULT_MTU

        var peerPublicKey: String? = null
        var presharedKey: String? = null
        var endpoint: String? = null
        val allowedIps = mutableListOf<String>()
        var persistentKeepalive: Int? = null

        var seenPeerSection = false

        for (rawLine in cleaned.lines()) {
            val line = stripComments(rawLine).trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                if (currentSection == "peer") {
                    if (seenPeerSection) {
                        // Only parse the first [Peer] section for single-home-network tunnel
                        currentSection = "ignored_peer"
                    } else {
                        seenPeerSection = true
                    }
                }
                continue
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()
            if (value.isEmpty()) continue

            when (currentSection) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += splitCsv(value)
                    "dns" -> dnsServers += splitCsv(value)
                    "mtu" -> value.toIntOrNull()?.let { parsed ->
                        mtu = parsed.coerceIn(1280, 1500)
                    }
                }

                "peer" -> when (key) {
                    "publickey" -> peerPublicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowedIps += splitCsv(value)
                    "persistentkeepalive" -> value.toIntOrNull()?.let { parsed ->
                        persistentKeepalive = parsed.coerceIn(0, 65535)
                    }
                }
            }
        }

        val priv = privateKey
            ?: return AppResult.Error(AppError.Validation("Missing PrivateKey in [Interface]"))
        if (base64KeyToHex(priv) == null) {
            return AppResult.Error(AppError.Validation("Invalid WireGuard PrivateKey (expected 32-byte Base64)"))
        }

        if (addresses.isEmpty()) {
            return AppResult.Error(AppError.Validation("Missing Address in [Interface]"))
        }
        val invalidAddress = addresses.firstOrNull { !isValidIpOrCidr(it) }
        if (invalidAddress != null) {
            return AppResult.Error(AppError.Validation("Invalid Address in [Interface]: $invalidAddress"))
        }

        // Keep valid DNS IP addresses and ignore non-IP search domains (e.g. fritz.box)
        val validDnsServers = dnsServers.filter(::isValidIpOrCidr)

        val pub = peerPublicKey
            ?: return AppResult.Error(AppError.Validation("Missing PublicKey in [Peer]"))
        if (base64KeyToHex(pub) == null) {
            return AppResult.Error(AppError.Validation("Invalid WireGuard Peer PublicKey (expected 32-byte Base64)"))
        }

        if (presharedKey != null && base64KeyToHex(presharedKey) == null) {
            return AppResult.Error(AppError.Validation("Invalid WireGuard PresharedKey (expected 32-byte Base64)"))
        }

        val ep = endpoint
            ?: return AppResult.Error(AppError.Validation("Missing Endpoint in [Peer]"))
        if (splitEndpoint(ep) == null) {
            return AppResult.Error(AppError.Validation("Invalid Endpoint format (expected host:port)"))
        }

        val invalidAllowed = allowedIps.firstOrNull { !isValidIpOrCidr(it) }
        if (invalidAllowed != null) {
            return AppResult.Error(AppError.Validation("Invalid AllowedIPs entry in [Peer]: $invalidAllowed"))
        }

        val normalizedAllowedIps = allowedIps
            .map(::normalizeAllowedIpCidr)
            .ifEmpty { listOf("0.0.0.0/0", "::/0") }

        val effectiveKeepalive = persistentKeepalive ?: WireGuardConfig.DEFAULT_KEEPALIVE_SEC

        return AppResult.Success(
            WireGuardConfig(
                profileName = defaultProfileName.trim().ifBlank { "Home Network" },
                privateKeyBase64 = priv,
                addresses = addresses.distinct(),
                dnsServers = validDnsServers.distinct(),
                mtu = mtu,
                peerPublicKeyBase64 = pub,
                presharedKeyBase64 = presharedKey,
                endpoint = ep,
                allowedIps = normalizedAllowedIps.distinct(),
                persistentKeepaliveSec = effectiveKeepalive.takeIf { it > 0 },
            ),
        )
    }

    /**
     * Builds the WireGuard UAPI string accepted by `device.Device.IpcSet`.
     * [resolvedEndpoint] must contain a numeric IP and port (`1.2.3.4:51820` or `[2001:db8::1]:51820`).
     */
    fun toUapiConfig(
        config: WireGuardConfig,
        resolvedEndpoint: String,
    ): String {
        val privHex = requireNotNull(base64KeyToHex(config.privateKeyBase64)) {
            "Invalid private key"
        }
        val pubHex = requireNotNull(base64KeyToHex(config.peerPublicKeyBase64)) {
            "Invalid peer public key"
        }
        val pskHex = config.presharedKeyBase64?.let { raw ->
            requireNotNull(base64KeyToHex(raw)) { "Invalid preshared key" }
        }

        val cleanEndpoint = resolvedEndpoint.trim()
        require(!cleanEndpoint.contains('\n') && !cleanEndpoint.contains('\r')) {
            "Invalid endpoint characters"
        }

        return buildString {
            append("private_key=").append(privHex).append('\n')
            append("replace_peers=true\n")
            append("public_key=").append(pubHex).append('\n')
            if (pskHex != null) {
                append("preshared_key=").append(pskHex).append('\n')
            }
            append("endpoint=").append(cleanEndpoint).append('\n')
            config.allowedIps.forEach { cidr ->
                val normalized = normalizeAllowedIpCidr(cidr)
                require(!normalized.contains('\n') && !normalized.contains('\r') && isValidIpOrCidr(normalized)) {
                    "Invalid allowed_ip entry"
                }
                append("allowed_ip=").append(normalized).append('\n')
            }
            config.persistentKeepaliveSec?.takeIf { it > 0 }?.let { seconds ->
                append("persistent_keepalive_interval=").append(seconds).append('\n')
            }
        }
    }

    fun base64KeyToHex(keyBase64: String): String? {
        val decoded = runCatching { Base64.getDecoder().decode(keyBase64.trim()) }.getOrNull()
            ?: return null
        if (decoded.size != WG_KEY_BYTE_LENGTH) return null
        val sb = StringBuilder(WG_KEY_BYTE_LENGTH * 2)
        for (b in decoded) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Splits an endpoint like `home.myfritz.net:51820`, `198.51.100.10:51820`, or
     * `[2001:db8::1]:51820` into `(host, port)`.
     */
    fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket <= 1 || closeBracket + 2 >= trimmed.length || trimmed[closeBracket + 1] != ':') {
                return null
            }
            val host = trimmed.substring(1, closeBracket).trim()
            val port = trimmed.substring(closeBracket + 2).trim().toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) null else host to port
        } else {
            val lastColon = trimmed.lastIndexOf(':')
            if (lastColon <= 0 || lastColon == trimmed.lastIndex) return null
            val host = trimmed.substring(0, lastColon).trim()
            val port = trimmed.substring(lastColon + 1).trim().toIntOrNull() ?: return null
            if (host.isEmpty() || host.contains(':') || port !in 1..65535) null else host to port
        }
    }

    fun formatNumericEndpoint(ipAddress: String, port: Int): String =
        if (ipAddress.contains(':') && !ipAddress.startsWith("[")) {
            "[$ipAddress]:$port"
        } else {
            "$ipAddress:$port"
        }

    private fun isValidIpOrCidr(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '\n' || it == '\r' }) return false
        val slashIdx = trimmed.indexOf('/')
        val ipPart = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        val prefixPart = if (slashIdx >= 0) trimmed.substring(slashIdx + 1) else null
        if (ipPart.isEmpty()) return false

        val isIpv4 = isValidIpv4(ipPart)
        val isIpv6 = !isIpv4 && isValidIpv6(ipPart)
        if (!isIpv4 && !isIpv6) return false

        if (prefixPart != null) {
            val prefix = prefixPart.toIntOrNull() ?: return false
            val maxPrefix = if (isIpv4) 32 else 128
            if (prefix !in 0..maxPrefix) return false
        }
        return true
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all { it.isDigit() } &&
                (octet.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }

    private fun isValidIpv6(ip: String): Boolean {
        if (!ip.contains(':') || ip.contains(":::")) return false
        if (ip.indexOf("::") != ip.lastIndexOf("::")) return false
        val cleaned = ip.removePrefix("::").removeSuffix("::")
        if (cleaned.isEmpty()) return true
        val groups = cleaned.split(':').filter { it.isNotEmpty() }
        if (groups.size > 8) return false
        return groups.all { group ->
            group.length in 1..4 && group.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
        }
    }

    private fun normalizeAllowedIpCidr(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains('/')) return trimmed
        return if (trimmed.contains(':')) "$trimmed/128" else "$trimmed/32"
    }

    private fun stripComments(line: String): String {
        val hashIdx = line.indexOf('#')
        val semiIdx = line.indexOf(';')
        val cut = listOf(hashIdx, semiIdx).filter { it >= 0 }.minOrNull()
        return if (cut != null) line.substring(0, cut) else line
    }

    private fun splitCsv(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
