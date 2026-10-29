package net.kirkanos.uptimekuma.feature.statuspage.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.feature.statuspage.StatusPageEditRoute
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaError
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaResult
import javax.inject.Inject

/** The editable copy of a status page. [refreshText] is the raw text of the refresh interval field. */
data class StatusPageForm(
    val config: StatusPageConfig,
    val refreshText: String = config.autoRefreshInterval.toString(),
    val groups: List<StatusPageGroup> = emptyList(),
) {
    val refreshSeconds: Int? get() =
        refreshText.trim().toIntOrNull()?.takeIf {
            it >=
                StatusPageConfig.MIN_REFRESH_SECONDS
        }
    val titleMissing: Boolean get() = config.title.isBlank()
    val slugProblem: StatusPageSlug.Problem? get() = StatusPageSlug.validate(config.slug)
    val isValid: Boolean get() = !titleMissing && slugProblem == null && refreshSeconds != null

    /** Monitor ids already on the page (the web UI hides those in the picker). */
    val monitorIds: Set<Long> get() = groups.flatMap { g -> g.monitors.map { it.id } }.toSet()
}

data class StatusPageEditUiState(
    val originalSlug: String,
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val form: StatusPageForm? = null,
    /** All monitors of the server, for the picker. */
    val monitors: List<Monitor> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saveError: KumaError? = null,
    /** Validation messages are shown after the first save attempt. */
    val showValidation: Boolean = false,
    /** Set after a successful save (the possibly new slug). */
    val savedSlug: String? = null,
) {
    /** Monitors that can still be added. */
    val availableMonitors: List<Monitor>
        get() {
            val used = form?.monitorIds.orEmpty()
            return monitors.filterNot { it.id in used }
        }
}

private data class EditState(
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val form: StatusPageForm? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saveError: KumaError? = null,
    val showValidation: Boolean = false,
    val savedSlug: String? = null,
)

@HiltViewModel
@Suppress("TooManyFunctions")
class StatusPageEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: StatusPageRepository,
        monitorRepository: MonitorRepository,
    ) : ViewModel() {
        val slug: String = checkNotNull(savedStateHandle.get<String>(StatusPageEditRoute.ARG_SLUG))

        private val edit = MutableStateFlow(EditState())

        val state: StateFlow<StatusPageEditUiState> =
            combine(edit, monitorRepository.observeMonitors()) { edit, monitors ->
                StatusPageEditUiState(
                    originalSlug = slug,
                    loading = edit.loading,
                    loadError = edit.loadError,
                    form = edit.form,
                    monitors = monitors.map { it.monitor },
                    dirty = edit.dirty,
                    saving = edit.saving,
                    saveError = edit.saveError,
                    showValidation = edit.showValidation,
                    savedSlug = edit.savedSlug,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusPageEditUiState(slug))

        init {
            load()
        }

        /** Loads the admin config (`getStatusPage`) and the groups (public REST, like the web UI). */
        fun load() {
            edit.update { it.copy(loading = true, loadError = null) }
            viewModelScope.launch {
                val config = async { kumaResult { repository.loadConfig(slug) } }
                val page = async { kumaResult { repository.loadPublic(slug) } }
                val configResult = config.await()
                val pageResult = page.await()
                val loaded = configResult.getOrNull()
                val groups = pageResult.getOrNull()?.groups
                edit.update {
                    if (loaded != null && groups != null) {
                        it.copy(loading = false, form = StatusPageForm(loaded, groups = groups), dirty = false)
                    } else {
                        it.copy(loading = false, loadError = configResult.kumaError() ?: pageResult.kumaError())
                    }
                }
            }
        }

        // ---- settings ---------------------------------------------------------------------------

        fun updateConfig(change: (StatusPageConfig) -> StatusPageConfig) =
            updateForm { it.copy(config = change(it.config)) }

        fun setRefreshText(text: String) = updateForm { it.copy(refreshText = text.filter(Char::isDigit)) }

        fun addDomain() = updateConfig { it.copy(domainNameList = it.domainNameList + "") }

        fun setDomain(
            index: Int,
            value: String,
        ) = updateConfig { it.copy(domainNameList = it.domainNameList.replaceAt(index) { value.trim() }) }

        fun removeDomain(index: Int) = updateConfig { it.copy(domainNameList = it.domainNameList.without(index)) }

        // ---- groups -----------------------------------------------------------------------------

        /** Adds a group at the top, like the web UI (named "Services" for the first, else "Untitled Group"). */
        fun addGroup(name: String) = updateGroups { listOf(StatusPageGroup(name = name)) + it }

        fun renameGroup(
            index: Int,
            name: String,
        ) = updateGroups { groups -> groups.replaceAt(index) { it.copy(name = name) } }

        fun moveGroup(
            index: Int,
            delta: Int,
        ) = updateGroups { it.move(index, delta) }

        fun deleteGroup(index: Int) = updateGroups { it.without(index) }

        // ---- monitors ---------------------------------------------------------------------------

        fun addMonitors(
            groupIndex: Int,
            monitorIds: List<Long>,
        ) {
            val byId = state.value.monitors.associateBy { it.id }
            updateGroups { groups ->
                val used = groups.flatMap { g -> g.monitors.map { it.id } }.toSet()
                val added =
                    monitorIds.distinct().filterNot { it in used }.mapNotNull { id ->
                        byId[id]?.let { StatusPageMonitor(id = it.id, name = it.name, type = it.type) }
                    }
                groups.replaceAt(groupIndex) { it.copy(monitors = it.monitors + added) }
            }
        }

        fun removeMonitor(
            groupIndex: Int,
            monitorIndex: Int,
        ) = updateMonitors(groupIndex) { it.without(monitorIndex) }

        fun moveMonitor(
            groupIndex: Int,
            monitorIndex: Int,
            delta: Int,
        ) = updateMonitors(groupIndex) { it.move(monitorIndex, delta) }

        fun setSendUrl(
            groupIndex: Int,
            monitorIndex: Int,
            sendUrl: Boolean,
        ) = updateMonitors(groupIndex) { list -> list.replaceAt(monitorIndex) { it.copy(sendUrl = sendUrl) } }

        fun setCustomUrl(
            groupIndex: Int,
            monitorIndex: Int,
            url: String,
        ) = updateMonitors(groupIndex) { list ->
            list.replaceAt(monitorIndex) { it.copy(url = url.trim().takeIf { u -> u.isNotEmpty() }) }
        }

        // ---- save -------------------------------------------------------------------------------

        fun save() {
            val current = edit.value
            val form = current.form ?: return
            if (current.saving) return
            val refresh = form.refreshSeconds
            if (!form.isValid || refresh == null) {
                edit.update { it.copy(showValidation = true) }
                return
            }
            val config =
                form.config.copy(
                    slug = StatusPageSlug.normalize(form.config.slug),
                    autoRefreshInterval = refresh,
                )
            edit.update { it.copy(saving = true, saveError = null, showValidation = true) }
            viewModelScope.launch {
                val result = kumaResult { repository.save(slug, config, form.groups) }
                edit.update {
                    val groups = result.getOrNull()
                    if (groups != null) {
                        it.copy(
                            saving = false,
                            dirty = false,
                            form = form.copy(config = config, groups = groups),
                            savedSlug = config.slug,
                        )
                    } else {
                        it.copy(saving = false, saveError = result.kumaError())
                    }
                }
            }
        }

        fun dismissSaveError() {
            edit.update { it.copy(saveError = null) }
        }

        private fun updateForm(change: (StatusPageForm) -> StatusPageForm) {
            edit.update { state -> state.form?.let { state.copy(form = change(it), dirty = true) } ?: state }
        }

        private fun updateGroups(change: (List<StatusPageGroup>) -> List<StatusPageGroup>) =
            updateForm { it.copy(groups = change(it.groups)) }

        private fun updateMonitors(
            groupIndex: Int,
            change: (List<StatusPageMonitor>) -> List<StatusPageMonitor>,
        ) = updateGroups { groups -> groups.replaceAt(groupIndex) { it.copy(monitors = change(it.monitors)) } }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

internal fun <T> List<T>.replaceAt(
    index: Int,
    change: (T) -> T,
): List<T> = if (index in indices) mapIndexed { i, item -> if (i == index) change(item) else item } else this

internal fun <T> List<T>.without(index: Int): List<T> =
    if (index in
        indices
    ) {
        filterIndexed { i, _ -> i != index }
    } else {
        this
    }

/** Moves the item at [index] by [delta] positions (clamped); no-op for invalid indices. */
internal fun <T> List<T>.move(
    index: Int,
    delta: Int,
): List<T> {
    if (index !in indices) return this
    val target = (index + delta).coerceIn(indices)
    if (target == index) return this
    val result = toMutableList()
    val item = result.removeAt(index)
    result.add(target, item)
    return result
}
