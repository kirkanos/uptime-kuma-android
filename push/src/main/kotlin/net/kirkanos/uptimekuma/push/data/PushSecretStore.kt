package net.kirkanos.uptimekuma.push.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage for a bearer token entered during push setup: the ntfy publishing token (unqualified binding) or the
 * FCM relay token ([RelayTokenStore] qualifier). Blocking; call off the main thread.
 */
interface PushTokenStore {
    fun load(): String?

    fun save(token: String)

    fun clear()
}

/**
 * Encrypts the ntfy token with an AES-256-GCM key that lives in the Android Keystore and never leaves it.
 * The ciphertext (`iv || ciphertext+tag`, Base64) is kept in private SharedPreferences.
 *
 * If the key is gone (app data restored from a backup onto another device, keystore reset) decryption fails;
 * the stale value is dropped and [load] returns `null`, so the user is asked for the token again.
 *
 * Kept local to :push for now; meant to be unified with the session token storage of :core:data later.
 *
 * @param entryKey preference key of this token; several instances share the file and the Keystore key.
 */
class PushSecretStore(
    context: Context,
    private val entryKey: String,
) : PushTokenStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): String? {
        val stored = prefs.getString(entryKey, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).decodeToString()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Stored push token cannot be decrypted; discarding it", e)
            clear()
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Stored push token is corrupt; discarding it", e)
            clear()
            null
        }
    }

    @Synchronized
    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "Unexpected GCM IV length ${iv.size}" }
        val encrypted = cipher.doFinal(token.encodeToByteArray())
        prefs.edit { putString(entryKey, Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)) }
    }

    @Synchronized
    override fun clear() {
        prefs.edit { remove(entryKey) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "PushSecretStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "uptimekuma_push_ntfy_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "push_secrets"
        const val NTFY_TOKEN_KEY = "ntfy_token"
        const val RELAY_TOKEN_KEY = "relay_token"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
