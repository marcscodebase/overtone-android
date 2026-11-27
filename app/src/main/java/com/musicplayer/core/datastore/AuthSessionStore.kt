package com.musicplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.musicplayer.core.model.AuthSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

interface AuthSessionStore {
    val session: Flow<AuthSession?>
    suspend fun getSession(): AuthSession?
    suspend fun saveSession(session: AuthSession)
    suspend fun clearSession()
}

@Singleton
class EncryptedAuthSessionStore @Inject constructor(
    @param:AuthDataStore private val dataStore: DataStore<Preferences>,
    private val cryptoCipher: CryptoCipher,
    private val json: Json,
) : AuthSessionStore {

    @Volatile
    private var cachedEncoded: String? = null

    @Volatile
    private var cachedSession: AuthSession? = null

    override val session: Flow<AuthSession?> = dataStore.data.map { prefs ->
        val encoded = prefs[KEY_ENCRYPTED_SESSION]
        if (encoded == null) {
            cachedEncoded = null
            cachedSession = null
            return@map null
        }
        decodeSession(encoded)
    }

    override suspend fun getSession(): AuthSession? = session.first()

    override suspend fun saveSession(session: AuthSession) {
        val plaintext = json.encodeToString(session).toByteArray(Charsets.UTF_8)
        val encrypted = cryptoCipher.encrypt(plaintext)
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        cachedEncoded = encoded
        cachedSession = session
        dataStore.edit { prefs ->
            prefs[KEY_ENCRYPTED_SESSION] = encoded
        }
    }

    override suspend fun clearSession() {
        cachedEncoded = null
        cachedSession = null
        dataStore.edit { prefs ->
            prefs.remove(KEY_ENCRYPTED_SESSION)
        }
    }

    private fun decodeSession(encoded: String): AuthSession? {
        if (cachedEncoded == encoded) {
            return cachedSession
        }
        val decoded = runCatching {
            val encrypted = Base64.getDecoder().decode(encoded)
            val plaintext = cryptoCipher.decrypt(encrypted).toString(Charsets.UTF_8)
            json.decodeFromString<AuthSession>(plaintext)
        }.getOrNull()
        cachedEncoded = encoded
        cachedSession = decoded
        return decoded
    }

    companion object {
        internal val KEY_ENCRYPTED_SESSION = stringPreferencesKey("encrypted_auth_session")
    }
}
