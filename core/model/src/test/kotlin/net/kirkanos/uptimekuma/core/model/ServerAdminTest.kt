package net.kirkanos.uptimekuma.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerAdminTest {
    @Test
    fun `update is only available for a strictly newer release`() {
        // The dev server reports latestVersion 2.5.5 while running 2.5.8.
        assertFalse(ServerDetails(version = "2.5.8", latestVersion = "2.5.5").updateAvailable)
        assertFalse(ServerDetails(version = "2.5.8", latestVersion = "2.5.8").updateAvailable)
        assertTrue(ServerDetails(version = "2.5.8", latestVersion = "2.10.0").updateAvailable)
        assertTrue(ServerDetails(version = "2.0.0-beta.1", latestVersion = "2.0.0").updateAvailable)
        assertFalse(ServerDetails(version = "2.5.8", latestVersion = null).updateAvailable)
        assertFalse(ServerDetails(version = null, latestVersion = "2.6.0").updateAvailable)
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(ServerDetails.compareVersions("2.10.0", "2.9.9") > 0)
        assertTrue(ServerDetails.compareVersions("v2.5", "2.5.0") == 0)
        assertTrue(ServerDetails.compareVersions("2.0.0-beta.2", "2.0.0-beta.1") > 0)
    }

    @Test
    fun `sqlite detection ignores case`() {
        assertTrue(ServerDetails(dbType = "SQLite").isSqlite)
        assertFalse(ServerDetails(dbType = "mariadb").isSqlite)
        assertFalse(ServerDetails().isSqlite)
    }

    @Test
    fun `extracts the 2FA secret from the otpauth URI`() {
        assertEquals("JBSWY3DP", TwoFactorSetup("otpauth://totp/Uptime%20Kuma:admin?secret=JBSWY3DP").secret)
        assertEquals("ABC", TwoFactorSetup("otpauth://totp/x?issuer=Kuma&secret=ABC&digits=6").secret)
        assertNull(TwoFactorSetup("otpauth://totp/x").secret)
    }

    @Test
    fun `entry page values`() {
        assertEquals("statusPage-dev", EntryPage.statusPage("dev"))
        assertEquals("dev", EntryPage.slugOf("statusPage-dev"))
        assertNull(EntryPage.slugOf(EntryPage.DASHBOARD))
    }

    @Test
    fun `wire values`() {
        assertEquals(ProxyProtocol.SOCKS5H, ProxyProtocol.fromWire("socks5h"))
        assertEquals(ProxyProtocol.HTTP, ProxyProtocol.fromWire(null))
        assertEquals(DockerHostType.TCP, DockerHostType.fromWire("tcp"))
        assertEquals(ApiKeyStatus.EXPIRED, ApiKeyStatus.fromWire("expired"))
        assertEquals(ApiKeyStatus.UNKNOWN, ApiKeyStatus.fromWire(""))
        assertEquals("socks5://h:1080", Proxy(1, ProxyProtocol.SOCKS5, "h", 1080, auth = false).label)
    }
}
