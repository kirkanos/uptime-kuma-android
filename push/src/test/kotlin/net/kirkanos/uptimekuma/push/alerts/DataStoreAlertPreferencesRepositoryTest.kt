package net.kirkanos.uptimekuma.push.alerts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.core.model.RepeatInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAlertPreferencesRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_761_700_000_000L
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }

    private fun TestScope.repository(): DataStoreAlertPreferencesRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "alert_preferences.preferences_pb") }
        return DataStoreAlertPreferencesRepository(dataStore, backgroundScope, clock)
    }

    @Test
    fun `defaults`() =
        runTest {
            val repo = repository()
            assertEquals(AlertPreferences(), repo.observePreferences().first())
        }

    @Test
    fun `switches and quiet hours are persisted`() =
        runTest {
            val repo = repository()
            repo.setNotifyOnUp(false)
            repo.setNotifyOnCertExpiry(false)
            repo.setQuietHours(QuietHours(enabled = true, startMinuteOfDay = 23 * 60, endMinuteOfDay = 6 * 60 + 30))
            val prefs = repo.observePreferences().first()
            assertEquals(false, prefs.notifyOnUp)
            assertEquals(false, prefs.notifyOnCertExpiry)
            assertEquals(QuietHours(true, 23 * 60, 6 * 60 + 30), prefs.quietHours)
        }

    @Test
    fun `repeat interval is persisted, off included`() =
        runTest {
            val repo = repository()
            assertEquals(RepeatInterval.DEFAULT, repo.observePreferences().first().repeatDown)
            repo.setRepeatDown(RepeatInterval.THIRTY_MINUTES)
            assertEquals(RepeatInterval.THIRTY_MINUTES, repo.observePreferences().first().repeatDown)
            repo.setRepeatDown(RepeatInterval.OFF)
            assertEquals(RepeatInterval.OFF, repo.observePreferences().first().repeatDown)
        }

    @Test
    fun `quiet hours are clamped to a day`() =
        runTest {
            val repo = repository()
            repo.setQuietHours(QuietHours(enabled = true, startMinuteOfDay = -5, endMinuteOfDay = 5_000))
            assertEquals(
                QuietHours(true, 0, QuietHours.MINUTES_PER_DAY - 1),
                repo.observePreferences().first().quietHours,
            )
        }

    @Test
    fun `mute, observe and unmute`() =
        runTest {
            val repo = repository()
            repo.mute(5, untilMillis = now + 3_600_000)
            repo.mute(6, untilMillis = null)
            assertEquals(MonitorMute(5, now + 3_600_000), repo.observeMute(5).first())
            assertEquals(MonitorMute(6, null), repo.observeMute(6).first())
            assertNull(repo.observeMute(7).first())
            repo.unmute(5)
            assertNull(repo.observeMute(5).first())
            assertEquals(
                setOf(6L),
                repo
                    .observePreferences()
                    .first()
                    .mutes.keys,
            )
        }

    @Test
    fun `expired mutes are pruned on the next write`() =
        runTest {
            val repo = repository()
            repo.mute(5, untilMillis = now + 1_000)
            repo.mute(6, untilMillis = null)
            now += 1_000
            repo.setNotifyOnUp(true)
            assertEquals(
                setOf(6L),
                repo
                    .observePreferences()
                    .first()
                    .mutes.keys,
            )
        }

    @Test
    fun `current gives a synchronous snapshot`() =
        runTest {
            val repo = repository()
            repo.setNotifyOnUp(false)
            runCurrent()
            assertEquals(false, repo.current().notifyOnUp)
        }
}
