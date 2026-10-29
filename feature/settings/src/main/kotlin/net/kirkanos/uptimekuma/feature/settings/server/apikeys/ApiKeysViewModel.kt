package net.kirkanos.uptimekuma.feature.settings.server.apikeys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.ApiKeyRepository
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject

/** The "add API key" dialog. */
data class ApiKeyForm(
    val name: String = "",
    /** `null` = never expires. */
    val expires: LocalDateTime? = null,
    val active: Boolean = true,
    val saving: Boolean = false,
    val error: KumaError? = null,
)

data class ApiKeysUiState(
    val keys: List<ApiKey>? = null,
    val loadError: KumaError? = null,
    val form: ApiKeyForm? = null,
    /** Shown exactly once after creation (the server never sends the key again). */
    val created: CreatedApiKey? = null,
    val busyIds: Set<Long> = emptySet(),
    val message: AdminMessage? = null,
)

private data class KeysLocal(
    val loadError: KumaError? = null,
    val form: ApiKeyForm? = null,
    val created: CreatedApiKey? = null,
    val busyIds: Set<Long> = emptySet(),
    val message: AdminMessage? = null,
)

/** API keys (`apiKeyList`): list, create (key shown once), enable/disable, delete. */
@HiltViewModel
class ApiKeysViewModel
    @Inject
    constructor(
        private val repository: ApiKeyRepository,
    ) : ViewModel() {
        /** Replaced in tests. */
        internal var clock: Clock = Clock.systemDefaultZone()

        private val local = MutableStateFlow(KeysLocal())

        val state: StateFlow<ApiKeysUiState> =
            combine(repository.observeApiKeys(), local) { keys, local ->
                ApiKeysUiState(
                    keys = keys,
                    loadError = local.loadError.takeIf { keys == null },
                    form = local.form,
                    created = local.created,
                    busyIds = local.busyIds,
                    message = local.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ApiKeysUiState())

        init {
            refresh()
        }

        fun refresh() {
            local.update { it.copy(loadError = null) }
            viewModelScope.launch {
                runKuma { repository.refresh() }.onFailure { e ->
                    local.update { it.copy(loadError = e.toKumaError()) }
                }
            }
        }

        fun startCreate() {
            // Like the web UI: expiry preset to one month from now (can be switched to "never").
            val inAMonth =
                LocalDateTime
                    .now(clock)
                    .plusMonths(1)
                    .withSecond(0)
                    .withNano(0)
            local.update { it.copy(form = ApiKeyForm(expires = inAMonth)) }
        }

        fun updateForm(transform: (ApiKeyForm) -> ApiKeyForm) {
            local.update { current -> current.copy(form = current.form?.let { transform(it).copy(error = null) }) }
        }

        fun dismissForm() {
            local.update { it.copy(form = null) }
        }

        fun canCreate(form: ApiKeyForm): Boolean =
            !form.saving && form.name.isNotBlank() &&
                (form.expires == null || form.expires.isAfter(LocalDateTime.now(clock)))

        fun create() {
            val form = local.value.form ?: return
            if (!canCreate(form)) return
            local.update { it.copy(form = form.copy(saving = true)) }
            viewModelScope.launch {
                runKuma { repository.create(ApiKeyDraft(form.name.trim(), form.expires, form.active)) }
                    .onSuccess { key -> local.update { it.copy(form = null, created = key) } }
                    .onFailure { e ->
                        local.update { it.copy(form = it.form?.copy(saving = false, error = e.toKumaError())) }
                    }
            }
        }

        fun dismissCreated() {
            local.update { it.copy(created = null) }
        }

        fun setActive(
            id: Long,
            active: Boolean,
        ) = run(id, if (active) AdminDone.ENABLED else AdminDone.DISABLED) { repository.setActive(id, active) }

        fun delete(id: Long) = run(id, AdminDone.DELETED) { repository.delete(id) }

        fun consumeMessage() {
            local.update { it.copy(message = null) }
        }

        private fun run(
            id: Long,
            done: AdminDone,
            block: suspend () -> Unit,
        ) {
            if (id in local.value.busyIds) return
            local.update { it.copy(busyIds = it.busyIds + id) }
            viewModelScope.launch {
                val message =
                    runKuma(
                        block,
                    ).fold({ AdminMessage.Done(done) }, { AdminMessage.Failed(it.toKumaError()) })
                local.update { it.copy(busyIds = it.busyIds - id, message = message) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
