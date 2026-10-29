package net.kirkanos.uptimekuma.feature.settings.server.settings

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
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError
import javax.inject.Inject

/** A status page offered as entry page. */
data class EntryPageOption(
    val slug: String,
    val title: String,
)

/** Validation problems of the editable settings, shown at the fields. */
enum class SettingsProblem { PRIMARY_URL, KEEP_DAYS }

data class ServerSettingsUiState(
    /** Settings as stored on the server; `null` until loaded. */
    val saved: ServerSettings? = null,
    /** Edited copy; equals [saved] when nothing changed. */
    val draft: ServerSettings? = null,
    val loadError: KumaError? = null,
    val saving: Boolean = false,
    val details: ServerDetails? = null,
    val statusPages: List<EntryPageOption> = emptyList(),
    /** The app's own server URL (offered as primary base URL). */
    val appServerUrl: String? = null,
    val chromeTest: ChromeTest = ChromeTest.Idle,
    val message: AdminMessage? = null,
) {
    val loading: Boolean get() = saved == null && loadError == null
    val dirty: Boolean get() = draft != null && draft != saved
    val problems: Set<SettingsProblem> get() = draft?.let(::validate).orEmpty()
    val canSave: Boolean get() = dirty && !saving && problems.isEmpty()

    companion object {
        fun validate(settings: ServerSettings): Set<SettingsProblem> =
            buildSet {
                val url = settings.primaryBaseUrl.trim()
                if (url.isNotEmpty() && !URL_PATTERN.matches(url)) add(SettingsProblem.PRIMARY_URL)
                if (settings.keepDataPeriodDays < 0) add(SettingsProblem.KEEP_DAYS)
            }

        private val URL_PATTERN = Regex("""^https?://[^\s/?#]+[^\s]*$""", RegexOption.IGNORE_CASE)
    }
}

/** Result of the Chrome executable test (General screen). */
sealed interface ChromeTest {
    data object Idle : ChromeTest

    data object Running : ChromeTest

    data class Found(
        val version: String?,
    ) : ChromeTest

    data class Failed(
        val error: KumaError,
    ) : ChromeTest
}

private data class Local(
    val saved: ServerSettings? = null,
    val draft: ServerSettings? = null,
    val loadError: KumaError? = null,
    val saving: Boolean = false,
    val chromeTest: ChromeTest = ChromeTest.Idle,
    val message: AdminMessage? = null,
)

/**
 * Edits the server settings like the web UI: load once (`getSettings`), change a local draft, save the whole
 * object. Shared by the General, Notifications, Reverse proxy, Monitor history and About screens, each of which
 * shows a subset of the fields.
 */
@HiltViewModel
class ServerSettingsViewModel
    @Inject
    constructor(
        private val repository: ServerSettingsRepository,
        statusPageRepository: StatusPageRepository,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(Local())

        val state: StateFlow<ServerSettingsUiState> =
            combine(
                local,
                repository.observeServerDetails(),
                statusPageRepository.observeStatusPages(),
                sessionRepository.observeServerConfig(),
            ) { local, details, pages, config ->
                ServerSettingsUiState(
                    saved = local.saved,
                    draft = local.draft,
                    loadError = local.loadError,
                    saving = local.saving,
                    details = details,
                    statusPages =
                        pages
                            .orEmpty()
                            .map { EntryPageOption(it.slug, it.title) }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
                    appServerUrl = config?.baseUrl,
                    chromeTest = local.chromeTest,
                    message = local.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ServerSettingsUiState())

        init {
            load()
        }

        fun load() {
            local.update { it.copy(loadError = null) }
            viewModelScope.launch {
                runKuma { repository.load() }
                    .onSuccess { settings -> local.update { it.copy(saved = settings, draft = settings) } }
                    .onFailure { e -> local.update { it.copy(loadError = e.toKumaError()) } }
            }
        }

        fun update(transform: (ServerSettings) -> ServerSettings) {
            local.update { current -> current.copy(draft = current.draft?.let(transform)) }
        }

        /** Discards local edits. */
        fun reset() {
            local.update { it.copy(draft = it.saved) }
        }

        fun save() {
            val draft = local.value.draft ?: return
            if (local.value.saving || ServerSettingsUiState.validate(draft).isNotEmpty()) return
            persist(draft)
        }

        /** Applies [transform] and saves right away (toggles on the About screen). */
        fun updateAndSave(transform: (ServerSettings) -> ServerSettings) {
            val saved = local.value.saved ?: return
            if (local.value.saving) return
            val next = transform(saved)
            local.update { it.copy(draft = next) }
            persist(next)
        }

        private fun persist(settings: ServerSettings) {
            local.update { it.copy(saving = true) }
            viewModelScope.launch {
                runKuma { repository.save(settings) }
                    .onSuccess {
                        local.update {
                            it.copy(
                                saving = false,
                                saved = settings,
                                draft = settings,
                                message = AdminMessage.Done(AdminDone.SAVED),
                            )
                        }
                    }.onFailure { e ->
                        local.update { it.copy(saving = false, message = AdminMessage.Failed(e.toKumaError())) }
                    }
            }
        }

        fun testChrome() {
            val executable = local.value.draft?.chromeExecutable ?: return
            if (local.value.chromeTest == ChromeTest.Running) return
            local.update { it.copy(chromeTest = ChromeTest.Running) }
            viewModelScope.launch {
                val result =
                    runKuma { repository.testChrome(executable) }
                        .fold({ ChromeTest.Found(it) }, { ChromeTest.Failed(it.toKumaError()) })
                local.update { it.copy(chromeTest = result) }
            }
        }

        fun consumeMessage() {
            local.update { it.copy(message = null) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
