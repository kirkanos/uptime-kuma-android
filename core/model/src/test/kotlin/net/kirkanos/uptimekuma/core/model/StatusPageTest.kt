package net.kirkanos.uptimekuma.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StatusPageTest {
    @Test
    fun `slug rules match checkSlug after trim and lower-case`() {
        assertNull(StatusPageSlug.validate("dev"))
        assertNull(StatusPageSlug.validate(" My-Page-2 "))
        assertEquals("my-page-2", StatusPageSlug.normalize(" My-Page-2 "))
        assertEquals(StatusPageSlug.Problem.EMPTY, StatusPageSlug.validate("  "))
        assertEquals(StatusPageSlug.Problem.INVALID_CHARACTERS, StatusPageSlug.validate("my page"))
        assertEquals(StatusPageSlug.Problem.INVALID_CHARACTERS, StatusPageSlug.validate("über"))
        assertEquals(StatusPageSlug.Problem.INVALID_CHARACTERS, StatusPageSlug.validate("a_b"))
        assertEquals(StatusPageSlug.Problem.CONSECUTIVE_OR_EDGE_DASH, StatusPageSlug.validate("a--b"))
        assertEquals(StatusPageSlug.Problem.CONSECUTIVE_OR_EDGE_DASH, StatusPageSlug.validate("-a"))
        assertEquals(StatusPageSlug.Problem.CONSECUTIVE_OR_EDGE_DASH, StatusPageSlug.validate("a-"))
    }

    @Test
    fun `slug suggestion from title`() {
        assertEquals("p2-status-test", StatusPageSlug.fromTitle("[P2-STATUS] Test"))
        assertEquals("my-status", StatusPageSlug.fromTitle("  My  Status! "))
        assertEquals("", StatusPageSlug.fromTitle("!!!"))
    }

    @Test
    fun `public links are parsed with sub-path`() {
        assertEquals(
            StatusPageAddress("https://status.example.org", "dev"),
            StatusPageAddress.parse("https://status.example.org/status/dev"),
        )
        assertEquals(
            StatusPageAddress("https://h.example/kuma", "my-page"),
            StatusPageAddress.parse(" h.example/kuma/status/My-Page/?x=1#y "),
        )
        assertEquals(
            StatusPageAddress("http://localhost:3001", "dev"),
            StatusPageAddress.parse("http://localhost:3001/status/dev/rss"),
        )
        assertEquals(StatusPageAddress("https://h", "default"), StatusPageAddress.parse("https://h/status"))
        assertEquals(
            StatusPageAddress("https://h/status", "status"),
            StatusPageAddress.parse("https://h/status/status/status"),
        )
        assertEquals(StatusPageAddress("https://h", "status"), StatusPageAddress.parse("https://h/status/status/rss"))
        assertEquals("https://h.example/kuma/status/dev", StatusPageAddress("https://h.example/kuma/", "dev").webUrl)
        assertNull(StatusPageAddress.parse(""))
        assertNull(StatusPageAddress.parse("https://h/dashboard/1"))
        assertNull(StatusPageAddress.parse("ftp://h/status/dev"))
        assertNull(StatusPageAddress.parse("https:///status/dev"))
        assertNull(StatusPageAddress.parse("https://h/status/a--b"))
    }

    @Test
    fun `overall status follows the web status page`() {
        val up = MonitorStatus.UP
        val down = MonitorStatus.DOWN
        assertEquals(OverallStatus.UNKNOWN, OverallStatus.of(emptyList()))
        assertEquals(OverallStatus.ALL_UP, OverallStatus.of(listOf(up, up)))
        assertEquals(OverallStatus.PARTIAL_DOWN, OverallStatus.of(listOf(up, down)))
        assertEquals(OverallStatus.PARTIAL_DOWN, OverallStatus.of(listOf(up, MonitorStatus.PENDING)))
        assertEquals(OverallStatus.ALL_DOWN, OverallStatus.of(listOf(down, MonitorStatus.PENDING)))
        assertEquals(OverallStatus.MAINTENANCE, OverallStatus.of(listOf(up, down, MonitorStatus.MAINTENANCE)))
    }

    @Test
    fun `clickable link only with sendUrl and a real url`() {
        val m = StatusPageMonitor(1, "x", "http", sendUrl = true, url = "https://a")
        assertEquals("https://a", m.clickableUrl)
        assertNull(m.copy(sendUrl = false).clickableUrl)
        assertNull(m.copy(url = "https://").clickableUrl)
        assertNull(m.copy(url = null).clickableUrl)
    }

    @Test
    fun `enum keys round-trip with defaults`() {
        assertEquals(IncidentStyle.PRIMARY, IncidentStyle.fromKey(null))
        assertEquals(IncidentStyle.DARK, IncidentStyle.fromKey("dark"))
        assertEquals(StatusPageTheme.AUTO, StatusPageTheme.fromKey("weird"))
        assertNull(StatusPageAnalytics.fromKey(null))
        assertEquals(StatusPageAnalytics.RYBBIT, StatusPageAnalytics.fromKey("rybbit"))
    }
}
