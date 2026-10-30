package net.kirkanos.uptimekuma.push.alerts

import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.core.model.RepeatInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReminderPlanTest {
    private val tenMinutes = RepeatInterval.TEN_MINUTES.millis
    private val now = 1_761_700_000_000L

    private fun alert(
        id: Long,
        lastAlertAt: Long,
        acknowledged: Boolean = false,
    ) = ActiveDownAlert(monitorId = id, lastAlertAt = lastAlertAt, acknowledged = acknowledged)

    @Test
    fun `due once the interval has passed, a little early counts`() {
        val alerts =
            listOf(
                alert(1, lastAlertAt = now - 10 * MINUTE),
                alert(2, lastAlertAt = now - 9 * MINUTE - 30_000),
                alert(3, lastAlertAt = now - 5 * MINUTE),
            )
        assertEquals(listOf(1L, 2L), ReminderPlan.due(alerts, tenMinutes, now).map { it.monitorId })
    }

    @Test
    fun `acknowledged alerts and interval off never ring`() {
        val alerts = listOf(alert(1, lastAlertAt = now - 60 * MINUTE, acknowledged = true))
        assertEquals(emptyList<ActiveDownAlert>(), ReminderPlan.due(alerts, tenMinutes, now))
        assertNull(ReminderPlan.nextAt(alerts, tenMinutes))
        val open = listOf(alert(2, lastAlertAt = now - 60 * MINUTE))
        assertEquals(emptyList<ActiveDownAlert>(), ReminderPlan.due(open, RepeatInterval.OFF.millis, now))
        assertNull(ReminderPlan.nextAt(open, RepeatInterval.OFF.millis))
    }

    @Test
    fun `next alarm is the earliest open reminder`() {
        val alerts =
            listOf(
                alert(1, lastAlertAt = now - 2 * MINUTE),
                alert(2, lastAlertAt = now - 7 * MINUTE),
                alert(3, lastAlertAt = now - 9 * MINUTE, acknowledged = true),
            )
        assertEquals(now + 3 * MINUTE, ReminderPlan.nextAt(alerts, tenMinutes))
        assertNull(ReminderPlan.nextAt(emptyList(), tenMinutes))
    }

    @Test
    fun `alert keeps the event and falls back to the event time as down since`() {
        val event =
            PushEvent(
                monitorId = 7,
                monitorName = "Website",
                target = "https://example.com",
                status = MonitorStatus.DOWN,
                message = "timeout",
                timeMillis = now - MINUTE,
            )
        val alert = ActiveDownAlert.of(event, 7, downSince = null, nowMillis = now)
        assertEquals(now - MINUTE, alert.downSince)
        assertEquals(now, alert.lastAlertAt)
        assertEquals(event, alert.toEvent())
        assertEquals(now - 30 * MINUTE, ActiveDownAlert.of(event, 7, now - 30 * MINUTE, now).downSince)
    }

    @Test
    fun `stored minutes map back to the interval`() {
        assertEquals(RepeatInterval.OFF, RepeatInterval.ofMinutes(0))
        assertEquals(RepeatInterval.ONE_HOUR, RepeatInterval.ofMinutes(60))
        assertEquals(RepeatInterval.DEFAULT, RepeatInterval.ofMinutes(7))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
