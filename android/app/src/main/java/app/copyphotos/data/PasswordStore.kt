package app.copyphotos.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SMB password, encrypted with an Android Keystore key.
 *
 * @param prefs the preferences file the encrypted password is kept in.
 */
class PasswordStore(private val prefs: SharedPreferences) {

    /** Whether a password is saved. */
    val has: Boolean get() = prefs.contains(KEY_PASSWORD)

    /**
     * Saves the password; an empty one removes it.
     * @param value the new password.
     * @throws java.security.GeneralSecurityException when the Keystore refuses to encrypt it.
     */
    fun set(value: String) {
        prefs.edit().apply {
            if (value.isEmpty()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, encrypt(value))
        }.apply()
    }

    /**
     * The saved password, decrypted into an array the caller wipes.
     * @return the password; empty if none is saved or this device's key can't decrypt it.
     */
    fun read(): CharArray =
        prefs.getString(KEY_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: CharArray(0)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Decrypts into a CharArray, wiping the intermediate buffers. */
    private fun decrypt(encoded: String): CharArray {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        val plain = cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
        return CharArray(chars.remaining()).also { chars.get(it) }.also {
            plain.fill(0)
            if (chars.hasArray()) chars.array().fill('\u0000')
        }
    }

    private companion object {
        const val KEY_PASSWORD = "smb.password"
        const val KEY_ALIAS = "copyphotos.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** GCM's standard IV length; the IV is stored in front of the ciphertext. */
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
