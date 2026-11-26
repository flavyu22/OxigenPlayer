// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted key/value store backed directly by Android Keystore AES-GCM. */
internal class AndroidKeystoreSecretStore private constructor(
    private val context: Context,
    private val key: SecretKey,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getString(name: String): String? = synchronized(lock) {
        val encoded = preferences.getString(name, null) ?: return@synchronized null
        runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > HEADER_SIZE)
            val buffer = ByteBuffer.wrap(payload)
            require(buffer.get() == FORMAT_VERSION)
            val ivSize = buffer.get().toInt() and 0xff
            require(ivSize in 12..16 && buffer.remaining() > ivSize)
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(name))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit { remove(name) }
            null
        }
    }

    fun putString(name: String, value: String) = synchronized(lock) {
        if (value.isBlank()) {
            preferences.edit { remove(name) }
            return@synchronized
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(name))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        preferences.edit { putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    fun remove(name: String) = synchronized(lock) {
        preferences.edit { remove(name) }
    }

    private fun aad(name: String): ByteArray =
        "${context.packageName}:$name".toByteArray(Charsets.UTF_8)

    companion object {
        private const val PREFERENCES_NAME = "oxigen_secure_v2"
        private const val KEY_ALIAS = "oxigenplayer.aes-gcm.v2"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
        private const val HEADER_SIZE = 14

        fun create(context: Context): AndroidKeystoreSecretStore? = runCatching {
            val appContext = context.applicationContext
            AndroidKeystoreSecretStore(appContext, getOrCreateKey())
        }.getOrNull()

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }
    }
}
