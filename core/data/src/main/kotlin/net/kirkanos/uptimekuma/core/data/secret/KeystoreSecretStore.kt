package net.kirkanos.uptimekuma.core.data.secret

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kirkanos.uptimekuma.core.data.di.DataDispatcher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SecretStore] backed by an AES-256-GCM key in the Android Keystore; ciphertexts are kept in a
 * private SharedPreferences file (`kuma_secrets`). The key never leaves the Keystore; after a
 * backup restore to another device the values simply read as `null` and are dropped.
 */
@Singleton
internal class KeystoreSecretStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @DataDispatcher private val dispatcher: CoroutineDispatcher,
    ) : SecretStore {
        private val mutex = Mutex()
        private val codec = AesGcmCodec { keystoreKey() }
        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override suspend fun put(
            key: String,
            value: String,
        ) = io { prefs.edit().putString(key, codec.encrypt(value, key)).apply() }

        override suspend fun get(key: String): String? =
            io {
                val stored = prefs.getString(key, null) ?: return@io null
                codec.decrypt(stored, key) ?: run {
                    prefs.edit().remove(key).apply()
                    null
                }
            }

        override suspend fun remove(key: String) = io { prefs.edit().remove(key).apply() }

        private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { mutex.withLock { block() } }

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "uptimekuma.secret-store.v1"
            const val PREFS_NAME = "kuma_secrets"
            const val KEY_SIZE_BITS = 256
        }
    }
