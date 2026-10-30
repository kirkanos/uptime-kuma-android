package net.kirkanos.uptimekuma.core.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.DataLog
import net.kirkanos.uptimekuma.core.data.statuspage.HttpGetter
import net.kirkanos.uptimekuma.core.domain.AppUpdateRepository
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.AppUpdate
import net.kirkanos.uptimekuma.core.model.ServerDetails
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appUpdateDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_update")

/**
 * [AppUpdateRepository] on GitHub's "latest release" endpoint, which skips drafts and pre-releases. The
 * result is kept in the `app_update` DataStore so the hint survives restarts without a new request.
 * Unauthenticated GitHub API calls are limited to 60 per hour and IP; one per day is far below that.
 */
@Singleton
internal class GitHubAppUpdateRepository(
    private val dataStore: DataStore<Preferences>,
    private val http: HttpGetter,
    private val installedVersion: String,
    private val clock: Clock,
) : AppUpdateRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        http: HttpGetter,
    ) : this(context.appUpdateDataStore, http, installedVersion(context), Clock.systemUTC())

    private val checkMutex = Mutex()

    private val preferences: Flow<Preferences> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override fun observeUpdate(): Flow<AppUpdate?> = preferences.map { it.toUpdate() }.distinctUntilChanged()

    override fun observeCheckEnabled(): Flow<Boolean> =
        preferences.map { it[KEY_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setCheckEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
        if (enabled) check(force = true)
    }

    override suspend fun check(force: Boolean) {
        checkMutex.withLock {
            val prefs = preferences.first()
            if (prefs[KEY_ENABLED] == false) return
            val now = clock.millis()
            val last = prefs[KEY_CHECKED_AT] ?: 0L
            if (!force && now - last in 0 until CHECK_INTERVAL_MILLIS) return
            val release = fetchLatest() ?: return
            dataStore.edit {
                it[KEY_CHECKED_AT] = now
                it[KEY_LATEST] = release.version
                it[KEY_URL] = release.url
            }
        }
    }

    override suspend fun dismiss(version: String) {
        dataStore.edit { it[KEY_DISMISSED] = version }
    }

    private suspend fun fetchLatest(): AppUpdate? =
        try {
            val result = http.get(LATEST_RELEASE_URL)
            if (result.code == HTTP_OK) {
                parseRelease(result.body)
            } else {
                DataLog.w("Update check: HTTP ${result.code}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DataLog.w("Update check failed: ${e.message}")
            null
        }

    private fun Preferences.toUpdate(): AppUpdate? {
        if (this[KEY_ENABLED] == false) return null
        val latest = this[KEY_LATEST] ?: return null
        if (installedVersion.isBlank() || !isNewer(latest, installedVersion)) return null
        return AppUpdate(
            version = latest,
            url = this[KEY_URL] ?: RELEASES_URL,
            dismissed = this[KEY_DISMISSED] == latest,
        )
    }

    companion object {
        const val RELEASES_URL = "https://github.com/kirkanos/uptime-kuma-android/releases"
        internal const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/kirkanos/uptime-kuma-android/releases/latest"
        private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        private const val HTTP_OK = 200

        /** Debug builds carry `versionNameSuffix = "-debug"`; it is not a pre-release marker. */
        private const val DEBUG_SUFFIX = "-debug"

        private val KEY_ENABLED = booleanPreferencesKey("check_enabled")
        private val KEY_CHECKED_AT = longPreferencesKey("checked_at")
        private val KEY_LATEST = stringPreferencesKey("latest_version")
        private val KEY_URL = stringPreferencesKey("latest_url")
        private val KEY_DISMISSED = stringPreferencesKey("dismissed_version")

        internal fun isNewer(
            latest: String,
            installed: String,
        ): Boolean = ServerDetails.compareVersions(latest, installed.removeSuffix(DEBUG_SUFFIX)) > 0

        /** `tag_name` and `html_url` of a GitHub release; `null` for drafts, pre-releases or foreign URLs. */
        internal fun parseRelease(body: String): AppUpdate? {
            val json =
                try {
                    KumaJson.json.parseToJsonElement(body) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            if (json["draft"].asLenientBoolean() == true || json["prerelease"].asLenientBoolean() == true) return null
            val tag = json["tag_name"].asStringOrNull()?.trim()?.removePrefix("v") ?: return null
            if (!VERSION.matches(tag)) return null
            // Only ever link to this repository's release pages.
            val url = json["html_url"].asStringOrNull()?.takeIf { it.startsWith("$RELEASES_URL/") } ?: RELEASES_URL
            return AppUpdate(version = tag, url = url)
        }

        private val VERSION = Regex("""\d+\.\d+\.\d+""")

        private fun installedVersion(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                .getOrNull()
                .orEmpty()
    }
}
