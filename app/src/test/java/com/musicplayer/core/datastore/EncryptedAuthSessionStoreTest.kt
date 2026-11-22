package com.musicplayer.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.musicplayer.core.model.AuthSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedAuthSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun createJvmAesGcmCipher(): CryptoCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val random = SecureRandom()
        return object : CryptoCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                val iv = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                val encrypted = cipher.doFinal(plaintext)
                return iv + encrypted
            }

            override fun decrypt(ciphertext: ByteArray): ByteArray {
                val iv = ciphertext.copyOfRange(0, 12)
                val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                return cipher.doFinal(encrypted)
            }
        }
    }

    @Test
    fun encryptsPersistsAndClearsAuthSession() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { tempFolder.newFile("auth.preferences_pb") },
        )
        val store = EncryptedAuthSessionStore(
            dataStore = dataStore,
            cryptoCipher = createJvmAesGcmCipher(),
            json = json,
        )

        assertNull(store.getSession())

        val expected = AuthSession(
            serverUrl = "http://192.168.1.50:8096",
            serverId = "srv-123",
            serverName = "Home Jellyfin",
            userId = "usr-456",
            username = "marc",
            accessToken = "super-secret-token-value",
        )
        store.saveSession(expected)

        val rawStored = dataStore.data.first()[EncryptedAuthSessionStore.KEY_ENCRYPTED_SESSION]
        assertNotNull(rawStored)
        assertFalse(rawStored!!.contains("super-secret-token-value"))

        assertEquals(expected, store.getSession())

        store.clearSession()
        assertNull(store.getSession())
    }

    @Test
    fun returnsNullGracefullyWhenStoredPayloadIsCorrupted() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { tempFolder.newFile("auth_corrupt.preferences_pb") },
        )
        dataStore.edit { prefs ->
            prefs[EncryptedAuthSessionStore.KEY_ENCRYPTED_SESSION] = "not-valid-encrypted-data"
        }
        val store = EncryptedAuthSessionStore(
            dataStore = dataStore,
            cryptoCipher = createJvmAesGcmCipher(),
            json = json,
        )

        assertNull(store.getSession())
    }
}
