package net.kirkanos.uptimekuma.core.data.secret

/**
 * Small encrypted key/value store for secrets (Kuma JWT, the push module's ntfy token, ...).
 * Values are encrypted with an Android Keystore AES-GCM key and stored in a private
 * SharedPreferences file (the app's data_extraction_rules exclude the `sharedpref` domain from backups).
 *
 * Inject it via Hilt (bound in `:core:data`).
 */
interface SecretStore {
    suspend fun put(
        key: String,
        value: String,
    )

    /** `null` when absent or no longer decryptable (e.g. Keystore key lost after a restore). */
    suspend fun get(key: String): String?

    suspend fun remove(key: String)
}
