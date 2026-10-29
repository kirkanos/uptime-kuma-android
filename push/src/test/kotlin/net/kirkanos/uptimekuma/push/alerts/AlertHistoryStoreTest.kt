package net.kirkanos.uptimekuma.push.alerts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.push.notify.toRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AlertHistoryStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, AlertHistoryStore.FILE_NAME)

    private fun TestScope.store(): AlertHistoryStore =
        AlertHistoryStore(AlertHistoryStore.create(backgroundScope) { file }, backgroundScope)

    private fun record(
        at: Long,
        status: MonitorStatus? = MonitorStatus.DOWN,
    ) = AlertRecord(at, monitorId = 1, monitorName = "API", status = status, message = "m$at", eventTimeMillis = at - 5)

    @Test
    fun `starts empty and keeps newest first`() =
        runTest {
            val store = store()
            assertTrue(store.observeAlerts().first().isEmpty())
            store.add(record(1_000))
            store.add(record(3_000))
            store.add(record(2_000))
            assertEquals(listOf(3_000L, 2_000L, 1_000L), store.observeAlerts().first().map { it.receivedAtMillis })
        }

    @Test
    fun `keeps at most 200 entries`() =
        runTest {
            val store = store()
            repeat(AlertHistoryStore.MAX_ENTRIES + 5) { store.add(record(it.toLong())) }
            val alerts = store.observeAlerts().first()
            assertEquals(AlertHistoryStore.MAX_ENTRIES, alerts.size)
            assertEquals((AlertHistoryStore.MAX_ENTRIES + 4).toLong(), alerts.first().receivedAtMillis)
            assertEquals(5L, alerts.last().receivedAtMillis)
        }

    @Test
    fun `round trips all fields through the file`() =
        runTest {
            val records =
                listOf(
                    record(2_000, status = null).copy(notified = false),
                    AlertRecord(1_000, null, null, null, "Test", eventTimeMillis = null),
                    record(500, status = MonitorStatus.MAINTENANCE),
                )
            val first = store()
            records.forEach { first.add(it) }
            val written = file.readText()
            assertTrue(written.contains("\"alerts\""))

            val decoded = AlertRecordsSerializer.readFrom(file.inputStream())
            assertEquals(records.sortedByDescending { it.receivedAtMillis }, decoded)
        }

    @Test
    fun `corrupt file is replaced by an empty history`() =
        runTest {
            file.writeText("{not json")
            val store = store()
            assertTrue(store.observeAlerts().first().isEmpty())
            store.add(record(1))
            assertEquals(1, store.observeAlerts().first().size)
        }

    @Test
    fun `clear empties the history and record writes in the background`() =
        runTest {
            val store = store()
            store.record(record(1))
            // DataStore writes on its own IO thread: wait for the emission instead of virtual time.
            assertEquals(1, store.observeAlerts().first { it.isNotEmpty() }.size)
            store.clear()
            assertTrue(store.observeAlerts().first().isEmpty())
        }

    @Test
    fun `push event maps to a record`() {
        val event =
            PushEvent(
                monitorId = 3,
                monitorName = "Web",
                status = MonitorStatus.UP,
                message = "200 - OK",
                timeMillis = 900,
            )
        assertEquals(
            AlertRecord(1_000, 3, "Web", MonitorStatus.UP, "200 - OK", eventTimeMillis = 900, notified = false),
            event.toRecord(nowMillis = 1_000, notified = false),
        )
    }
}
