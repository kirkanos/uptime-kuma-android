package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.domain.TagSyncException
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.kumaclient.KumaAcks
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.testing.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakeTagRepository
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Records calls and answers them with recorded acks (`fixtures/kuma-2.5.8/ack`) or canned responses. */
internal class FakeEditorRemote : EditorRemote {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val responses = mutableMapOf<String, (List<Any?>) -> JsonElement>()
    val snapshots = mutableMapOf<String, MutableStateFlow<List<JsonElement>?>>()

    override suspend fun call(
        event: String,
        vararg args: Any?,
    ): JsonObject {
        calls += event to args.toList()
        val response = responses[event]?.invoke(args.toList()) ?: JsonValues.toJson(mapOf("ok" to true))
        return KumaAcks.requireOk(event, response)
    }

    override fun snapshot(event: String): Flow<List<JsonElement>?> =
        snapshots.getOrPut(event) { MutableStateFlow(null) }

    fun events(): List<String> = calls.map { it.first }
}

internal object AckFixtures {
    private val dir: File by lazy {
        listOfNotNull(System.getProperty("kuma.fixtures")?.let(::File), File("../../fixtures/kuma-2.5.8")).first {
            it.isDirectory
        }
    }

    fun response(name: String): JsonElement =
        JsonValues.parseOrNull(File(dir, "ack/$name.json").readText())!!.jsonObject.getValue("response")
}

class MonitorEditorRepositoryTest {
    private val remote = FakeEditorRemote()
    private val monitors = FakeMonitorRepository(SampleData.monitors(0))
    private val notifications =
        FakeKumaNotificationRepository(
            listOf(
                KumaNotification(id = 4, name = "ntfy", type = "ntfy", isDefault = true),
                KumaNotification(id = 5, name = "mail", type = "smtp"),
            ),
        )
    private val tags = FakeTagRepository()
    private val repo = KumaMonitorEditorRepository(remote, monitors, notifications, tags)
    private val spec = MonitorSpec.bundled

    @Test
    fun `loadForEdit decodes getMonitor with tags`() =
        runTest {
            remote.responses["getMonitor"] = { AckFixtures.response("getMonitor.tagged") }
            val draft = repo.loadForEdit(8)
            assertEquals(listOf("getMonitor" to listOf<Any?>(8L)), remote.calls)
            assertEquals(8L, draft.id)
            assertEquals("http", draft.type)
            assertEquals(
                listOf(MonitorTag(1, "env", "#059669", "dev"), MonitorTag(2, "team", "#7C3AED", "android")),
                draft.tags,
            )
            assertEquals(listOf("200-299"), draft["accepted_statuscodes"])
        }

    @Test
    fun `new draft preselects default notification and default proxy`() =
        runTest {
            remote.snapshots["proxyList"] =
                MutableStateFlow(
                    listOf(
                        JsonValues.toJson(
                            listOf(
                                mapOf("id" to 1, "protocol" to "http", "host" to "a", "port" to 3128, "default" to 0),
                                mapOf("id" to 2, "protocol" to "socks5", "host" to "b", "port" to 1080, "default" to 1),
                            ),
                        ),
                    ),
                )
            val draft = repo.newDraft("http")
            assertNull(draft.id)
            assertEquals(mapOf("4" to true), draft["notificationIDList"])
            assertEquals(2L, draft["proxyId"])
        }

    @Test
    fun `add sends the add payload then attaches tags`() =
        runTest {
            remote.responses["add"] = { AckFixtures.response("add.ok") }
            val draft =
                MonitorDrafts
                    .newDraft(spec, "http")
                    .with("name", "n")
                    .with("url", "https://example.com")
                    .copy(tags = listOf(MonitorTag(1, "env", "#059669", "prod")))
            val id = repo.save(draft)
            assertEquals(18L, id)
            assertEquals(listOf("add", "addMonitorTag"), remote.events())
            val payload =
                remote.calls
                    .first()
                    .second
                    .single() as JsonObject
            assertFalse("tags" in payload)
            assertFalse("id" in payload)
            assertEquals(JsonValues.toJson(listOf("200-299")), payload["accepted_statuscodes"])
            assertEquals(listOf<Any?>(1L, 18L, "prod"), remote.calls[1].second)
        }

    @Test
    fun `edit sends editMonitor with id and diffs tags against the server`() =
        runTest {
            remote.responses["editMonitor"] = { AckFixtures.response("editMonitor.ok") }
            remote.responses["getMonitor"] = { AckFixtures.response("getMonitor.tagged") }
            val loaded = MonitorDrafts.fromMonitorJson(JsonValues.toKotlin(tagged()) as Map<String, Any?>)
            val edited =
                loaded.with("name", "renamed").copy(
                    tags = listOf(MonitorTag(1, "env", "#059669", "prod"), MonitorTag(3, "critical", "#DC2626")),
                )
            assertEquals(8L, repo.save(edited))
            assertEquals(
                listOf("editMonitor", "getMonitor", "deleteMonitorTag", "editMonitorTag", "addMonitorTag"),
                remote.events(),
            )
            val payload =
                remote.calls
                    .first()
                    .second
                    .single() as JsonObject
            assertEquals(JsonValues.toJson(8L), payload["id"])
            assertEquals(JsonValues.toJson("renamed"), payload["name"])
            assertEquals(listOf<Any?>(2L, 8L, "android"), remote.calls[2].second)
            assertEquals(listOf<Any?>(1L, 8L, "prod"), remote.calls[3].second)
            assertEquals(listOf<Any?>(3L, 8L, ""), remote.calls[4].second)
        }

    @Test
    fun `server validation errors surface as KumaException`() =
        runTest {
            remote.responses["add"] = { AckFixtures.response("add.validation-error.interval") }
            val e =
                assertThrows<net.kirkanos.uptimekuma.core.model.KumaException> {
                    repo.save(MonitorDrafts.newDraft(spec, "http").with("interval", 0))
                }
            assertEquals("Interval cannot be less than 1 seconds", e.error.message)
        }

    @Test
    fun `tag failure after a successful add reports the new id`() =
        runTest {
            remote.responses["add"] = { AckFixtures.response("add.ok") }
            remote.responses["addMonitorTag"] = { JsonValues.toJson(mapOf("ok" to false, "msg" to "boom")) }
            val draft =
                MonitorDrafts
                    .newDraft(
                        spec,
                        "group",
                    ).with("name", "g")
                    .copy(tags = listOf(MonitorTag(1, "env", "#000")))
            val e = assertThrows<TagSyncException> { repo.save(draft) }
            assertEquals(18L, e.monitorId)
        }

    @Test
    fun `clone loads the source and resets identity`() =
        runTest {
            remote.responses["getMonitor"] = { AckFixtures.response("getMonitor.push") }
            val clone = repo.clone(7) { "Kopie von $it" }
            assertNull(clone.id)
            assertEquals("Kopie von Push demo", clone.name)
            assertTrue((clone["pushToken"] as String).matches(Regex("[A-Za-z0-9]{32}")))
        }

    @Test
    fun `delete with children uses deleteMonitor directly`() =
        runTest {
            repo.delete(11, deleteChildren = true)
            assertEquals(listOf("deleteMonitor" to listOf<Any?>(11L, true)), remote.calls)
            repo.delete(1)
            assertEquals(listOf(1L), monitors.deletedIds)
        }

    @Test
    fun `pickers combine notifications, snapshots, groups and tags`() =
        runTest {
            remote.snapshots["dockerHostList"] =
                MutableStateFlow(
                    listOf(
                        JsonValues.toJson(
                            listOf(
                                mapOf(
                                    "id" to 3,
                                    "name" to "local",
                                    "dockerType" to "socket",
                                ),
                            ),
                        ),
                    ),
                )
            remote.snapshots["remoteBrowserList"] = MutableStateFlow(listOf(JsonArray(emptyList())))
            val pickers = repo.observePickers().first()
            assertEquals(listOf(4L, 5L), pickers.notifications.map { it.id })
            assertTrue(pickers.notifications.first().isDefault)
            assertEquals(listOf("local"), pickers.dockerHosts.map { it.label })
            assertTrue(pickers.remoteBrowsers.isEmpty())
            assertEquals(
                SampleData.monitors(0).filter { it.monitor.isGroup }.map { it.monitor.id },
                pickers.groups.map { it.id },
            )
            assertEquals(SampleData.tags, pickers.tags)
        }

    @Test
    fun `tag repository decodes getTags and keeps changes in memory`() =
        runTest {
            val tagRepo = KumaTagRepository(remote, monitors)
            remote.responses["getTags"] = { AckFixtures.response("getTags") }
            remote.responses["addTag"] = { args ->
                val obj = args.single() as JsonObject
                JsonValues.toJson(
                    mapOf(
                        "ok" to true,
                        "tag" to mapOf("id" to 9, "name" to JsonValues.toKotlin(obj["name"]), "color" to "#000"),
                    ),
                )
            }
            tagRepo.refresh()
            assertEquals(listOf("env", "team"), tagRepo.observeTags().first().map { it.name })
            val created = tagRepo.create(" prod ", "#000")
            assertEquals(Tag(9, "prod", "#000"), created)
            tagRepo.update(created.copy(name = "production"))
            assertEquals(listOf("env", "production", "team"), tagRepo.observeTags().first().map { it.name })
            tagRepo.delete(9)
            assertEquals(listOf("env", "team"), tagRepo.observeTags().first().map { it.name })
            assertEquals(
                listOf("getTags", "addTag", "editTag", "getMonitorList", "deleteTag", "getMonitorList"),
                remote.events(),
            )
            val editArg = remote.calls[2].second.single() as JsonObject
            assertEquals(JsonValues.toJson(mapOf("id" to 9, "name" to "production", "color" to "#000")), editArg)
        }

    private fun tagged(): JsonElement = AckFixtures.response("getMonitor.tagged").jsonObject.getValue("monitor")
}
