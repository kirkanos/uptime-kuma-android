package net.kirkanos.uptimekuma.feature.settings.server.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError
import javax.inject.Inject

enum class DatabaseOperation { SHRINK, CLEAR_STATISTICS }

data class DatabaseUiState(
    /** Bytes; `null` while unknown. */
    val sizeBytes: Long? = null,
    val sizeError: KumaError? = null,
    val running: DatabaseOperation? = null,
    val message: AdminMessage? = null,
)

/** Database size, `shrinkDatabase` (SQLite only) and `clearStatistics` (Monitor history screen). */
@HiltViewModel
class DatabaseViewModel
    @Inject
    constructor(
        private val repository: ServerSettingsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DatabaseUiState())
        val state: StateFlow<DatabaseUiState> = mutableState.asStateFlow()

        init {
            refreshSize()
        }

        fun refreshSize() {
            viewModelScope.launch {
                runKuma { repository.databaseSize() }
                    .onSuccess { size -> mutableState.update { it.copy(sizeBytes = size, sizeError = null) } }
                    .onFailure { e -> mutableState.update { it.copy(sizeError = e.toKumaError()) } }
            }
        }

        fun shrink() = run(DatabaseOperation.SHRINK, AdminDone.DATABASE_SHRUNK) { repository.shrinkDatabase() }

        fun clearStatistics() =
            run(DatabaseOperation.CLEAR_STATISTICS, AdminDone.STATISTICS_CLEARED) {
                repository.clearStatistics()
            }

        fun consumeMessage() {
            mutableState.update { it.copy(message = null) }
        }

        private fun run(
            operation: DatabaseOperation,
            done: AdminDone,
            block: suspend () -> Unit,
        ) {
            if (mutableState.value.running != null) return
            mutableState.update { it.copy(running = operation) }
            viewModelScope.launch {
                val message =
                    runKuma(block).fold({ AdminMessage.Done(done) }, { AdminMessage.Failed(it.toKumaError()) })
                mutableState.update { it.copy(running = null, message = message) }
                refreshSize()
            }
        }
    }
