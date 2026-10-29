package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MonitorEditorRepository
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.domain.TagRepository
import net.kirkanos.uptimekuma.core.domain.TagSyncException
import net.kirkanos.uptimekuma.core.domain.editor.FieldIssue
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.MonitorValidator
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.Tag
import javax.inject.Inject

enum class EditorMode { NEW, EDIT, CLONE }

/** Problems shown above the form. */
sealed interface EditorProblem {
    /** Client-side validation failed; the fields show the details. */
    data object Invalid : EditorProblem

    data class Server(
        val error: KumaError,
    ) : EditorProblem

    /** The monitor was saved, but tags could not be attached/detached. */
    data class TagsFailed(
        val error: KumaError,
    ) : EditorProblem
}

data class MonitorEditorUiState(
    val mode: EditorMode = EditorMode.NEW,
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val draft: MonitorDraft? = null,
    /** State the dirty check compares against (loaded or last saved draft). */
    val original: MonitorDraft? = null,
    val pickers: EditorPickers = EditorPickers(),
    val sections: List<EditorSection> = emptyList(),
    val errors: Map<String, FieldIssue> = emptyMap(),
    /** Errors are shown once the user tried to save. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val problem: EditorProblem? = null,
    /** Interval below 20 s: ask before saving. */
    val confirmShortInterval: Boolean = false,
    /** Set after a successful save: navigate to the monitor. */
    val savedMonitorId: Long? = null,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val baseUrl: String? = null,
) {
    val dirty: Boolean get() = draft != null && draft != original

    val type: String get() = draft?.type ?: MonitorDraft.DEFAULT_TYPE

    val pushUrl: String?
        get() {
            val token = draft?.get("pushToken") as? String ?: return null
            val base = baseUrl ?: return null
            return if (type == "push" && token.isNotBlank()) MonitorDrafts.pushUrl(base, token) else null
        }

    /** Group ids that may not become the parent: the monitor itself and its descendants. */
    val excludedParents: Set<Long>
        get() =
            buildSet {
                draft?.id?.let(::add)
                (draft?.get("childrenIDs") as? List<*>).orEmpty().mapNotNullTo(this) { (it as? Number)?.toLong() }
            }

    fun visibleErrors(): Map<String, FieldIssue> = if (showErrors) errors else emptyMap()
}

@HiltViewModel
class MonitorEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MonitorEditorRepository,
        private val tagRepository: TagRepository,
        sessionRepository: SessionRepository,
        serverInfoRepository: ServerInfoRepository,
    ) : ViewModel() {
        private val route =
            MonitorEditorRoute(
                monitorId = savedStateHandle.get<Long>(MonitorEditorRoute.ARG_MONITOR_ID) ?: MonitorEditorRoute.NONE,
                cloneOf = savedStateHandle.get<Long>(MonitorEditorRoute.ARG_CLONE_OF) ?: MonitorEditorRoute.NONE,
                type = savedStateHandle.get<String>(MonitorEditorRoute.ARG_TYPE) ?: MonitorDraft.DEFAULT_TYPE,
            )
        private val spec = MonitorSpec.bundled
        private val mutableState =
            MutableStateFlow(
                MonitorEditorUiState(
                    mode =
                        when {
                            route.monitorId != MonitorEditorRoute.NONE -> EditorMode.EDIT
                            route.cloneOf != MonitorEditorRoute.NONE -> EditorMode.CLONE
                            else -> EditorMode.NEW
                        },
                ),
            )
        val state: StateFlow<MonitorEditorUiState> = mutableState.asStateFlow()
        private var started = false

        init {
            repository
                .observePickers()
                .onEach { p ->
                    mutableState.update { it.copy(pickers = p) }
                }.launchIn(viewModelScope)
            combine(sessionRepository.observeServerConfig(), serverInfoRepository.observeServerInfo()) { config, info ->
                info?.primaryBaseUrl?.takeIf { it.isNotBlank() } ?: config?.baseUrl
            }.onEach { base -> mutableState.update { it.copy(baseUrl = base) } }.launchIn(viewModelScope)
        }

        /** Loads the draft once. [cloneNameFormat] is a format string with one `%s` for the source name. */
        fun load(cloneNameFormat: String = "Copy of %s") {
            if (started) return
            started = true
            reload(cloneNameFormat)
        }

        fun retryLoad(cloneNameFormat: String = "Copy of %s") = reload(cloneNameFormat)

        private fun reload(cloneNameFormat: String) {
            mutableState.update { it.copy(loading = true, loadError = null) }
            viewModelScope.launch {
                runCatching { tagRepository.refresh() }
                val result =
                    runKuma {
                        when (mutableState.value.mode) {
                            EditorMode.EDIT -> repository.loadForEdit(route.monitorId)
                            EditorMode.CLONE -> repository.clone(route.cloneOf) { cloneNameFormat.format(it) }
                            EditorMode.NEW -> repository.newDraft(route.type)
                        }
                    }
                result
                    .onSuccess { draft ->
                        mutableState.update { it.copy(loading = false, original = draft).withDraft(draft) }
                    }.onFailure { e -> mutableState.update { it.copy(loading = false, loadError = e.toKumaError()) } }
            }
        }

        fun onFieldChange(
            key: String,
            value: Any?,
        ) {
            val draft = mutableState.value.draft ?: return
            val fields = MonitorDrafts.change(spec, draft.fields, key, value)
            mutableState.update { it.withDraft(draft.copy(fields = fields)) }
        }

        fun regeneratePushToken() = onFieldChange("pushToken", MonitorDrafts.newPushToken())

        fun addTag(
            tag: Tag,
            value: String,
        ) {
            val draft = mutableState.value.draft ?: return
            val entry = MonitorTag(tag.id, tag.name, tag.color, value.trim())
            if (draft.tags.any { it.tagId == entry.tagId && it.value == entry.value }) return
            mutableState.update { it.withDraft(draft.copy(tags = draft.tags + entry)) }
        }

        fun removeTag(tag: MonitorTag) {
            val draft = mutableState.value.draft ?: return
            mutableState.update { it.withDraft(draft.copy(tags = draft.tags - tag)) }
        }

        /** Creates a new tag definition right away and attaches it to the draft. */
        fun createTag(
            name: String,
            color: String,
            value: String,
        ) {
            viewModelScope.launch {
                runKuma { tagRepository.create(name, color) }
                    .onSuccess { addTag(it, value) }
                    .onFailure { e -> mutableState.update { it.copy(problem = EditorProblem.Server(e.toKumaError())) } }
            }
        }

        /** Validates and saves; asks for confirmation first when the interval is below 20 s. */
        fun save(confirmedShortInterval: Boolean = false) {
            val current = mutableState.value
            val draft = current.draft?.takeUnless { current.saving } ?: return
            val validation = MonitorValidator.validate(spec, draft.fields)
            if (!validation.isValid) {
                mutableState.update { it.copy(showErrors = true, problem = EditorProblem.Invalid) }
                return
            }
            if (validation.shortIntervalWarning && !confirmedShortInterval) {
                mutableState.update { it.copy(confirmShortInterval = true) }
                return
            }
            mutableState.update { it.copy(saving = true, problem = null, confirmShortInterval = false) }
            viewModelScope.launch {
                try {
                    val id = repository.save(draft)
                    mutableState.update { it.copy(saving = false, savedMonitorId = id, original = it.draft) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TagSyncException) {
                    // The monitor exists now: further saves must edit it instead of adding a duplicate.
                    val saved = draft.copy(id = e.monitorId)
                    mutableState.update {
                        it
                            .copy(
                                saving = false,
                                problem = EditorProblem.TagsFailed(e.cause.toKumaError()),
                            ).withDraft(saved)
                    }
                } catch (e: Exception) {
                    mutableState.update { it.copy(saving = false, problem = EditorProblem.Server(e.toKumaError())) }
                }
            }
        }

        fun dismissShortIntervalConfirm() = mutableState.update { it.copy(confirmShortInterval = false) }

        fun dismissProblem() = mutableState.update { it.copy(problem = null) }

        fun delete(deleteChildren: Boolean = false) {
            val id = mutableState.value.draft?.id ?: return
            mutableState.update { it.copy(deleting = true, problem = null) }
            viewModelScope.launch {
                runKuma { repository.delete(id, deleteChildren) }
                    .onSuccess { mutableState.update { it.copy(deleting = false, deleted = true) } }
                    .onFailure { e ->
                        mutableState.update {
                            it.copy(
                                deleting = false,
                                problem = EditorProblem.Server(e.toKumaError()),
                            )
                        }
                    }
            }
        }

        private fun MonitorEditorUiState.withDraft(draft: MonitorDraft): MonitorEditorUiState =
            copy(
                draft = draft,
                sections = EditorSections.build(spec, draft.fields),
                errors = MonitorValidator.validate(spec, draft.fields).errors,
            )

        private suspend fun <T> runKuma(block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
    }

internal fun Throwable.toKumaError(): KumaError =
    (this as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, message)
