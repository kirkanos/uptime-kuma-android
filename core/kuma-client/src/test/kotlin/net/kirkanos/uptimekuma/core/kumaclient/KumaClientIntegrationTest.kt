package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows

/**
 * Talks to a live Kuma 2.5.x (`infra/docker-compose.dev.yml`, seeded with `infra/kuma-seed`).
 * Run with `./gradlew :core:kuma-client:integrationTest`; excluded from the default `test` task.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KumaClientIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorded = MutableSharedFlow<KumaEvent>(replay = 10_000)
    private lateinit var client: KumaClient
    private lateinit var token: String

    @BeforeAll
    fun connectAndLogin() =
        runBlocking<Unit> {
            client = KumaClient(url, logger = { msg, t -> println("kuma-client: $msg ${t ?: ""}") })
            scope.launch { client.events.collect { recorded.emit(it) } }
            client.connect()
            val ready = client.awaitReady(20_000)
            assertFalse(ready.autoLogin)
            assertFalse(client.needSetup())
            val response = client.login(user, password)
            token = (response as LoginResponse.Success).token
        }

    @AfterAll
    fun tearDown() {
        client.close()
        scope.cancel()
    }

    private suspend inline fun <reified T : KumaEvent> awaitEvent(
        timeoutMillis: Long = 20_000,
        crossinline predicate: (T) -> Boolean = { true },
    ): T = withTimeout(timeoutMillis) { recorded.first { it is T && predicate(it) } as T }

    private suspend fun monitors() = awaitEvent<KumaEvent.MonitorList>().monitors

    @Test
    @Order(1)
    fun `login pushes full info, monitor list and heartbeat lists`() =
        runBlocking<Unit> {
            val info = awaitEvent<KumaEvent.Info> { it.info.isFull }.info
            assertTrue(info.version.orEmpty().startsWith("2."), info.version)
            val monitors = monitors()
            assertTrue(monitors.isNotEmpty())
            monitors.keys.forEach { id -> awaitEvent<KumaEvent.HeartbeatList> { it.monitorId == id } }
            awaitEvent<KumaEvent.NotificationList>()
            awaitEvent<KumaEvent.MaintenanceList>()
            awaitEvent<KumaEvent.Uptime>()
            println("monitors=${monitors.size} version=${info.version}")
        }

    @Test
    @Order(2)
    fun `a live heartbeat arrives`() =
        runBlocking<Unit> {
            val beat = awaitEvent<KumaEvent.NewHeartbeat>(timeoutMillis = 70_000).beat.toModel()
            assertTrue(beat != null && beat.timeMillis > 0)
            println("heartbeat: $beat")
        }

    @Test
    @Order(3)
    fun `pause and resume push updateMonitorIntoList`() =
        runBlocking<Unit> {
            val monitor = monitors().values.first { it.type == "http" && it.active }
            client.pauseMonitor(monitor.id)
            awaitEvent<KumaEvent.MonitorUpdate> { it.monitors[monitor.id]?.active == false }
            client.resumeMonitor(monitor.id)
            awaitEvent<KumaEvent.MonitorUpdate> { it.monitors[monitor.id]?.active == true }
            val error = assertThrows<KumaException> { runBlocking { client.pauseMonitor(9_999_999) } }.error
            assertEquals(KumaError.Kind.SERVER_REJECTED, error.kind)
        }

    @Test
    @Order(4)
    fun `beats, chart and important events`() =
        runBlocking<Unit> {
            val monitor = monitors().values.first { it.type == "http" }
            val beats = client.getMonitorBeats(monitor.id, 24)
            assertTrue(beats.isNotEmpty())
            assertEquals(beats.sortedBy { it.timeMillis }, beats)
            val chart = client.getMonitorChartData(monitor.id, 24)
            assertTrue(chart.isNotEmpty())
            val important = client.importantHeartbeats(null, 0, 25)
            assertTrue(important.isNotEmpty() && important.all { it.important })
            assertTrue(client.importantHeartbeatCount(null) >= important.size)
            val tags = client.getTags()
            println("beats=${beats.size} chart=${chart.size} important=${important.size} tags=${tags.map { it.name }}")
        }

    @Test
    @Order(5)
    fun `add and delete a webhook notification`() =
        runBlocking<Unit> {
            val name = "Integration webhook ${System.currentTimeMillis()}"
            val payload =
                NotificationConfig.toPayload(
                    KumaNotification(
                        id = null,
                        name = name,
                        type = "webhook",
                        fields =
                            mapOf(
                                "webhookURL" to "http://127.0.0.1:9/never",
                                "webhookContentType" to "json",
                                "httpMethod" to "POST",
                            ),
                    ),
                )
            val id = client.addNotification(payload, null)
            val listed =
                awaitEvent<KumaEvent.NotificationList> { list -> list.notifications.any { it.id == id } }
                    .notifications
                    .first { it.id == id }
                    .toModel()
            assertEquals(name, listed.name)
            assertEquals("webhook", listed.type)
            assertEquals("http://127.0.0.1:9/never", listed.fields["webhookURL"])
            val testError = assertThrows<KumaException> { runBlocking { client.testNotification(payload) } }.error
            assertEquals(KumaError.Kind.SERVER_REJECTED, testError.kind)
            client.deleteNotification(id)
            awaitEvent<KumaEvent.NotificationList> { list -> list.notifications.none { it.id == id } }
        }

    @Test
    @Order(6)
    fun `token login on a second socket, invalid token rejected`() =
        runBlocking<Unit> {
            val second = KumaClient(url)
            try {
                second.connect()
                second.awaitReady(20_000)
                second.loginByToken(token)
                val error = assertThrows<KumaException> { runBlocking { second.loginByToken("not-a-jwt") } }.error
                assertEquals(KumaError.Kind.INVALID_CREDENTIALS, error.kind)
                second.logout()
            } finally {
                second.close()
            }
        }

    @Test
    @Order(7)
    fun `requests before connect fail fast`() {
        val unconnected = KumaClient(url)
        val error = assertThrows<KumaException> { runBlocking { unconnected.getTags() } }.error
        assertEquals(KumaError.Kind.NETWORK, error.kind)
        unconnected.reconnectNow() // never connected: no-op, must not open a socket
        assertEquals(KumaClientState.Idle, unconnected.state.value)
        unconnected.close()
    }

    @Test
    @Order(8)
    fun `reconnectNow opens a fresh server socket that needs a token login again`() =
        runBlocking<Unit> {
            val second = KumaClient(url)
            try {
                second.connect()
                second.awaitReady(20_000)
                second.loginByToken(token)
                second.getTags()

                second.reconnectNow()
                // A new transport is a new, unauthenticated server socket: Ready again, then login by token.
                val ready = withTimeout(20_000) { second.state.first { it is KumaClientState.Ready } }
                assertFalse((ready as KumaClientState.Ready).autoLogin)
                val error = assertThrows<KumaException> { runBlocking { second.getTags() } }.error
                assertEquals(KumaError.Kind.UNAUTHORIZED, error.kind)
                second.loginByToken(token)
                assertTrue(second.getTags().isNotEmpty())
            } finally {
                second.close()
            }
        }
}
