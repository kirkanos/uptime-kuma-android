package net.kirkanos.uptimekuma.push.alerts

import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.core.model.QuietHours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class AlertGateTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun gateAt(
        hour: Int,
        minute: Int = 0,
    ): AlertGate {
        val instant = LocalDateTime.of(2026, 10, 29, hour, minute).atZone(zone).toInstant()
        return AlertGate(Clock.fixed(instant, zone))
    }

    private fun event(
        status: MonitorStatus?,
        monitorId: Long? = 7,
    ) = PushEvent(monitorId = monitorId, monitorName = "API", status = status, message = "msg")

    private val quiet =
        AlertPreferences(
            quietHours =
                QuietHours(
                    enabled = true,
                    startMinuteOfDay = 22 * 60,
                    endMinuteOfDay =
                        7 * 60,
                ),
        )

    @Test
    fun `defaults show everything`() {
        val gate = gateAt(12)
        MonitorStatus.entries.forEach { assertEquals(AlertDecision.SHOW, gate.decide(event(it), AlertPreferences())) }
        assertEquals(AlertDecision.SHOW, gate.decide(event(null), AlertPreferences()))
    }

    @Test
    fun `muted monitor never alerts, not even DOWN`() {
        val gate = gateAt(12)
        val prefs = AlertPreferences(mutes = mapOf(7L to MonitorMute(7, untilMillis = null)))
        assertEquals(AlertDecision.MUTED, gate.decide(event(MonitorStatus.DOWN), prefs))
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.DOWN, monitorId = 8), prefs))
    }

    @Test
    fun `expired mute no longer applies`() {
        val gate = gateAt(12)
        val now = gate.nowMillis()
        val active = AlertPreferences(mutes = mapOf(7L to MonitorMute(7, untilMillis = now + 1)))
        val expired = AlertPreferences(mutes = mapOf(7L to MonitorMute(7, untilMillis = now)))
        assertEquals(AlertDecision.MUTED, gate.decide(event(MonitorStatus.DOWN), active))
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.DOWN), expired))
    }

    @Test
    fun `test notifications ignore mutes and quiet hours`() {
        val gate = gateAt(23)
        assertEquals(AlertDecision.SHOW, gate.decide(event(null, monitorId = null), quiet))
    }

    @Test
    fun `recovery and certificate alerts can be disabled`() {
        val gate = gateAt(12)
        val prefs = AlertPreferences(notifyOnUp = false, notifyOnCertExpiry = false)
        assertEquals(AlertDecision.UP_DISABLED, gate.decide(event(MonitorStatus.UP), prefs))
        assertEquals(AlertDecision.CERT_EXPIRY_DISABLED, gate.decide(event(null), prefs))
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.DOWN), prefs))
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.PENDING), prefs))
    }

    @ParameterizedTest
    @CsvSource("22,0,true", "23,59,true", "0,0,true", "6,59,true", "7,0,false", "12,0,false", "21,59,false")
    fun `quiet hours across midnight let only DOWN through`(
        hour: Int,
        minute: Int,
        inside: Boolean,
    ) {
        val gate = gateAt(hour, minute)
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.DOWN), quiet))
        val expected = if (inside) AlertDecision.QUIET_HOURS else AlertDecision.SHOW
        assertEquals(expected, gate.decide(event(MonitorStatus.UP), quiet))
        assertEquals(expected, gate.decide(event(MonitorStatus.PENDING), quiet))
        assertEquals(expected, gate.decide(event(MonitorStatus.MAINTENANCE), quiet))
        assertEquals(expected, gate.decide(event(null), quiet))
    }

    @Test
    fun `disabled quiet hours do nothing`() {
        val gate = gateAt(23)
        val prefs = quiet.copy(quietHours = quiet.quietHours.copy(enabled = false))
        assertEquals(AlertDecision.SHOW, gate.decide(event(MonitorStatus.UP), prefs))
    }

    @Test
    fun `quiet hours window within one day and empty window`() {
        val day = QuietHours(enabled = true, startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        assertTrue(day.contains(9 * 60))
        assertTrue(day.contains(16 * 60 + 59))
        assertFalse(day.contains(17 * 60))
        assertFalse(day.contains(8 * 60))
        assertFalse(QuietHours(enabled = true, startMinuteOfDay = 600, endMinuteOfDay = 600).contains(600))
    }
}
