package net.kirkanos.uptimekuma.feature.settings.server.infra

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError

/** Result of a connection test inside the editor dialog. */
sealed interface TestResult {
    data object Running : TestResult

    data class Ok(
        val message: String?,
    ) : TestResult

    data class Failed(
        val error: KumaError,
    ) : TestResult
}

/** The open create/edit dialog. */
data class EditorState<D>(
    val draft: D,
    val isNew: Boolean,
    val saving: Boolean = false,
    val test: TestResult? = null,
    val error: KumaError? = null,
)

data class CrudUiState<T, D>(
    /** `null` until the server pushed the list. */
    val items: List<T>? = null,
    val editor: EditorState<D>? = null,
    val busyIds: Set<Long> = emptySet(),
    val message: AdminMessage? = null,
)

private data class CrudLocal<D>(
    val editor: EditorState<D>? = null,
    val busyIds: Set<Long> = emptySet(),
    val message: AdminMessage? = null,
)

/**
 * List + editor dialog over a snapshot-backed repository (proxies, Docker hosts, remote browsers). Subclasses
 * provide the repository calls and the validation.
 */
@Suppress("TooManyFunctions")
abstract class CrudListViewModel<T, D> : ViewModel() {
    private val local = MutableStateFlow(CrudLocal<D>())

    protected abstract fun observe(): Flow<List<T>?>

    protected abstract fun newDraft(): D

    protected abstract fun draftOf(item: T): D

    protected abstract fun isValid(draft: D): Boolean

    protected abstract suspend fun persist(draft: D): Long

    protected abstract suspend fun remove(id: Long)

    /** Connection test; `null` when the type has none. */
    protected open suspend fun test(draft: D): String? = null

    val state: StateFlow<CrudUiState<T, D>> by lazy {
        combine(observe(), local) { items, local ->
            CrudUiState(items = items, editor = local.editor, busyIds = local.busyIds, message = local.message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CrudUiState())
    }

    fun create() {
        local.update { it.copy(editor = EditorState(newDraft(), isNew = true)) }
    }

    fun edit(item: T) {
        local.update { it.copy(editor = EditorState(draftOf(item), isNew = false)) }
    }

    fun updateDraft(transform: (D) -> D) {
        local.update { current ->
            current.copy(
                editor = current.editor?.let { it.copy(draft = transform(it.draft), test = null, error = null) },
            )
        }
    }

    fun dismissEditor() {
        local.update { it.copy(editor = null) }
    }

    fun canSave(editor: EditorState<D>): Boolean = !editor.saving && isValid(editor.draft)

    fun save() {
        val editor = local.value.editor ?: return
        if (!canSave(editor)) return
        local.update { it.copy(editor = editor.copy(saving = true, error = null)) }
        viewModelScope.launch {
            runKuma { persist(editor.draft) }
                .onSuccess {
                    val done = if (editor.isNew) AdminDone.CREATED else AdminDone.SAVED
                    local.update { it.copy(editor = null, message = AdminMessage.Done(done)) }
                }.onFailure { e ->
                    local.update { it.copy(editor = it.editor?.copy(saving = false, error = e.toKumaError())) }
                }
        }
    }

    fun runTest() {
        val editor = local.value.editor ?: return
        if (editor.test == TestResult.Running || !isValid(editor.draft)) return
        local.update { it.copy(editor = editor.copy(test = TestResult.Running)) }
        viewModelScope.launch {
            val result =
                runKuma {
                    test(
                        editor.draft,
                    )
                }.fold({ TestResult.Ok(it) }, { TestResult.Failed(it.toKumaError()) })
            // Only show the result while the same draft is still open.
            local.update { current ->
                current.copy(
                    editor =
                        current.editor?.takeIf { it.draft == editor.draft }?.copy(test = result) ?: current.editor,
                )
            }
        }
    }

    fun delete(id: Long) {
        if (id in local.value.busyIds) return
        local.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            val message =
                runKuma {
                    remove(id)
                }.fold({ AdminMessage.Done(AdminDone.DELETED) }, { AdminMessage.Failed(it.toKumaError()) })
            local.update { it.copy(busyIds = it.busyIds - id, message = message) }
        }
    }

    fun consumeMessage() {
        local.update { it.copy(message = null) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
