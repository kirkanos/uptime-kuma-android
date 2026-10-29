package net.kirkanos.uptimekuma.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasswordStrengthTest {
    @Test
    fun `mirrors check-password-strength defaults`() {
        assertEquals(PasswordStrength.TOO_WEAK, PasswordStrength.of(""))
        assertEquals(PasswordStrength.TOO_WEAK, PasswordStrength.of("abcdefgh"))
        assertEquals(PasswordStrength.TOO_WEAK, PasswordStrength.of("abc12"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("abc123"))
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("Abc12345"))
        assertEquals(PasswordStrength.MEDIUM, PasswordStrength.of("Abc123!x"))
        assertEquals(
            PasswordStrength.STRONG,
            PasswordStrength.of("devpassword123!".replaceFirstChar { it.uppercase() }),
        )
    }

    @Test
    fun `the dev password is accepted by Kuma`() {
        // devpassword123!: lower case, digits, symbol -> diversity 3, length 15 -> weak (not too weak).
        assertEquals(PasswordStrength.WEAK, PasswordStrength.of("devpassword123!"))
    }
}
