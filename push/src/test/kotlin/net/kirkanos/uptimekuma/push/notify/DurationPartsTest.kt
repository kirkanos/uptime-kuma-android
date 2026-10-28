package net.kirkanos.uptimekuma.push.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DurationPartsTest {
    @Test
    fun `splits durations`() {
        assertEquals(DurationParts(0, 0, 0, 42), DurationParts.of(42_000))
        assertEquals(DurationParts(0, 1, 5, 0), DurationParts.of(65 * 60_000L))
        assertEquals(DurationParts(2, 3, 0, 1), DurationParts.of((2 * 86_400 + 3 * 3_600 + 1) * 1000L))
        assertEquals(DurationParts(0, 0, 0, 0), DurationParts.of(-5))
    }
}
