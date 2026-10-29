package net.kirkanos.uptimekuma.widget.state

import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetUpdatePolicyTest {
    private val policy = WidgetUpdatePolicy(minIntervalMillis = 300_000)
    private val base = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.UP, ping = 10.0)))
    private val pingChanged = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.UP, ping = 20.0)))
    private val down = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.DOWN)))

    @Test
    fun `first state is always published`() {
        assertTrue(policy.shouldPublish(null, base, 0, NOW))
    }

    @Test
    fun `identical state is skipped`() {
        assertFalse(policy.shouldPublish(base, base, 0, NOW))
    }

    @Test
    fun `status change is published at once`() {
        assertTrue(policy.shouldPublish(base, down, NOW, NOW + 1))
    }

    @Test
    fun `ping change is throttled`() {
        assertFalse(policy.shouldPublish(base, pingChanged, NOW, NOW + 299_999))
        assertTrue(policy.shouldPublish(base, pingChanged, NOW, NOW + 300_000))
    }
}
