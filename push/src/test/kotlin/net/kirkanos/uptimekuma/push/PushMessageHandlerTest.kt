package net.kirkanos.uptimekuma.push

import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.push.data.FakeScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PushMessageHandlerTest {
    private val shown = mutableListOf<PushEvent>()
    private val scheduler = FakeScheduler()
    private val presenter = AlertPresenter { event -> shown.add(event) }
    private val handler = PushMessageHandler(presenter, scheduler)

    @Test
    fun `UnifiedPush body is parsed, shown and triggers a sync`() {
        handler.handle(DOWN_BODY.encodeToByteArray())
        val event = shown.single()
        assertEquals(7L, event.monitorId)
        assertEquals("API", event.monitorName)
        assertEquals(MonitorStatus.DOWN, event.status)
        assertEquals("timeout", event.message)
        assertEquals(1, scheduler.syncs)
    }

    @Test
    fun `relay data message takes exactly the same path`() {
        val viaFcm = handler.handleRelayData(mapOf("v" to "1", "body" to DOWN_BODY))
        val viaUnifiedPush = handler.handle(DOWN_BODY)
        assertEquals(viaUnifiedPush, viaFcm)
        assertEquals(2, shown.size)
        assertEquals(2, scheduler.syncs)
    }

    @Test
    fun `test notification without monitor is shown but needs no sync`() {
        handler.handleRelayData(mapOf("v" to "1", "body" to TEST_BODY))
        assertNull(shown.single().monitorId)
        assertEquals("Test message", shown.single().message)
        assertEquals(0, scheduler.syncs)
    }

    @Test
    fun `relay message without body is dropped`() {
        assertNull(handler.handleRelayData(mapOf("v" to "1")))
        assertEquals(0, shown.size)
        assertEquals(0, scheduler.syncs)
    }

    @Test
    fun `unknown relay version is still parsed`() {
        handler.handleRelayData(mapOf("v" to "2", "body" to DOWN_BODY))
        assertEquals(7L, shown.single().monitorId)
    }

    @Test
    fun `a crashing presenter does not prevent the sync`() {
        val failing = PushMessageHandler(presenter = { error("boom") }, scheduler = scheduler)
        failing.handle(DOWN_BODY)
        assertEquals(1, scheduler.syncs)
    }

    private companion object {
        const val DOWN_BODY =
            """{"v":1,"monitor":{"id":7,"name":"API","type":"http","url":"https://api.example.com",""" +
                """"hostname":null,"port":null},"heartbeat":{"status":0,"msg":"timeout",""" +
                """"time":"2026-10-29 08:00:00.000","localDateTime":null,"ping":null,"duration":null,""" +
                """"retries":0,"important":true},"msg":"[API] [DOWN] timeout"}"""
        const val TEST_BODY =
            """{"v":1,"monitor":{"id":null,"name":null,"type":null,"url":null,"hostname":null,"port":null},""" +
                """"heartbeat":{"status":null,"msg":null,"time":null,"localDateTime":null,"ping":null,""" +
                """"duration":null,"retries":null,"important":null},"msg":"Test message"}"""
    }
}
