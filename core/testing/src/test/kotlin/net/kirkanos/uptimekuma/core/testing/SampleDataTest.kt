package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleDataTest {
    private val now = 1_761_700_000_000L

    @Test
    fun `sample data covers every interesting status`() {
        val statuses = SampleData.monitors(now).map { it.displayStatus }.toSet()
        assertTrue(
            statuses.containsAll(
                listOf(
                    DisplayStatus.UP,
                    DisplayStatus.DOWN,
                    DisplayStatus.PENDING,
                    DisplayStatus.MAINTENANCE,
                    DisplayStatus.PAUSED,
                ),
            ),
        )
    }

    @Test
    fun `beats are oldest first and in the past`() {
        SampleData.monitors(now).forEach { overview ->
            val times = overview.recentBeats.map { it.timeMillis }
            assertEquals(times.sorted(), times)
            assertTrue(times.last() <= now)
        }
    }

    @Test
    fun `group children reference their parent`() {
        val monitors = SampleData.monitors(now).associateBy { it.monitor.id }
        val group = monitors.values.single { it.monitor.isGroup }
        group.monitor.childrenIds.forEach { assertEquals(group.monitor.id, monitors.getValue(it).monitor.parentId) }
    }

    @Test
    fun `fake repository pages important events and pauses monitors`() =
        runTest {
            val repository = FakeMonitorRepository.withSampleData(now)
            val first = repository.loadImportantEvents(null, 0, 10)
            val second = repository.loadImportantEvents(null, 10, 10)
            assertEquals(10, first.size)
            assertFalse(first.any { it in second })
            assertTrue(first.first().timeMillis >= second.first().timeMillis)

            repository.pause(1)
            assertFalse(
                repository.monitors.value
                    .first { it.monitor.id == 1L }
                    .monitor.active,
            )
        }
}
