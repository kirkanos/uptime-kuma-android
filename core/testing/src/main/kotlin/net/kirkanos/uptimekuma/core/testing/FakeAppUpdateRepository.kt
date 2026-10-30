package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import net.kirkanos.uptimekuma.core.domain.AppUpdateRepository
import net.kirkanos.uptimekuma.core.model.AppUpdate

/** In-memory [AppUpdateRepository] for unit tests; [check] only counts calls. */
class FakeAppUpdateRepository(
    initial: AppUpdate? = null,
) : AppUpdateRepository {
    val update = MutableStateFlow(initial)
    val checkEnabled = MutableStateFlow(true)
    var checks = 0
        private set

    override fun observeUpdate(): Flow<AppUpdate?> =
        combine(update, checkEnabled) { update, enabled -> update?.takeIf { enabled } }

    override fun observeCheckEnabled(): Flow<Boolean> = checkEnabled

    override suspend fun setCheckEnabled(enabled: Boolean) {
        checkEnabled.value = enabled
    }

    override suspend fun check(force: Boolean) {
        checks++
    }

    override suspend fun dismiss(version: String) {
        update.value = update.value?.let { if (it.version == version) it.copy(dismissed = true) else it }
    }
}
