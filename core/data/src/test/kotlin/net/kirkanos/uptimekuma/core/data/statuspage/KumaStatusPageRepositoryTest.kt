package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kirkanos.uptimekuma.core.data.FakeConfigStore
import net.kirkanos.uptimekuma.core.data.FakeKumaApi
import net.kirkanos.uptimekuma.core.data.FakeKumaCache
import net.kirkanos.uptimekuma.core.data.FakeSecretStore
import net.kirkanos.uptimekuma.core.data.Fixtures
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaApiFactory
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Repository against a real [KumaConnectionManager] with a scripted socket and fixture REST responses. */
@OptIn(ExperimentalCoroutinesApi::class)
class KumaStatusPageRepositoryTest {
    private val requests = mutableListOf<Pair<String, List<Any?>>>()
    private val httpRequests = mutableListOf<String>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val devConfig = StatusPageJsonTest.ack("getStatusPage.ok.json").getValue("config").jsonObject
    private val statusPageListEvent =
        KumaEvent.Raw(
            "statusPageList",
            StatusPageJsonTest
                .fixture("statusPageList.json")
                .getValue("samples")
                .jsonArray[0]
                .jsonObject
                .getValue("args")
                .jsonArray
                .toList(),
        )

    private val incidentJson =
        """{"id":5,"style":"warning","title":"T","content":"C","pin":true,"active":true,
            |"createdDate":"2026-10-29 10:00:00","lastUpdatedDate":null,"status_page_id":1}
        """.trimMargin()

    private var handler: (String, List<Any?>) -> String = { event, args ->
        when (event) {
            "getStatusPage" -> {
                val slug = args[0] as String
                """{"ok":true,"config":${JsonObject(devConfig + ("slug" to JsonPrimitive(slug)))}}"""
            }

            "addStatusPage" -> {
                """{"ok":true,"msg":"successAdded","msgi18n":true,"slug":"${args[1]}"}"""
            }

            "saveStatusPage" -> {
                """{"ok":true,"publicGroupList":[{"id":9,"name":"G","monitorList":[]}]}"""
            }

            "postIncident" -> {
                """{"ok":true,"incident":$incidentJson}"""
            }

            "getIncidentHistory" -> {
                """{"ok":true,"incidents":[$incidentJson],"total":1,"nextCursor":null,"hasMore":false}"""
            }

            else -> {
                """{"ok":true}"""
            }
        }
    }

    /** [FakeKumaApi] plus the generic request used by feature repositories. */
    private inner class RawApi(
        inner: FakeKumaApi,
    ) : KumaApi by inner {
        override suspend fun requestRaw(
            event: String,
            args: List<Any?>,
            timeoutMillis: Long,
        ): List<JsonElement> {
            requests += event to args
            return listOf(KumaJson.json.parseToJsonElement(handler(event, args)))
        }
    }

    private val rest =
        StatusPageRestClient { url ->
            httpRequests += url
            val name = if ("/heartbeat/" in url) "status-page.heartbeat.json" else "status-page.json"
            StatusPageJsonTest.restRaw(name).let { (code, body) -> HttpResult(code, body) }
        }

    @AfterEach
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.repository(): KumaStatusPageRepository {
        val secrets = FakeSecretStore().apply { values[KumaConnectionManager.TOKEN_KEY] = "jwt-1" }
        val manager =
            KumaConnectionManager(
                apiFactory =
                    KumaApiFactory { url ->
                        RawApi(FakeKumaApi(url).apply { postLoginEvents = Fixtures.loginBurst() + statusPageListEvent })
                    },
                cache = FakeKumaCache(),
                configStore = FakeConfigStore(ServerConfig(baseUrl = "https://kuma.example/sub", username = "admin")),
                secretStore = secrets,
                logoutHooks = emptySet(),
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it },
                timings = ConnectionTimings(readyTimeoutMillis = 5_000, infoTimeoutMillis = 1_000),
            )
        manager.setForeground(true)
        advanceUntilIdle()
        return KumaStatusPageRepository(manager, rest)
    }

    @Test
    fun `list comes from the statusPageList snapshot`() =
        runTest {
            val repo = repository()
            assertEquals(listOf("dev"), repo.observeStatusPages().first()!!.map { it.slug })
        }

    @Test
    fun `create normalizes the slug and shows the page before the server re-sends the list`() =
        runTest {
            val repo = repository()
            assertEquals("p2-status-test", repo.create(" [P2-STATUS] Test ", " P2-Status-Test "))
            assertEquals(listOf("addStatusPage", "[P2-STATUS] Test", "p2-status-test"), requests[0].flat())
            assertEquals("getStatusPage" to listOf<Any?>("p2-status-test"), requests[1])
            assertEquals(
                listOf("dev", "p2-status-test"),
                repo
                    .observeStatusPages()
                    .first()!!
                    .map { it.slug }
                    .sorted(),
            )
        }

    @Test
    fun `save sends current slug, edited admin config, kept logo and groups, then renames locally`() =
        runTest {
            val repo = repository()
            val config = repo.loadConfig("dev").copy(slug = "dev-2", title = "Renamed")
            val groups =
                repo.save(
                    "dev",
                    config,
                    listOf(
                        StatusPageGroup(
                            name = "G",
                            monitors = listOf(StatusPageMonitor(1, "HTTP", "http", sendUrl = true)),
                        ),
                    ),
                )
            assertEquals(listOf(9L), groups.map { it.id })
            val (event, args) = requests.last()
            assertEquals("saveStatusPage", event)
            assertEquals("dev", args[0])
            val sent = args[1] as JsonObject
            assertEquals(devConfig.keys, sent.keys)
            assertEquals("dev-2", sent.getValue("slug").jsonPrimitive.content)
            assertEquals("Renamed", sent.getValue("title").jsonPrimitive.content)
            assertEquals("/icon.svg", args[2])
            assertEquals(
                KumaJson.json.parseToJsonElement("""[{"name":"G","monitorList":[{"id":1,"sendUrl":true}]}]"""),
                args[3] as JsonArray,
            )
            val pages = repo.observeStatusPages().first()!!
            assertEquals(listOf("dev-2"), pages.map { it.slug })
            assertEquals("Renamed", pages.single().title)
        }

    @Test
    fun `delete removes the page locally`() =
        runTest {
            val repo = repository()
            repo.delete("dev")
            assertEquals("deleteStatusPage" to listOf<Any?>("dev"), requests.single())
            assertEquals(emptyList<Any>(), repo.observeStatusPages().first())
        }

    @Test
    fun `public page of the own server uses REST below the sub-path and the uncached pinned incident`() =
        runTest {
            val repo = repository()
            val page = repo.loadPublic("dev")
            assertEquals("https://kuma.example/sub/api/status-page/dev", httpRequests.single())
            assertEquals(listOf(5L), page.incidents.map { it.id }, "from getIncidentHistory, REST had none")
            assertEquals("getIncidentHistory" to listOf<Any?>("dev", null), requests.single())
            val beats = repo.loadHeartbeats("dev")
            assertEquals(2, beats.beats.size)
        }

    @Test
    fun `public page of another server needs no socket`() =
        runTest {
            val repo = repository()
            val page = repo.loadPublic("dev", StatusPageAddress("https://other.example", "dev"))
            assertEquals("https://other.example/api/status-page/dev", httpRequests.single())
            assertEquals(emptyList<Any>(), page.incidents)
            assertEquals(emptyList<Any>(), requests)
        }

    @Test
    fun `incidents post, unpin and history`() =
        runTest {
            val repo = repository()
            val incident = repo.postIncident("dev", IncidentDraft("T", "C", IncidentStyle.WARNING))
            assertEquals(5L, incident.id)
            assertEquals(IncidentStyle.WARNING, incident.style)
            val (event, args) = requests.last()
            assertEquals("postIncident", event)
            assertEquals("dev", args[0])
            assertEquals(KumaJson.json.parseToJsonElement("""{"title":"T","content":"C","style":"warning"}"""), args[1])
            repo.unpinIncident("dev")
            assertEquals("unpinIncident" to listOf<Any?>("dev"), requests.last())
            val history = repo.loadIncidentHistory("dev", null)
            assertEquals(1, history.total)
            assertNull(history.nextCursor)
        }

    @Test
    fun `ok false surfaces as KumaException`() =
        runTest {
            handler = { _, _ -> """{"ok":false,"msg":"No slug?"}""" }
            val repo = repository()
            assertEquals("No slug?", assertThrows<KumaException> { repo.loadConfig("nope") }.error.message)
        }

    private fun Pair<String, List<Any?>>.flat(): List<Any?> = listOf(first) + second
}
