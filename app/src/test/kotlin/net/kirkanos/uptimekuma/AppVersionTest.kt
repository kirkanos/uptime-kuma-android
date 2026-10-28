package net.kirkanos.uptimekuma

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Smoke test proving JUnit 5 runs for the Android app module. */
class AppVersionTest {
    @Test
    fun `application id is stable`() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("net.kirkanos.uptimekuma"))
    }

    @Test
    fun `version code starts at one`() {
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
