package net.kirkanos.uptimekuma.core.data

import net.kirkanos.uptimekuma.core.data.secret.AesGcmCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class AesGcmCodecTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = AesGcmCodec { key }

    @Test
    fun `round trip with fresh iv per value`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VybmFtZSI6ImFkbWluIn0.sig"
        val a = codec.encrypt(jwt, "kuma.jwt.1")
        val b = codec.encrypt(jwt, "kuma.jwt.1")
        assertNotEquals(a, b)
        assertEquals(jwt, codec.decrypt(a, "kuma.jwt.1"))
        assertEquals(jwt, codec.decrypt(b, "kuma.jwt.1"))
    }

    @Test
    fun `wrong entry, wrong key or tampering yields null`() {
        val sealed = codec.encrypt("secret", "a")
        assertNull(codec.decrypt(sealed, "b"))
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(AesGcmCodec { otherKey }.decrypt(sealed, "a"))
        val bytes = Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        assertNull(codec.decrypt(Base64.getEncoder().encodeToString(bytes), "a"))
        assertNull(codec.decrypt("not base64!", "a"))
        assertNull(codec.decrypt("", "a"))
    }
}
