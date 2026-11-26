package com.musicplayer.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.musicplayer.core.model.WireGuardConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedWireGuardConfigStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `encrypts, persists, reads and clears WireGuardConfig cleanly`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tempFolder.root, "wg_store_test.preferences_pb")
        }
        val fakeCipher = object : CryptoCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        val store = EncryptedWireGuardConfigStore(
            dataStore = dataStore,
            cryptoCipher = fakeCipher,
            json = Json { ignoreUnknownKeys = true },
        )

        val config = WireGuardConfig(
            profileName = "FritzBox",
            privateKeyBase64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            addresses = listOf("10.0.0.2/32"),
            dnsServers = listOf("192.168.178.1"),
            peerPublicKeyBase64 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            endpoint = "home.myfritz.net:51820",
            allowedIps = listOf("192.168.178.0/24"),
        )

        assertNull(store.getConfig())
        store.saveConfig(config)
        assertEquals(config, store.getConfig())
        store.clearConfig()
        assertNull(store.getConfig())
    }
}
