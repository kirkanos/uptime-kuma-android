package net.kirkanos.uptimekuma.feature.monitors.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.TagRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.feature.monitors.editor.toKumaError
import javax.inject.Inject

data class TagManagerUiState(
    val tags: List<Tag> = emptyList(),
    val refreshing: Boolean = true,
    val busy: Boolean = false,
    /** Error of the last action or refresh, shown once. */
    val error: KumaError? = null,
)

private data class TagLocalState(
    val refreshing: Boolean = true,
    val busy: Boolean = false,
    val error: KumaError? = null,
)

@HiltViewModel
class TagManagerViewModel
    @Inject
    constructor(
        private val repository: TagRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(TagLocalState())

        val state: StateFlow<TagManagerUiState> =
            combine(repository.observeTags(), local) { tags, l ->
                TagManagerUiState(tags = tags, refreshing = l.refreshing, busy = l.busy, error = l.error)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TagManagerUiState())

        init {
            refresh()
        }

        fun refresh() {
            local.update { it.copy(refreshing = true, error = null) }
            viewModelScope.launch {
                val error = attempt { repository.refresh() }
                local.update { it.copy(refreshing = false, error = error) }
            }
        }

        fun create(
            name: String,
            color: String,
        ) = run { repository.create(name.trim(), color) }

        fun update(tag: Tag) = run { repository.update(tag.copy(name = tag.name.trim())) }

        fun delete(id: Long) = run { repository.delete(id) }

        fun dismissError() = local.update { it.copy(error = null) }

        private fun run(block: suspend () -> Unit) {
            if (local.value.busy) return
            local.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val error = attempt(block)
                local.update { it.copy(busy = false, error = error) }
            }
        }

        private suspend fun attempt(block: suspend () -> Unit): KumaError? =
            try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toKumaError()
            }

        companion object {
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
