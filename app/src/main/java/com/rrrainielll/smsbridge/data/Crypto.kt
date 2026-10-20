package com.rrrainielll.smsbridge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 *
 * The key is generated inside the Keystore (hardware-backed where available) and never leaves it,
 * so values written to SharedPreferences / SQLite are useless if the app data is copied off the device.
 * The key is intentionally *not* tied to the device being unlocked, because SMS can arrive while the
 * phone is locked and must still be queued.
 */
object Crypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "smsbridge_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns base64(iv || ciphertext). An empty input yields an empty string. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Returns the plaintext, or null if the value cannot be decrypted (e.g. key was lost). */
    fun decrypt(blob: String): String? {
        if (blob.isEmpty()) return ""
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
