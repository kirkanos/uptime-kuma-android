package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.AppUpdate

/** Update check against the app's GitHub releases. Implemented in `:core:data`. */
interface AppUpdateRepository {
    /** The newest known release if it is newer than the installed app and checks are on, else `null`. */
    fun observeUpdate(): Flow<AppUpdate?>

    fun observeCheckEnabled(): Flow<Boolean>

    /** Turning checks on runs one right away; turning them off hides a known update. */
    suspend fun setCheckEnabled(enabled: Boolean)

    /** Asks GitHub for the latest release, at most once a day unless [force]. Failures are ignored. */
    suspend fun check(force: Boolean = false)

    /** Hides the dashboard hint for [version] until a newer release appears. */
    suspend fun dismiss(version: String)
}
