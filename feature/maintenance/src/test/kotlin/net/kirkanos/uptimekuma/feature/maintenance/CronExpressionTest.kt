package net.kirkanos.uptimekuma.feature.maintenance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

class CronExpressionTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "30 3 * * *",
            "0 2 * * 1,3,5",
            "*/15 * * * *",
            "0 0 1,15,L * *",
            "0 4 * * SUN",
            "0 4 * JAN-MAR MON-FRI",
            "0 0 L * *",
            "0 0 15W * *",
            "0 0 ? * 5#2",
            "0 0 * * 5L",
            "0 30 3 * * *",
            "  30 3 * * *  ",
            "@daily",
            "@Weekly",
            "0 2  * * *",
        ],
    )
    fun `accepts cron expressions croner accepts`(expression: String) {
        assertTrue(CronExpression.isValid(expression), expression)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "* * * *",
            "* * * * * * *",
            "60 * * * *",
            "0 24 * * *",
            "0 0 32 * *",
            "0 0 0 * *",
            "0 0 * 13 *",
            "0 0 * * 8",
            "0 0 * * FOO",
            "5-1 * * * *",
            "*/0 * * * *",
            "a b c d e",
            "@sometimes",
            "0 0 ? ? ?",
            "1,,2 * * * *",
        ],
    )
    fun `rejects invalid expressions`(expression: String) {
        assertFalse(CronExpression.isValid(expression), expression)
    }

    @Test
    fun `summarizes common shapes`() {
        assertEquals(CronExpression.Summary.Daily(LocalTime.of(3, 30)), CronExpression.summarize("30 3 * * *"))
        assertEquals(
            CronExpression.Summary.Weekly(LocalTime.of(2, 0), listOf(0, 1, 5)),
            CronExpression.summarize("0 2 * * 5,1,7"),
        )
        assertEquals(
            CronExpression.Summary.Monthly(LocalTime.of(0, 15), listOf(1, 15), lastDay = true),
            CronExpression.summarize("15 0 15,1,L * *"),
        )
    }

    @Test
    fun `no summary for exotic or invalid expressions`() {
        assertNull(CronExpression.summarize("*/5 * * * *"))
        assertNull(CronExpression.summarize("0 2 * 1 *"))
        assertNull(CronExpression.summarize("0 2 1 * 1"))
        assertNull(CronExpression.summarize("0 0 30 3 * *"))
        assertNull(CronExpression.summarize("0 2 * * MON"))
        assertNull(CronExpression.summarize("nope"))
    }
}
