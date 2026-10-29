package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kirkanos.uptimekuma.core.data.FakeConfigStore
import net.kirkanos.uptimekuma.core.data.FakeKumaCache
import net.kirkanos.uptimekuma.core.data.FakeSecretStore
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every maintenance strategy against the dev Kuma (`infra/docker-compose.dev.yml`). Creates entities titled
 * `[P2-MAINT] …` and deletes them again. Run: `./gradlew :core:data:testDebugUnitTest -Pintegration
 * --tests '*MaintenanceIntegrationTest*'`.
 */
@Tag("integration")
class MaintenanceIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun `create, edit, pause, resume and delete every strategy`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = FakeKumaCache()
            val m =
                KumaConnectionManager(
                    SocketKumaApiFactory(),
                    cache,
                    FakeConfigStore(),
                    FakeSecretStore(),
                    emptySet(),
                    scope,
                    ConnectionTimings(),
                )
            val repo = KumaMaintenanceRepository(ConnectionMaintenanceBackend(m))
            val created = mutableListOf<Long>()
            try {
                m.setForeground(true)
                assertEquals(LoginResult.Success, m.login(url, user, password, null))
                withTimeout(TIMEOUT) { m.state.first { it is ConnectionState.Connected } }
                val pages = withTimeout(TIMEOUT) { repo.observeStatusPages().filterNotNull().first() }
                val dev = pages.first { it.slug == "dev" }
                withTimeout(TIMEOUT) { while (cache.monitors.isEmpty()) kotlinx.coroutines.delay(100) }
                val monitorId = if (1L in cache.monitors) 1L else cache.monitors.keys.min()
                println("status pages=$pages, monitor=$monitorId (${cache.monitors[monitorId]?.name})")

                val tomorrow =
                    LocalDateTime
                        .now(ZoneId.of("UTC"))
                        .plusDays(1)
                        .withSecond(0)
                        .withNano(0)
                val base = MaintenanceDraft(monitorIds = setOf(monitorId), statusPageIds = setOf(dev.id))
                val drafts =
                    listOf(
                        // Manual windows are active immediately: show them on the status page only.
                        base.copy(
                            title = "$PREFIX manual",
                            strategy = MaintenanceStrategy.MANUAL,
                            monitorIds = emptySet(),
                        ),
                        base.copy(
                            title = "$PREFIX single",
                            strategy = MaintenanceStrategy.SINGLE,
                            startDate = tomorrow,
                            endDate = tomorrow.plusMinutes(90),
                            timezoneOption = "UTC",
                        ),
                        base.copy(
                            title = "$PREFIX cron",
                            strategy = MaintenanceStrategy.CRON,
                            cron = "30 3 * * *",
                            durationMinutes = 45,
                            startDate = tomorrow,
                        ),
                        base.copy(
                            title = "$PREFIX interval",
                            strategy = MaintenanceStrategy.RECURRING_INTERVAL,
                            intervalDay = 2,
                            startDate = tomorrow,
                        ),
                        base.copy(
                            title = "$PREFIX weekday",
                            strategy = MaintenanceStrategy.RECURRING_WEEKDAY,
                            weekdays = setOf(1, 3, 5),
                            startTime = LocalTime.of(22, 0),
                            endTime = LocalTime.of(1, 0),
                            timezoneOption = "Europe/Berlin",
                            startDate = tomorrow,
                        ),
                        base.copy(
                            title = "$PREFIX day-of-month",
                            strategy = MaintenanceStrategy.RECURRING_DAY_OF_MONTH,
                            daysOfMonth =
                                setOf(
                                    MaintenanceMonthDay.Day(15),
                                    MaintenanceMonthDay.Day(1),
                                    MaintenanceMonthDay.LAST,
                                ),
                            startDate = tomorrow,
                            endDate = tomorrow.plusYears(1),
                        ),
                    )
                val ids =
                    drafts.associate { draft ->
                        val result = repo.save(draft)
                        created += result.id
                        assertNull(result.linkError, draft.title)
                        draft.strategy to result.id
                    }

                // Links round-trip.
                ids.forEach { (strategy, id) ->
                    val detail = repo.get(id)
                    val expectedMonitors =
                        if (strategy ==
                            MaintenanceStrategy.MANUAL
                        ) {
                            emptyList()
                        } else {
                            listOf(monitorId)
                        }
                    assertEquals(expectedMonitors, detail.monitorIds, strategy.name)
                    assertEquals(listOf(dev.id), detail.statusPages.map { it.id }, strategy.name)
                    assertEquals(strategy, detail.maintenance.strategy)
                }

                // The pushed list decodes with web UI semantics.
                val list = awaitList(repo) { list -> ids.values.all { id -> list.any { it.id == id } } }
                val byStrategy = ids.mapValues { (_, id) -> list.first { it.id == id } }
                byStrategy.values.forEach(::print)
                val now = Instant.now().toEpochMilli()
                with(byStrategy.getValue(MaintenanceStrategy.MANUAL)) {
                    assertEquals(MaintenanceStatus.UNDER_MAINTENANCE, status)
                    assertTrue(timeslots.isEmpty())
                }
                with(byStrategy.getValue(MaintenanceStrategy.SINGLE)) {
                    assertEquals(MaintenanceStatus.SCHEDULED, status)
                    assertEquals("UTC", timezone)
                    val slot = timeslots.single()
                    assertEquals(tomorrow.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), slot.startMillis)
                    assertEquals(90 * 60_000L, slot.endMillis - slot.startMillis)
                }
                with(byStrategy.getValue(MaintenanceStrategy.CRON)) {
                    assertEquals(MaintenanceStatus.SCHEDULED, status)
                    assertEquals("30 3 * * *", cron)
                    assertEquals(45, durationMinutes)
                    assertTrue(timeslots.isNotEmpty() && timeslots.last().startMillis > now)
                }
                with(byStrategy.getValue(MaintenanceStrategy.RECURRING_WEEKDAY)) {
                    assertEquals("0 22 * * 1,3,5", cron)
                    assertEquals(180, durationMinutes)
                    assertEquals(listOf(1, 3, 5), weekdays)
                    assertEquals(LocalTime.of(22, 0), startTime)
                    assertEquals("Europe/Berlin", timezoneOption)
                }
                with(byStrategy.getValue(MaintenanceStrategy.RECURRING_DAY_OF_MONTH)) {
                    assertEquals("0 2 1,15,L * *", cron)
                    assertEquals(
                        setOf(MaintenanceMonthDay.Day(1), MaintenanceMonthDay.Day(15), MaintenanceMonthDay.LAST),
                        daysOfMonth.toSet(),
                    )
                    assertTrue(timeslots.isNotEmpty())
                }
                with(byStrategy.getValue(MaintenanceStrategy.RECURRING_INTERVAL)) {
                    assertEquals(2, intervalDay)
                    assertEquals(60, durationMinutes)
                }

                // Edit: rename and detach the monitor (it must not stay attached).
                val singleId = ids.getValue(MaintenanceStrategy.SINGLE)
                val edited =
                    repo.save(drafts[1].copy(id = singleId, title = "$PREFIX single (edited)", monitorIds = emptySet()))
                assertEquals(singleId, edited.id)
                assertTrue(repo.get(singleId).monitorIds.isEmpty())
                awaitList(repo) { list -> list.any { it.id == singleId && it.title.endsWith("(edited)") } }

                // Pause / resume.
                val cronId = ids.getValue(MaintenanceStrategy.CRON)
                repo.pause(cronId)
                val paused =
                    awaitList(repo) { list -> list.any { it.id == cronId && !it.active } }.first {
                        it.id ==
                            cronId
                    }
                assertEquals(MaintenanceStatus.INACTIVE, paused.status)
                repo.resume(cronId)
                val resumed =
                    awaitList(repo) { list -> list.any { it.id == cronId && it.active } }.first {
                        it.id ==
                            cronId
                    }
                assertEquals(MaintenanceStatus.SCHEDULED, resumed.status)
                println("paused=${paused.status} resumed=${resumed.status}")

                // What the server does with a weekday window without days (the app requires at least one).
                val empty =
                    try {
                        repo.save(
                            drafts[4].copy(
                                title = "$PREFIX weekday-empty",
                                weekdays = emptySet(),
                                monitorIds = emptySet(),
                            ),
                        )
                    } catch (e: KumaException) {
                        println("empty weekdays rejected: ${e.error}")
                        null
                    }
                empty?.let {
                    created += it.id
                    println(
                        "empty weekdays accepted: ${awaitList(repo) { l ->
                            l.any { m ->
                                m.id == it.id
                            }
                        }.first { m -> m.id == it.id }}",
                    )
                }
            } finally {
                // Delete everything this test created (also leftovers of earlier runs).
                runCatching {
                    val leftovers =
                        repo.observeMaintenances().filterNotNull().first().filter {
                            it.title.startsWith(
                                PREFIX,
                            )
                        }
                    (created + leftovers.map { it.id }).distinct().forEach { id -> runCatching { repo.delete(id) } }
                    val remaining = awaitList(repo) { list -> list.none { it.title.startsWith(PREFIX) } }
                    assertFalse(remaining.any { it.title.startsWith(PREFIX) })
                    println("cleanup done, ${remaining.size} other maintenances left")
                }.onFailure { println("cleanup failed: $it") }
                m.logout()
                scope.cancel()
            }
        }

    private suspend fun awaitList(
        repo: KumaMaintenanceRepository,
        predicate: (List<Maintenance>) -> Boolean,
    ): List<Maintenance> = withTimeout(TIMEOUT) { repo.observeMaintenances().filterNotNull().first(predicate) }

    private fun print(m: Maintenance) {
        val slots =
            m.timeslots.joinToString {
                "${Instant.ofEpochMilli(it.startMillis)}..${Instant.ofEpochMilli(it.endMillis)}"
            }
        println(
            "#${m.id} ${m.title}: strategy=${m.strategy.wire} status=${m.status.wire} active=${m.active} " +
                "cron='${m.cron}' duration=${m.durationMinutes} " +
                "tz=${m.timezone}/${m.timezoneOption}${m.timezoneOffset} " +
                "range=${m.startDate}..${m.endDate} time=${m.startTime}..${m.endTime} slots=[$slots]",
        )
    }

    private companion object {
        const val PREFIX = "[P2-MAINT]"
        const val TIMEOUT = 20_000L
    }
}
