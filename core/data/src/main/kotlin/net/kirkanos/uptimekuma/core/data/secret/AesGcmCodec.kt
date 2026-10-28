package net.kirkanos.uptimekuma.core.data.secret

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM/NoPadding with a random 12-byte IV per value. Output is `base64(iv || ciphertext+tag)`.
 * The entry key is bound as associated data, so a ciphertext cannot be moved to another entry.
 * Works with Android Keystore keys (the provider generates the IV) and plain JCE keys (tests).
 */
internal class AesGcmCodec(
    private val key: () -> SecretKey,
) {
    fun encrypt(
        plaintext: String,
        associatedData: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "Unexpected IV size ${iv.size}" }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    /** `null` when the value cannot be decrypted (tampered, wrong key, wrong entry). */
    fun decrypt(
        encoded: String,
        associatedData: String,
    ): String? =
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size <= IV_SIZE) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
                cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
