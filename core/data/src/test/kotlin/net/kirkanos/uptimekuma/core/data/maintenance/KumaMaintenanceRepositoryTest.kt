package net.kirkanos.uptimekuma.core.data.maintenance

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.KumaAcks
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private class FakeBackend : MaintenanceBackend {
    val snapshots = mutableMapOf<String, MutableStateFlow<List<JsonElement>?>>()
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val acks = mutableMapOf<String, JsonObject>()

    override fun snapshot(event: String): Flow<List<JsonElement>?> =
        snapshots.getOrPut(event) { MutableStateFlow(null) }

    override suspend fun call(
        event: String,
        vararg args: Any?,
    ): JsonObject {
        calls += event to args.toList()
        return KumaAcks.requireOk(event, acks[event] ?: json("""{"ok":true}"""))
    }
}

private fun json(text: String): JsonObject = KumaJson.json.parseToJsonElement(text).jsonObject

class KumaMaintenanceRepositoryTest {
    private val backend = FakeBackend()
    private val repository = KumaMaintenanceRepository(backend)

    @Test
    fun `observes the maintenanceList snapshot`() =
        runTest {
            repository.observeMaintenances().test {
                assertNull(awaitItem())
                backend.snapshots.getValue("maintenanceList").value = MaintenanceFixtures.eventArgs("maintenanceList")
                val list = awaitItem()
                assertEquals(MaintenanceStatus.UNDER_MAINTENANCE, list?.single()?.status)
                // Re-pushes with identical content (every important heartbeat) do not re-emit.
                backend.snapshots.getValue("maintenanceList").value = MaintenanceFixtures.eventArgs("maintenanceList")
                expectNoEvents()
            }
        }

    @Test
    fun `observes status pages`() =
        runTest {
            backend.snapshot("statusPageList")
            backend.snapshots.getValue("statusPageList").value = MaintenanceFixtures.eventArgs("statusPageList")
            repository.observeStatusPages().test {
                assertEquals(listOf(MaintenanceStatusPage(1, "Dev Status", "dev")), awaitItem())
            }
        }

    @Test
    fun `get combines maintenance, monitors and status pages`() =
        runTest {
            backend.acks["getMaintenance"] = MaintenanceFixtures.ack("getMaintenance")
            backend.acks["getMonitorMaintenance"] = MaintenanceFixtures.ack("getMonitorMaintenance")
            backend.acks["getMaintenanceStatusPage"] = MaintenanceFixtures.ack("getMaintenanceStatusPage")
            val detail = repository.get(1)
            assertEquals("Dev maintenance (single)", detail.maintenance.title)
            assertEquals(listOf(10L), detail.monitorIds)
            assertEquals(listOf(MaintenanceStatusPage(1, "Dev Status")), detail.statusPages)
            assertEquals(
                listOf("getMaintenance", "getMonitorMaintenance", "getMaintenanceStatusPage"),
                backend.calls.map { it.first },
            )
            assertEquals(listOf<Any?>(1L), backend.calls.first().second)
            // Same request shape as the recorded fixture.
            assertEquals(MaintenanceFixtures.request("getMaintenance").single().toString(), "1")
        }

    @Test
    fun `add then replaces monitor and status page links`() =
        runTest {
            backend.acks["addMaintenance"] =
                json("""{"ok":true,"msg":"successAdded","msgi18n":true,"maintenanceID":42}""")
            val result =
                repository.save(
                    MaintenanceDraft(title = "t", monitorIds = setOf(3, 1), statusPageIds = setOf(1)),
                )
            assertEquals(42L, result.id)
            assertNull(result.linkError)
            assertEquals(
                listOf("addMaintenance", "addMonitorMaintenance", "addMaintenanceStatusPage"),
                backend.calls.map { it.first },
            )
            assertEquals(listOf<Any?>(42L, json("""{"v":[{"id":1},{"id":3}]}""")["v"]), backend.calls[1].second)
            assertEquals(listOf<Any?>(42L, json("""{"v":[{"id":1}]}""")["v"]), backend.calls[2].second)
        }

    @Test
    fun `edit uses editMaintenance and the draft id`() =
        runTest {
            backend.acks["editMaintenance"] = json("""{"ok":true,"msg":"Saved.","msgi18n":true,"maintenanceID":7}""")
            val result = repository.save(MaintenanceDraft(id = 7, title = "t"))
            assertEquals(7L, result.id)
            assertEquals("editMaintenance", backend.calls.first().first)
            assertEquals(
                7L,
                (
                    backend.calls
                        .first()
                        .second
                        .single() as JsonObject
                )["id"].toString().toLong(),
            )
            assertEquals(listOf<Any?>(7L, json("""{"v":[]}""")["v"]), backend.calls[1].second)
        }

    @Test
    fun `link failure is reported but keeps the id`() =
        runTest {
            backend.acks["addMaintenance"] = json("""{"ok":true,"maintenanceID":5}""")
            backend.acks["addMonitorMaintenance"] = json("""{"ok":false,"msg":"Permission denied."}""")
            val result = repository.save(MaintenanceDraft(title = "t", monitorIds = setOf(1)))
            assertEquals(5L, result.id)
            assertEquals(KumaError(KumaError.Kind.SERVER_REJECTED, "Permission denied."), result.linkError)
            assertEquals(3, backend.calls.size)
        }

    @Test
    fun `failed add throws and does not link`() =
        runTest {
            backend.acks["addMaintenance"] = json("""{"ok":false,"msg":"Invalid start date"}""")
            val e = assertThrows<KumaException> { repository.save(MaintenanceDraft(title = "t")) }
            assertEquals("Invalid start date", e.error.message)
            assertEquals(1, backend.calls.size)
        }

    @Test
    fun `pause, resume, delete and refresh emit their events with the id`() =
        runTest {
            repository.pause(2)
            repository.resume(2)
            repository.delete(2)
            repository.refresh()
            assertEquals(
                listOf(
                    "pauseMaintenance" to listOf<Any?>(2L),
                    "resumeMaintenance" to listOf<Any?>(2L),
                    "deleteMaintenance" to listOf<Any?>(2L),
                    "getMaintenanceList" to emptyList(),
                ),
                backend.calls,
            )
        }
}
