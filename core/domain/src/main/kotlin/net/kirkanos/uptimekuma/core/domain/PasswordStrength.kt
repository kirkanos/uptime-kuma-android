package net.kirkanos.uptimekuma.core.domain

/**
 * Mirrors `check-password-strength` (default options), which Kuma uses to reject `TOO_WEAK` passwords on
 * `setup` and `changePassword` (`passwordTooWeak`).
 */
enum class PasswordStrength {
    TOO_WEAK,
    WEAK,
    MEDIUM,
    STRONG,
    ;

    companion object {
        private const val WEAK_LENGTH = 6
        private const val MEDIUM_LENGTH = 8
        private const val STRONG_LENGTH = 10
        private const val WEAK_DIVERSITY = 2
        private const val FULL_DIVERSITY = 4
        private const val SYMBOLS = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        fun of(password: String): PasswordStrength {
            val diversity =
                listOf(
                    password.any { it in 'a'..'z' },
                    password.any { it in 'A'..'Z' },
                    password.any { it in '0'..'9' },
                    password.any { it in SYMBOLS },
                ).count { it }
            val length = password.length
            return when {
                diversity >= FULL_DIVERSITY && length >= STRONG_LENGTH -> STRONG
                diversity >= FULL_DIVERSITY && length >= MEDIUM_LENGTH -> MEDIUM
                diversity >= WEAK_DIVERSITY && length >= WEAK_LENGTH -> WEAK
                else -> TOO_WEAK
            }
        }
    }
}
