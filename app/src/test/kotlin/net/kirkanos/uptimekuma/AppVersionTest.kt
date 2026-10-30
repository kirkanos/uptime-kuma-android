package net.kirkanos.uptimekuma

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Smoke test proving JUnit 5 runs for the Android app module. */
class AppVersionTest {
    @Test
    fun `application id is stable`() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("net.kirkanos.uptimekuma"))
    }

    @Test
    fun `version comes from gradle properties`() {
        // VERSION_CODE grows with every release; only its range is stable.
        assertTrue(BuildConfig.VERSION_CODE >= 1)
        assertTrue(Regex("""\d+\.\d+\.\d+(-debug)?""").matches(BuildConfig.VERSION_NAME), BuildConfig.VERSION_NAME)
    }
}
