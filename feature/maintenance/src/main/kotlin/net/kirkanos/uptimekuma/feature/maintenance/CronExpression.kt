package net.kirkanos.uptimekuma.feature.maintenance

import java.time.LocalTime

/**
 * Client-side check of the cron dialect Kuma validates with `croner`: 5 fields (6 with leading seconds),
 * `*`, `?`, numbers, ranges, steps, lists, month/day names, `L`/`W` (day of month), `L`/`#` (weekday) and the
 * `@daily`-style nicknames. The server stays the final authority; this only gives early feedback.
 */
internal object CronExpression {
    private data class FieldSpec(
        val range: IntRange,
        val names: List<String> = emptyList(),
        val dayOfMonth: Boolean = false,
        val dayOfWeek: Boolean = false,
    )

    private val seconds = FieldSpec(0..59)
    private val fields =
        listOf(
            FieldSpec(0..59),
            FieldSpec(0..23),
            FieldSpec(1..31, dayOfMonth = true),
            FieldSpec(
                1..12,
                names = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"),
            ),
            FieldSpec(0..7, names = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"), dayOfWeek = true),
        )
    private val nicknames = setOf("@yearly", "@annually", "@monthly", "@weekly", "@daily", "@hourly")
    private const val FIELD_COUNT = 5
    private const val FIELD_COUNT_WITH_SECONDS = 6
    private const val MONTH = 3
    private const val DAY_OF_WEEK = 4
    private const val DAYS_PER_WEEK = 7

    private val whitespace = Regex("\\s+")
    private val nthWeekday = Regex("(\\w+)#[1-5]")
    private val lastWeekday = Regex("(\\w+)L")
    private val nearestWeekday = Regex("(\\d{1,2})W")

    fun isValid(expression: String): Boolean {
        val trimmed = expression.trim()
        val parts = trimmed.split(whitespace)
        val specs =
            when (parts.size) {
                FIELD_COUNT -> fields
                FIELD_COUNT_WITH_SECONDS -> listOf(seconds) + fields
                else -> null
            }
        return when {
            trimmed.isEmpty() -> false
            trimmed.startsWith("@") -> trimmed.lowercase() in nicknames
            specs == null -> false
            else -> parts.zip(specs).all { (part, spec) -> isValidField(part.uppercase(), spec) }
        }
    }

    private fun isValidField(
        field: String,
        spec: FieldSpec,
    ): Boolean = field.split(',').all { it.isNotEmpty() && (isSpecialItem(it, spec) || isRangeItem(it, spec)) }

    /** `L`, `LW`, `15W` (day of month) and `5#2`, `5L` (day of week). */
    private fun isSpecialItem(
        item: String,
        spec: FieldSpec,
    ): Boolean {
        val dayOfMonth = spec.dayOfMonth && (item == "L" || item == "LW")
        val nearest =
            spec.dayOfMonth && nearestWeekday.matchEntire(item)?.let { value(it.groupValues[1], spec) } != null
        val weekday =
            spec.dayOfWeek &&
                (nthWeekday.matchEntire(item) ?: lastWeekday.matchEntire(item))
                    ?.let { value(it.groupValues[1], spec) } != null
        return dayOfMonth || nearest || weekday
    }

    /** `*`, `?`, `n`, `a-b`, each with an optional `/step`. */
    private fun isRangeItem(
        item: String,
        spec: FieldSpec,
    ): Boolean {
        val base = item.substringBefore('/')
        val step = item.substringAfter('/', missingDelimiterValue = "1").toIntOrNull()
        val wildcard = base == "*" || (base == "?" && (spec.dayOfMonth || spec.dayOfWeek))
        val from = value(base.substringBefore('-'), spec)
        val to = if ('-' in base) value(base.substringAfter('-'), spec) else from
        val range = from != null && to != null && from <= to
        return step != null && step >= 1 && (wildcard || range)
    }

    private fun value(
        text: String,
        spec: FieldSpec,
    ): Int? {
        val index = spec.names.indexOf(text)
        return if (index >= 0) index + spec.range.first else text.toIntOrNull()?.takeIf { it in spec.range }
    }

    /** Plain-language form of the common shapes; `null` when the expression is something more exotic. */
    sealed interface Summary {
        val time: LocalTime

        data class Daily(
            override val time: LocalTime,
        ) : Summary

        /** [weekdays]: 0 = Sunday .. 6 = Saturday. */
        data class Weekly(
            override val time: LocalTime,
            val weekdays: List<Int>,
        ) : Summary

        /** [days]: day numbers; [lastDay] for `L`. */
        data class Monthly(
            override val time: LocalTime,
            val days: List<Int>,
            val lastDay: Boolean,
        ) : Summary
    }

    fun summarize(expression: String): Summary? {
        val parts = expression.trim().split(whitespace)
        val simple = parts.size == FIELD_COUNT && isValid(expression) && parts[MONTH] == "*"
        val time = if (simple) runCatching { LocalTime.of(parts[1].toInt(), parts[0].toInt()) }.getOrNull() else null
        return time?.let { summarize(it, dayOfMonth = parts[2], dayOfWeek = parts[DAY_OF_WEEK]) }
    }

    private fun summarize(
        time: LocalTime,
        dayOfMonth: String,
        dayOfWeek: String,
    ): Summary? =
        when {
            dayOfMonth == "*" && dayOfWeek == "*" -> {
                Summary.Daily(time)
            }

            dayOfMonth == "*" -> {
                numbers(dayOfWeek.split(','))?.let { days ->
                    Summary.Weekly(time, days.map { it % DAYS_PER_WEEK }.distinct().sorted())
                }
            }

            dayOfWeek == "*" -> {
                val items = dayOfMonth.split(',')
                numbers(items.filter { it != "L" })?.let { days ->
                    Summary.Monthly(time, days.distinct().sorted(), lastDay = "L" in items)
                }
            }

            else -> {
                null
            }
        }

    /** All items as numbers, or `null` when any is not a plain number. */
    private fun numbers(items: List<String>): List<Int>? =
        items.mapNotNull { it.toIntOrNull() }.takeIf { it.size == items.size }
}
