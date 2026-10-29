package net.kirkanos.uptimekuma.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerInfoTest {
    @Test
    fun `update is available when latest version differs`() {
        val info = ServerInfo(version = "2.0.0", latestVersion = "2.1.0")
        assertTrue(info.updateAvailable)
    }

    @Test
    fun `no update when latest version is unknown or equal`() {
        assertFalse(ServerInfo(version = "2.0.0").updateAvailable)
        assertFalse(ServerInfo(version = "2.0.0", latestVersion = "2.0.0").updateAvailable)
        // Kuma's update check can report an older release than the running one (seen: 2.5.8 vs 2.5.5).
        assertFalse(ServerInfo(version = "2.5.8", latestVersion = "2.5.5").updateAvailable)
    }

    @Test
    fun `monitor status maps heartbeat codes`() {
        assertEquals(MonitorStatus.UP, MonitorStatus.fromCode(1))
        assertEquals(MonitorStatus.MAINTENANCE, MonitorStatus.fromCode(3))
        assertEquals(MonitorStatus.PENDING, MonitorStatus.fromCode(42))
    }
}
