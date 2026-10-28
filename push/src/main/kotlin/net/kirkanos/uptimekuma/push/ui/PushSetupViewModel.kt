package net.kirkanos.uptimekuma.push.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.push.data.DistributorGateway
import net.kirkanos.uptimekuma.push.data.PushTokenStore
import net.kirkanos.uptimekuma.push.di.PushIoDispatcher
import javax.inject.Inject

@Immutable
data class DistributorOption(
    val packageName: String,
    val label: String,
)

enum class PushSetupPhase {
    Loading,

    /** No UnifiedPush distributor installed: show "Install ntfy". */
    InstallDistributor,

    /** Distributor picker (if needed), token, "apply to all". */
    Form,

    /** Registering with the distributor and creating the Kuma notification. */
    Working,

    /** Push is active: test, set up again, disable. */
    Active,
}

enum class TestState { Idle, Sending, Sent }

/** Phases a distributor refresh must not leave. */
private val keptPhases = setOf(PushSetupPhase.Working, PushSetupPhase.Active)

@Immutable
data class PushSetupUiState(
    val phase: PushSetupPhase = PushSetupPhase.Loading,
    val distributors: List<DistributorOption> = emptyList(),
    val selectedDistributor: String? = null,
    val token: String = "",
    val applyToAll: Boolean = true,
    val error: PushUiError? = null,
    val testState: TestState = TestState.Idle,
) {
    val canSubmit: Boolean
        get() = phase == PushSetupPhase.Form && token.isNotBlank() && selectedDistributor != null
}

@HiltViewModel
class PushSetupViewModel
    @Inject
    constructor(
        private val repository: PushRepository,
        private val gateway: DistributorGateway,
        private val tokenStore: PushTokenStore,
        @PushIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(PushSetupUiState())
        val state: StateFlow<PushSetupUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val storedToken = withContext(ioDispatcher) { runCatching { tokenStore.load() }.getOrNull() }
                _state.update { it.copy(token = it.token.ifEmpty { storedToken.orEmpty() }) }
                refreshInternal(initial = true)
            }
        }

        /** Re-checks installed distributors, e.g. when the screen resumes after installing ntfy. */
        fun refresh() {
            if (_state.value.phase == PushSetupPhase.Loading) return
            viewModelScope.launch { refreshInternal(initial = false) }
        }

        private suspend fun refreshInternal(initial: Boolean) {
            val options =
                withContext(ioDispatcher) {
                    repository.availableDistributors().map { DistributorOption(it, gateway.label(it)) }
                }
            val active = initial && repository.isActive()
            _state.update { current ->
                val selected =
                    current.selectedDistributor?.takeIf { sel -> options.any { it.packageName == sel } }
                        ?: preselect(options)
                val phase =
                    when {
                        active -> PushSetupPhase.Active
                        current.phase in keptPhases -> current.phase
                        options.isEmpty() -> PushSetupPhase.InstallDistributor
                        else -> PushSetupPhase.Form
                    }
                current.copy(distributors = options, selectedDistributor = selected, phase = phase)
            }
        }

        private fun preselect(options: List<DistributorOption>): String? {
            options.singleOrNull()?.let { return it.packageName }
            val saved = runCatching { gateway.savedDistributor() }.getOrNull()
            if (saved != null && options.any { it.packageName == saved }) return saved
            val default = runCatching { gateway.defaultDistributor() }.getOrNull()
            return default?.takeIf { d -> options.any { it.packageName == d } }
        }

        fun onTokenChange(token: String) = _state.update { it.copy(token = token, error = null) }

        fun onApplyToAllChange(applyToAll: Boolean) = _state.update { it.copy(applyToAll = applyToAll) }

        fun onDistributorSelected(packageName: String) =
            _state.update { it.copy(selectedDistributor = packageName, error = null) }

        fun setUp() {
            val current = _state.value
            if (current.token.isBlank()) {
                _state.update { it.copy(error = PushUiError.MissingToken) }
                return
            }
            _state.update { it.copy(phase = PushSetupPhase.Working, error = null, testState = TestState.Idle) }
            viewModelScope.launch {
                try {
                    repository.setUp(current.token, current.applyToAll, current.selectedDistributor)
                    _state.update { it.copy(phase = PushSetupPhase.Active) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(phase = PushSetupPhase.Form, error = PushUiError.from(e)) }
                    refreshInternal(initial = false)
                }
            }
        }

        fun sendTest() {
            _state.update { it.copy(testState = TestState.Sending, error = null) }
            viewModelScope.launch {
                try {
                    repository.sendTest()
                    _state.update { it.copy(testState = TestState.Sent) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(testState = TestState.Idle, error = PushUiError.from(e)) }
                }
            }
        }

        /** From the active state back to the form (new token, other distributor). */
        fun reconfigure() {
            _state.update {
                it.copy(
                    phase = if (it.distributors.isEmpty()) PushSetupPhase.InstallDistributor else PushSetupPhase.Form,
                    error = null,
                    testState = TestState.Idle,
                )
            }
        }

        fun disable() {
            _state.update { it.copy(phase = PushSetupPhase.Working, error = null) }
            viewModelScope.launch {
                try {
                    repository.tearDown()
                    _state.update { it.copy(phase = PushSetupPhase.Form, token = "", testState = TestState.Idle) }
                    refreshInternal(initial = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(phase = PushSetupPhase.Active, error = PushUiError.from(e)) }
                }
            }
        }

        private suspend fun PushRepository.isActive(): Boolean =
            runCatching { observeRegistration().first()?.kumaNotificationId != null }.getOrDefault(false)
    }
