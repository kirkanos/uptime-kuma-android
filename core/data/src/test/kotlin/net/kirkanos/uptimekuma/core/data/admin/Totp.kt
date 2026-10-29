package net.kirkanos.uptimekuma.core.data.admin

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s) for the live 2FA check; Kuma uses `notp` with the same defaults. */
internal object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val STEP_SECONDS = 30L
    private const val DIGITS_MOD = 1_000_000
    private const val DIGITS = 6

    fun code(
        base32Secret: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(base32Secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(epochSeconds / STEP_SECONDS).array())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % DIGITS_MOD).toString().padStart(DIGITS, '0')
    }

    fun decodeBase32(text: String): ByteArray {
        val clean = text.trimEnd('=').uppercase().filter { it != ' ' }
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Not base32: $c" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
