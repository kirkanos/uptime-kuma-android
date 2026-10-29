package net.kirkanos.uptimekuma.feature.maintenance.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceEditRoute
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/** One edit of the form; applied by [MaintenanceEditViewModel.edit]. */
sealed interface DraftChange {
    data class Title(
        val value: String,
    ) : DraftChange

    data class Description(
        val value: String,
    ) : DraftChange

    data class Strategy(
        val value: MaintenanceStrategy,
    ) : DraftChange

    data class Cron(
        val value: String,
    ) : DraftChange

    /** Raw text of the duration field (minutes). */
    data class Duration(
        val text: String,
    ) : DraftChange

    /** Raw text of the interval field (days). */
    data class Interval(
        val text: String,
    ) : DraftChange

    data class ToggleWeekday(
        val day: Int,
    ) : DraftChange

    data class ToggleMonthDay(
        val day: MaintenanceMonthDay,
    ) : DraftChange

    data class StartTime(
        val value: LocalTime,
    ) : DraftChange

    data class EndTime(
        val value: LocalTime,
    ) : DraftChange

    data class StartDate(
        val value: LocalDateTime?,
    ) : DraftChange

    data class EndDate(
        val value: LocalDateTime?,
    ) : DraftChange

    /** End = start + minutes (single window quick buttons). */
    data class QuickDuration(
        val minutes: Long,
    ) : DraftChange

    data class Timezone(
        val option: String,
    ) : DraftChange

    data class Monitors(
        val ids: Set<Long>,
    ) : DraftChange

    data class ToggleStatusPage(
        val id: Long,
    ) : DraftChange

    data class AllStatusPages(
        val enabled: Boolean,
    ) : DraftChange
}

data class MaintenanceEditUiState(
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val isNew: Boolean = true,
    val isClone: Boolean = false,
    val draft: MaintenanceDraft = MaintenanceDraft(),
    val durationText: String = MaintenanceDraft.DEFAULT_DURATION_MINUTES.toString(),
    val intervalText: String = "1",
    /** "All status pages" checkbox of the web UI: links every existing page. */
    val allStatusPages: Boolean = false,
    val monitors: List<MonitorOption> = emptyList(),
    val statusPages: List<MaintenanceStatusPage> = emptyList(),
    /** Resolved server zone, if known from another maintenance using `SAME_AS_SERVER`. */
    val serverTimezone: String? = null,
    /** Errors are shown after the first save attempt. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveError: KumaError? = null,
    /** True when the last error happened while linking monitors/status pages (the maintenance exists). */
    val linkFailed: Boolean = false,
    /** Ask before saving a maintenance that only targets status pages (`noMonitorsSelectedWarning`). */
    val confirmNoMonitors: Boolean = false,
    /** Title of the cloned maintenance; the screen turns it into "Clone of …" and calls back. */
    val pendingCloneTitle: String? = null,
    val done: Boolean = false,
) {
    /** The draft as it will be sent (all status pages resolved). */
    val effectiveDraft: MaintenanceDraft
        get() = if (allStatusPages) draft.copy(statusPageIds = statusPages.map { it.id }.toSet()) else draft

    val errors: Set<MaintenanceFieldError> get() = MaintenanceValidation.validate(effectiveDraft)

    val visibleErrors: Set<MaintenanceFieldError> get() = if (showErrors) errors else emptySet()
}

private data class EditorLocal(
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val draft: MaintenanceDraft = MaintenanceDraft(),
    val durationText: String = MaintenanceDraft.DEFAULT_DURATION_MINUTES.toString(),
    val intervalText: String = "1",
    val allStatusPages: Boolean = false,
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveError: KumaError? = null,
    val linkFailed: Boolean = false,
    val confirmNoMonitors: Boolean = false,
    val pendingCloneTitle: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class MaintenanceEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaintenanceRepository,
        monitorRepository: MonitorRepository,
    ) : ViewModel() {
        private val sourceId: Long =
            savedStateHandle.get<Long>(MaintenanceEditRoute.ARG_ID) ?: MaintenanceEditRoute.NEW
        private val clone: Boolean = savedStateHandle.get<Boolean>(MaintenanceEditRoute.ARG_CLONE) ?: false
        private val isNew = sourceId == MaintenanceEditRoute.NEW

        private val local = MutableStateFlow(EditorLocal())

        val state: StateFlow<MaintenanceEditUiState> =
            combine(
                local,
                monitorRepository.observeMonitors(),
                repository.observeStatusPages(),
                repository.observeMaintenances(),
            ) { local, monitors, pages, maintenances ->
                MaintenanceEditUiState(
                    loading = local.loading,
                    loadError = local.loadError,
                    isNew = isNew || clone,
                    isClone = clone,
                    draft = local.draft,
                    durationText = local.durationText,
                    intervalText = local.intervalText,
                    allStatusPages = local.allStatusPages,
                    monitors = EditorSupport.monitorOptions(monitors),
                    statusPages = pages.orEmpty(),
                    serverTimezone =
                        maintenances
                            ?.firstOrNull { it.timezoneOption == Maintenance.TIMEZONE_SAME_AS_SERVER }
                            ?.timezone,
                    showErrors = local.showErrors,
                    saving = local.saving,
                    saveError = local.saveError,
                    linkFailed = local.linkFailed,
                    confirmNoMonitors = local.confirmNoMonitors,
                    pendingCloneTitle = local.pendingCloneTitle,
                    done = local.done,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MaintenanceEditUiState())

        init {
            load()
        }

        fun load() {
            if (isNew) {
                val draft = EditorSupport.newDraft(LocalDateTime.now())
                local.value = EditorLocal(loading = false, draft = draft)
                return
            }
            local.update { it.copy(loading = true, loadError = null) }
            viewModelScope.launch {
                try {
                    val detail = repository.get(sourceId)
                    val pages = repository.observeStatusPages().first().orEmpty()
                    val draft = EditorSupport.draftOf(detail, clone)
                    val linked = detail.statusPages.map { it.id }.toSet()
                    local.value =
                        EditorLocal(
                            loading = false,
                            draft = draft,
                            durationText = draft.durationMinutes.toString(),
                            intervalText = draft.intervalDay.toString(),
                            allStatusPages = pages.isNotEmpty() && pages.all { it.id in linked },
                            pendingCloneTitle = if (clone) detail.maintenance.title else null,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    local.update { it.copy(loading = false, loadError = e.toKumaError()) }
                }
            }
        }

        fun edit(change: DraftChange) {
            local.update { current -> current.apply(change) }
        }

        /** Applies the localized "Clone of …" title computed by the screen. */
        fun applyCloneTitle(title: String) {
            local.update { it.copy(draft = it.draft.copy(title = title), pendingCloneTitle = null) }
        }

        /** Validates and saves; [confirmed] skips the "no monitors selected" question. */
        fun save(confirmed: Boolean = false) {
            // Read the local state directly: the combined [state] may lag behind edits made just before.
            val current = local.value
            if (current.saving || current.loading) return
            val pages = state.value.statusPages
            val draft =
                if (current.allStatusPages) {
                    current.draft.copy(statusPageIds = pages.map { it.id }.toSet())
                } else {
                    current.draft
                }
            if (MaintenanceValidation.validate(draft).isNotEmpty()) {
                local.update { it.copy(showErrors = true, confirmNoMonitors = false) }
                return
            }
            if (!confirmed && draft.monitorIds.isEmpty() && draft.statusPageIds.isNotEmpty()) {
                local.update { it.copy(confirmNoMonitors = true) }
                return
            }
            local.update { it.copy(saving = true, saveError = null, linkFailed = false, confirmNoMonitors = false) }
            viewModelScope.launch {
                try {
                    val result = repository.save(draft)
                    local.update {
                        if (result.linkError != null) {
                            // The maintenance exists now; a retry must edit it instead of adding another one.
                            it.copy(
                                saving = false,
                                draft = it.draft.copy(id = result.id),
                                saveError = result.linkError,
                                linkFailed = true,
                            )
                        } else {
                            it.copy(saving = false, done = true)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    local.update { it.copy(saving = false, saveError = e.toKumaError()) }
                }
            }
        }

        fun dismissConfirm() {
            local.update { it.copy(confirmNoMonitors = false) }
        }

        fun dismissSaveError() {
            local.update { it.copy(saveError = null) }
        }

        private fun Exception.toKumaError(): KumaError =
            (this as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, message)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

@Suppress("CyclomaticComplexMethod")
private fun EditorLocal.apply(change: DraftChange): EditorLocal {
    val d = draft
    return when (change) {
        is DraftChange.Title -> {
            copy(draft = d.copy(title = change.value))
        }

        is DraftChange.Description -> {
            copy(draft = d.copy(description = change.value))
        }

        is DraftChange.Strategy -> {
            copy(draft = d.copy(strategy = change.value))
        }

        is DraftChange.Cron -> {
            copy(draft = d.copy(cron = change.value))
        }

        is DraftChange.Duration -> {
            copy(durationText = change.text, draft = d.copy(durationMinutes = change.text.toCount()))
        }

        is DraftChange.Interval -> {
            copy(intervalText = change.text, draft = d.copy(intervalDay = change.text.toCount()))
        }

        is DraftChange.ToggleWeekday -> {
            copy(draft = d.copy(weekdays = d.weekdays.toggle(change.day)))
        }

        is DraftChange.ToggleMonthDay -> {
            copy(draft = d.copy(daysOfMonth = d.daysOfMonth.toggle(change.day)))
        }

        is DraftChange.StartTime -> {
            copy(draft = d.copy(startTime = change.value))
        }

        is DraftChange.EndTime -> {
            copy(draft = d.copy(endTime = change.value))
        }

        is DraftChange.StartDate -> {
            copy(draft = d.copy(startDate = change.value))
        }

        is DraftChange.EndDate -> {
            copy(draft = d.copy(endDate = change.value))
        }

        is DraftChange.QuickDuration -> {
            copy(
                draft =
                    d.copy(
                        endDate =
                            d.startDate?.plusMinutes(change.minutes) ?: d.endDate,
                    ),
            )
        }

        is DraftChange.Timezone -> {
            copy(draft = d.copy(timezoneOption = change.option))
        }

        is DraftChange.Monitors -> {
            copy(draft = d.copy(monitorIds = change.ids))
        }

        is DraftChange.ToggleStatusPage -> {
            copy(draft = d.copy(statusPageIds = d.statusPageIds.toggle(change.id)))
        }

        is DraftChange.AllStatusPages -> {
            copy(allStatusPages = change.enabled)
        }
    }
}

private fun String.toCount(): Int = trim().toIntOrNull() ?: 0

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
