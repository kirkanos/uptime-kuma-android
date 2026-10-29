package net.kirkanos.uptimekuma.core.domain.notification

/**
 * A Vue `v-if` condition of a provider form, e.g. `httpMethod === 'post' && webhookContentType == 'custom'`.
 * Supports identifiers (JS truthiness), string/number/boolean literals, `!`, `==`/`===`/`!=`/`!==`,
 * `&&`, `||` (with JS precedence) and parentheses. Comparisons are lenient (string forms, ignoring case),
 * because stored configs mix types (e.g. `"5"` vs `5`) and case (`httpMethod: "POST"`, which the server
 * lower-cases anyway).
 */
sealed interface Condition {
    fun evaluate(
        values: Map<String, Any?>,
        toggles: Map<String, Boolean> = emptyMap(),
    ): Boolean

    fun identifiers(): Set<String>

    data class Ident(
        val name: String,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = if (name in toggles) toggles.getValue(name) else truthy(values[name])

        override fun identifiers() = setOf(name)
    }

    data class Literal(
        val value: Any?,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = truthy(value)

        override fun identifiers() = emptySet<String>()
    }

    data class Not(
        val inner: Condition,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = !inner.evaluate(values, toggles)

        override fun identifiers() = inner.identifiers()
    }

    data class And(
        val left: Condition,
        val right: Condition,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = left.evaluate(values, toggles) && right.evaluate(values, toggles)

        override fun identifiers() = left.identifiers() + right.identifiers()
    }

    data class Or(
        val left: Condition,
        val right: Condition,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = left.evaluate(values, toggles) || right.evaluate(values, toggles)

        override fun identifiers() = left.identifiers() + right.identifiers()
    }

    data class Equals(
        val left: Condition,
        val right: Condition,
        val negated: Boolean,
    ) : Condition {
        override fun evaluate(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ): Boolean {
            val equal = valueOf(left, values, toggles).equals(valueOf(right, values, toggles), ignoreCase = true)
            return equal != negated
        }

        override fun identifiers() = left.identifiers() + right.identifiers()

        private fun valueOf(
            operand: Condition,
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ): String? =
            when (operand) {
                is Ident -> {
                    if (operand.name in
                        toggles
                    ) {
                        toggles[operand.name].toString()
                    } else {
                        canonical(values[operand.name])
                    }
                }

                is Literal -> {
                    canonical(operand.value)
                }

                else -> {
                    operand.evaluate(values, toggles).toString()
                }
            }
    }

    companion object {
        /** Parses [text]; `null` when it uses syntax outside the supported subset. */
        fun parseOrNull(text: String): Condition? =
            try {
                ConditionParser(text).parse()
            } catch (_: IllegalArgumentException) {
                null
            }

        /** JavaScript truthiness. */
        fun truthy(value: Any?): Boolean =
            when (value) {
                null -> false
                is Boolean -> value
                is String -> value.isNotEmpty()
                is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
                else -> true
            }

        private fun canonical(value: Any?): String? =
            when (value) {
                null -> null
                is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                is Float -> canonical(value.toDouble())
                else -> value.toString()
            }
    }
}

private class ConditionParser(
    private val text: String,
) {
    private var pos = 0

    fun parse(): Condition {
        val result = or()
        skipSpace()
        require(pos == text.length) { "unexpected '${text.substring(pos)}' in '$text'" }
        return result
    }

    private fun or(): Condition {
        var left = and()
        while (accept("||")) left = Condition.Or(left, and())
        return left
    }

    private fun and(): Condition {
        var left = equality()
        while (accept("&&")) left = Condition.And(left, equality())
        return left
    }

    private fun equality(): Condition {
        val left = unary()
        return when {
            accept("!==") || accept("!=") -> Condition.Equals(left, unary(), negated = true)
            accept("===") || accept("==") -> Condition.Equals(left, unary(), negated = false)
            else -> left
        }
    }

    private fun unary(): Condition {
        skipSpace()
        if (peek() == '!' && text.getOrNull(pos + 1) != '=') {
            pos++
            return Condition.Not(unary())
        }
        return primary()
    }

    private fun primary(): Condition {
        skipSpace()
        val c = peek() ?: throw IllegalArgumentException("unexpected end of '$text'")
        return when {
            c == '(' -> {
                pos++
                val inner = or()
                require(accept(")")) { "missing ')' in '$text'" }
                inner
            }

            c == '\'' || c == '"' -> {
                string(c)
            }

            c.isDigit() || c == '-' -> {
                number()
            }

            c.isLetter() || c == '_' || c == '$' -> {
                identifier()
            }

            else -> {
                throw IllegalArgumentException("unexpected '$c' in '$text'")
            }
        }
    }

    private fun string(quote: Char): Condition {
        val end = text.indexOf(quote, pos + 1)
        require(end > pos) { "unterminated string in '$text'" }
        val value = text.substring(pos + 1, end)
        pos = end + 1
        return Condition.Literal(value)
    }

    private fun number(): Condition {
        val start = pos
        pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
        val raw = text.substring(start, pos)
        return Condition.Literal(raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException(raw))
    }

    private fun identifier(): Condition {
        val start = pos
        while (pos < text.length && isIdentifierChar(text[pos])) pos++
        return when (val name = text.substring(start, pos)) {
            "true" -> Condition.Literal(true)
            "false" -> Condition.Literal(false)
            "null", "undefined" -> Condition.Literal(null)
            else -> Condition.Ident(name)
        }
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun accept(token: String): Boolean {
        skipSpace()
        if (!text.startsWith(token, pos)) return false
        pos += token.length
        return true
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
