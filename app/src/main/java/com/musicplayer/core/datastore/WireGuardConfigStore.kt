package com.musicplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.musicplayer.core.model.WireGuardConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

interface WireGuardConfigStore {
    val config: Flow<WireGuardConfig?>
    suspend fun getConfig(): WireGuardConfig?
    suspend fun saveConfig(config: WireGuardConfig)
    suspend fun clearConfig()
}

@Singleton
class EncryptedWireGuardConfigStore @Inject constructor(
    @param:WireGuardDataStore private val dataStore: DataStore<Preferences>,
    private val cryptoCipher: CryptoCipher,
    private val json: Json,
) : WireGuardConfigStore {

    override val config: Flow<WireGuardConfig?> = dataStore.data.map { prefs ->
        val encoded = prefs[KEY_ENCRYPTED_CONFIG] ?: return@map null
        decode(encoded)
    }

    override suspend fun getConfig(): WireGuardConfig? = config.first()

    override suspend fun saveConfig(config: WireGuardConfig) {
        val plaintext = json.encodeToString(config).toByteArray(Charsets.UTF_8)
        val encrypted = cryptoCipher.encrypt(plaintext)
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        dataStore.edit { prefs ->
            prefs[KEY_ENCRYPTED_CONFIG] = encoded
        }
    }

    override suspend fun clearConfig() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ENCRYPTED_CONFIG)
        }
    }

    private fun decode(encoded: String): WireGuardConfig? = runCatching {
        val encrypted = Base64.getDecoder().decode(encoded)
        val plaintext = cryptoCipher.decrypt(encrypted).toString(Charsets.UTF_8)
        json.decodeFromString<WireGuardConfig>(plaintext)
    }.getOrNull()

    companion object {
        internal val KEY_ENCRYPTED_CONFIG = stringPreferencesKey("encrypted_wireguard_config")
    }
}
