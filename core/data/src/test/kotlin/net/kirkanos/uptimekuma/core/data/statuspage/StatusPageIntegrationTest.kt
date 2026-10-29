package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Status page round trip against the dev Kuma (`infra/docker-compose.dev.yml`) with the real socket and
 * HTTP clients: create `p2-status-test`, save two groups' worth of monitors, post + unpin an incident,
 * read it via REST, delete it. Never touches the seeded page `dev`.
 * Run: `./gradlew :core:data:testDebugUnitTest -Pintegration --tests '*StatusPageIntegrationTest*'`.
 */
@Tag("integration")
class StatusPageIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"

    @Test
    @Suppress("LongMethod")
    fun `create, save groups, incident, REST read and delete`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = FakeKumaCache()
            val manager =
                KumaConnectionManager(
                    SocketKumaApiFactory(),
                    cache,
                    FakeConfigStore(),
                    FakeSecretStore(),
                    emptySet(),
                    scope,
                    ConnectionTimings(),
                )
            val repo = KumaStatusPageRepository(manager, StatusPageRestClient(UrlConnectionHttpGetter(Dispatchers.IO)))
            try {
                manager.setForeground(true)
                assertEquals(LoginResult.Success, manager.login(url, user, password, null))
                withTimeout(TIMEOUT) { manager.state.first { it is ConnectionState.Connected } }
                repo.refreshList()
                val before = withTimeout(TIMEOUT) { repo.observeStatusPages().first { it != null } }!!
                println("status pages before: ${before.map { it.slug }}")
                assertTrue(before.any { it.slug == "dev" })
                if (before.any { it.slug == SLUG }) repo.delete(SLUG) // leftover of an aborted run
                val devBefore = repo.loadConfig("dev")

                assertEquals(SLUG, repo.create(TITLE, "P2-Status-Test"))
                assertTrue(repo.observeStatusPages().first()!!.any { it.slug == SLUG && it.title == TITLE })

                withTimeout(TIMEOUT) { while (cache.monitors.size < 2) delay(100) }
                val seeded =
                    cache.monitors.values
                        .filter { it.type != "group" }
                        .sortedBy { it.id }
                        .take(2)
                val config = repo.loadConfig(SLUG)
                println("created: $config")
                val saved =
                    repo.save(
                        SLUG,
                        config.copy(
                            description = "$TITLE description",
                            footerText = "$TITLE footer",
                            autoRefreshInterval = 120,
                        ),
                        listOf(
                            StatusPageGroup(
                                name = "[P2-STATUS] Services",
                                monitors =
                                    listOf(
                                        StatusPageMonitor(seeded[0].id, seeded[0].name, seeded[0].type, sendUrl = true),
                                        StatusPageMonitor(seeded[1].id, seeded[1].name, seeded[1].type),
                                    ),
                            ),
                        ),
                    )
                println("saved groups: $saved")
                assertNotNull(saved.single().id)

                val reloaded = repo.loadConfig(SLUG)
                assertEquals("$TITLE description", reloaded.description)
                assertEquals(120, reloaded.autoRefreshInterval)
                assertEquals(config.icon, reloaded.icon, "logo kept")

                val address = StatusPageAddress(url, SLUG)
                val public = repo.loadPublic(SLUG, address)
                println("REST page: ${public.config.title}, groups=${public.groups}")
                assertEquals(TITLE, public.config.title)
                assertEquals(
                    seeded.map { it.id },
                    public.groups
                        .single()
                        .monitors
                        .map { it.id },
                )
                assertTrue(
                    public.groups
                        .single()
                        .monitors[0]
                        .sendUrl,
                )
                val beats = repo.loadHeartbeats(SLUG, address)
                println("REST heartbeats: ${beats.beats.mapValues { it.value.size }}, uptime=${beats.uptime24h}")
                assertEquals(seeded.map { it.id }.toSet(), beats.beats.keys)

                val incident =
                    repo.postIncident(
                        SLUG,
                        IncidentDraft("[P2-STATUS] Incident", "Investigating", IncidentStyle.WARNING),
                    )
                println("posted: $incident")
                assertTrue(incident.pin && incident.active)
                // The REST page is cached (5 min) and postIncident does not clear it; the history endpoint is fresh.
                val restHistory = repo.loadPublicIncidentHistory(address, null)
                println("REST incident history: ${restHistory.incidents}")
                assertEquals(incident.id, restHistory.incidents.first().id)
                assertEquals(
                    listOf(incident.id),
                    repo.loadPublic(SLUG).incidents.map { it.id },
                    "own server: socket overlay",
                )

                repo.unpinIncident(SLUG)
                val afterUnpin = repo.loadIncidentHistory(SLUG, null).incidents.first { it.id == incident.id }
                assertFalse(afterUnpin.pin)
                assertTrue(repo.loadPublic(SLUG).incidents.isEmpty())

                repo.delete(SLUG)
                assertFalse(repo.observeStatusPages().first()!!.any { it.slug == SLUG })
                assertThrows<KumaException> { repo.loadConfig(SLUG) }
                assertEquals(devBefore, repo.loadConfig("dev"), "seeded page untouched")
                println("deleted $SLUG; dev unchanged")
            } finally {
                runCatching {
                    if (repo.observeStatusPages().first()?.any { it.slug == SLUG } ==
                        true
                    ) {
                        repo.delete(SLUG)
                    }
                }
                runCatching { manager.logout() }
                scope.cancel()
            }
        }

    private companion object {
        const val SLUG = "p2-status-test"
        const val TITLE = "[P2-STATUS] Test"
        const val TIMEOUT = 20_000L
    }
}
