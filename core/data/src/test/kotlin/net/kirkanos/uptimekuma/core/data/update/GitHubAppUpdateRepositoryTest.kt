package net.kirkanos.uptimekuma.core.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.data.statuspage.HttpResult
import net.kirkanos.uptimekuma.core.model.AppUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GitHubAppUpdateRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-10-30T12:00:00Z")
    private var response: () -> HttpResult = { HttpResult(200, release("v0.3.0")) }
    private val requested = mutableListOf<String>()

    private fun TestScope.repository(installed: String = "0.2.0") =
        GitHubAppUpdateRepository(
            dataStore =
                PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "app_update.preferences_pb") },
            http = { url ->
                requested += url
                response()
            },
            installedVersion = installed,
            clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId?) = this

                    override fun instant() = now
                },
        )

    @Test
    fun `newer release becomes an update with its release page`() =
        runTest {
            val repo = repository()
            repo.check()
            assertEquals(
                AppUpdate("0.3.0", "https://github.com/kirkanos/uptime-kuma-android/releases/tag/v0.3.0"),
                repo.observeUpdate().first(),
            )
            assertEquals(listOf(GitHubAppUpdateRepository.LATEST_RELEASE_URL), requested)
        }

    @Test
    fun `same or older release is no update, debug suffix ignored`() =
        runTest {
            response = { HttpResult(200, release("v0.2.0")) }
            val repo = repository(installed = "0.2.0-debug")
            repo.check()
            assertNull(repo.observeUpdate().first())
        }

    @Test
    fun `checks at most once a day unless forced`() =
        runTest {
            val repo = repository()
            repo.check()
            repo.check()
            assertEquals(1, requested.size)
            repo.check(force = true)
            assertEquals(2, requested.size)
            now = now.plusSeconds(25 * 60 * 60)
            repo.check()
            assertEquals(3, requested.size)
        }

    @Test
    fun `failures keep the last result and retry next time`() =
        runTest {
            val repo = repository()
            repo.check()
            response = { throw IOException("offline") }
            repo.check(force = true)
            assertEquals("0.3.0", repo.observeUpdate().first()?.version)
            response = { HttpResult(403, "{}") }
            repo.check(force = true)
            assertEquals("0.3.0", repo.observeUpdate().first()?.version)
        }

    @Test
    fun `dismiss hides only that version`() =
        runTest {
            val repo = repository()
            repo.check()
            repo.dismiss("0.3.0")
            assertTrue(repo.observeUpdate().first()!!.dismissed)
            response = { HttpResult(200, release("v0.4.0")) }
            repo.check(force = true)
            assertFalse(repo.observeUpdate().first()!!.dismissed)
        }

    @Test
    fun `disabled checks send no request and hide a known update`() =
        runTest {
            val repo = repository()
            repo.check()
            repo.setCheckEnabled(false)
            assertNull(repo.observeUpdate().first())
            repo.check(force = true)
            assertEquals(1, requested.size)
            repo.setCheckEnabled(true)
            assertEquals(2, requested.size)
            assertEquals("0.3.0", repo.observeUpdate().first()?.version)
        }

    @Test
    fun `parse rejects drafts, pre-releases, odd tags and foreign links`() {
        assertNull(GitHubAppUpdateRepository.parseRelease(release("v0.3.0", draft = true)))
        assertNull(GitHubAppUpdateRepository.parseRelease(release("v0.3.0", prerelease = true)))
        assertNull(GitHubAppUpdateRepository.parseRelease(release("nightly")))
        assertNull(GitHubAppUpdateRepository.parseRelease("not json"))
        assertEquals(
            GitHubAppUpdateRepository.RELEASES_URL,
            GitHubAppUpdateRepository
                .parseRelease(release("v0.3.0", url = "https://evil.example/releases/tag/v0.3.0"))
                ?.url,
        )
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        url: String = "https://github.com/kirkanos/uptime-kuma-android/releases/tag/$tag",
    ) = """{"tag_name":"$tag","html_url":"$url","draft":$draft,"prerelease":$prerelease,"assets":[]}"""
}
