package com.musicplayer.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface CryptoCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Encrypts and decrypts sensitive payloads (Jellyfin auth tokens & credentials) using an
 * AES-256-GCM master key stored in AndroidKeyStore.
 *
 * Wire format: [12-byte GCM IV][ciphertext + 16-byte GCM authentication tag].
 */
@Singleton
class AndroidKeystoreCryptoCipher @Inject constructor() : CryptoCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH_BYTES) { "Unexpected GCM IV length" }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(iv.size + encrypted.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(encrypted, 0, this, iv.size, encrypted.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val minLength = GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)
        require(ciphertext.size > minLength) { "Ciphertext payload is truncated" }
        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val encrypted = ciphertext.copyOfRange(GCM_IV_LENGTH_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = runCatching {
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        }.getOrNull()
        if (existing != null) {
            return existing.secretKey
        }
        runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "music_player_auth_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
