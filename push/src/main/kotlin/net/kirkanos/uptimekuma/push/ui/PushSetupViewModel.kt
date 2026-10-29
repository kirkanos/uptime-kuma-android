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
import net.kirkanos.uptimekuma.core.domain.PushTransportConfig
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.core.model.FirebaseConfigField
import net.kirkanos.uptimekuma.core.model.PushTransport
import net.kirkanos.uptimekuma.push.data.DistributorGateway
import net.kirkanos.uptimekuma.push.data.PushTokenStore
import net.kirkanos.uptimekuma.push.di.PushIoDispatcher
import net.kirkanos.uptimekuma.push.di.RelayTokenStore
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettings
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettingsStore
import net.kirkanos.uptimekuma.push.firebase.GoogleServicesParser
import net.kirkanos.uptimekuma.push.firebase.RelayUrl
import java.io.IOException
import javax.inject.Inject

@Immutable
data class DistributorOption(
    val packageName: String,
    val label: String,
)

enum class PushSetupPhase {
    Loading,

    /** `full` flavor, push not active: ntfy (UnifiedPush) or Firebase (own project)? */
    ChooseTransport,

    /** No UnifiedPush distributor installed: show "Install ntfy". */
    InstallDistributor,

    /** ntfy: distributor picker (if needed), token, "apply to all". */
    Form,

    /** Firebase: relay URL and token, Firebase project values, "apply to all". */
    FirebaseForm,

    /** Registering with the transport and creating the Kuma notification. */
    Working,

    /** Push is active: test, set up again, disable. */
    Active,
}

enum class TestState { Idle, Sending, Sent }

/** Phases a distributor refresh must not leave. */
private val keptPhases =
    setOf(PushSetupPhase.Working, PushSetupPhase.Active, PushSetupPhase.ChooseTransport, PushSetupPhase.FirebaseForm)

/** Input of the Firebase form. Validation is only shown for fields the user has filled. */
@Immutable
data class FirebaseFormState(
    val relayUrl: String = "",
    val relayToken: String = "",
    val projectId: String = "",
    val applicationId: String = "",
    val apiKey: String = "",
    val senderId: String = "",
) {
    val config: FirebaseConfig
        get() = FirebaseConfig(projectId, applicationId, apiKey, senderId)

    /** `null` while empty. */
    val relayCheck: RelayUrl.Check?
        get() = relayUrl.takeIf { it.isNotBlank() }?.let(RelayUrl::check)

    /** Invalid fields among the filled ones. */
    val invalidFields: Set<FirebaseConfigField>
        get() = config.invalidFields().filterTo(mutableSetOf()) { value(it).isNotBlank() }

    val complete: Boolean
        get() =
            relayUrl.isNotBlank() &&
                relayToken.isNotBlank() &&
                FirebaseConfigField.entries.all { value(it).isNotBlank() }

    fun value(field: FirebaseConfigField): String =
        when (field) {
            FirebaseConfigField.PROJECT_ID -> projectId
            FirebaseConfigField.APPLICATION_ID -> applicationId
            FirebaseConfigField.API_KEY -> apiKey
            FirebaseConfigField.SENDER_ID -> senderId
        }

    fun with(
        field: FirebaseConfigField,
        value: String,
    ): FirebaseFormState =
        when (field) {
            FirebaseConfigField.PROJECT_ID -> copy(projectId = value)
            FirebaseConfigField.APPLICATION_ID -> copy(applicationId = value)
            FirebaseConfigField.API_KEY -> copy(apiKey = value)
            FirebaseConfigField.SENDER_ID -> copy(senderId = value)
        }

    fun with(config: FirebaseConfig): FirebaseFormState =
        copy(
            projectId = config.projectId,
            applicationId = config.applicationId,
            apiKey = config.apiKey,
            senderId = config.gcmSenderId,
        )

    override fun toString(): String = "FirebaseFormState(relayUrl=$relayUrl, relayToken=***, config=$config)"
}

@Immutable
data class PushSetupUiState(
    val phase: PushSetupPhase = PushSetupPhase.Loading,
    val distributors: List<DistributorOption> = emptyList(),
    val selectedDistributor: String? = null,
    val token: String = "",
    val applyToAll: Boolean = true,
    val error: PushUiError? = null,
    val testState: TestState = TestState.Idle,
    /** Transports of this build; more than one = the picker is shown. */
    val transports: Set<PushTransport> = setOf(PushTransport.NTFY),
    /** Transport of the form being edited (picker choice). */
    val transport: PushTransport = PushTransport.NTFY,
    /** Transport of the active setup, `null` while push is not active. */
    val activeTransport: PushTransport? = null,
    val firebase: FirebaseFormState = FirebaseFormState(),
    /** The Firebase values were just filled from a google-services.json. */
    val firebaseImported: Boolean = false,
) {
    val canSubmit: Boolean
        get() =
            when (phase) {
                PushSetupPhase.Form -> token.isNotBlank() && selectedDistributor != null
                PushSetupPhase.FirebaseForm -> firebase.complete && firebase.relayCheck != RelayUrl.Check.INVALID
                else -> false
            }

    val canChooseTransport: Boolean
        get() = transports.size > 1
}

@HiltViewModel
class PushSetupViewModel
    @Inject
    constructor(
        private val repository: PushRepository,
        private val gateway: DistributorGateway,
        private val tokenStore: PushTokenStore,
        @RelayTokenStore private val relayTokenStore: PushTokenStore,
        private val firebaseSettings: FirebaseSettingsStore,
        @PushIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(PushSetupUiState())
        val state: StateFlow<PushSetupUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val stored =
                    withContext(ioDispatcher) {
                        StoredValues(
                            ntfyToken = runCatching { tokenStore.load() }.getOrNull(),
                            relayToken = runCatching { relayTokenStore.load() }.getOrNull(),
                            firebase = runCatching { firebaseSettings.load() }.getOrNull(),
                        )
                    }
                _state.update { current ->
                    val form = current.firebase
                    val prefilled =
                        if (form == FirebaseFormState() && stored.firebase != null) {
                            form.copy(relayUrl = stored.firebase.relayUrl).with(stored.firebase.firebase)
                        } else {
                            form
                        }
                    current.copy(
                        token = current.token.ifEmpty { stored.ntfyToken.orEmpty() },
                        firebase =
                            prefilled.copy(
                                relayToken = prefilled.relayToken.ifEmpty { stored.relayToken.orEmpty() },
                            ),
                        transports = repository.availableTransports(),
                    )
                }
                refreshInternal(initial = true)
            }
        }

        private class StoredValues(
            val ntfyToken: String?,
            val relayToken: String?,
            val firebase: FirebaseSettings?,
        )

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
            val active = if (initial) activeTransport() else null
            _state.update { current ->
                val selected =
                    current.selectedDistributor?.takeIf { sel -> options.any { it.packageName == sel } }
                        ?: preselect(options)
                val phase =
                    when {
                        active != null -> PushSetupPhase.Active
                        current.phase in keptPhases -> current.phase
                        initial && current.canChooseTransport -> PushSetupPhase.ChooseTransport
                        else -> ntfyPhase(options)
                    }
                current.copy(
                    distributors = options,
                    selectedDistributor = selected,
                    phase = phase,
                    activeTransport = active ?: current.activeTransport,
                    transport = active ?: current.transport,
                )
            }
        }

        private fun ntfyPhase(options: List<DistributorOption>): PushSetupPhase =
            if (options.isEmpty()) PushSetupPhase.InstallDistributor else PushSetupPhase.Form

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

        // --- Transport choice -------------------------------------------------------------------------

        fun onTransportSelected(transport: PushTransport) {
            if (transport !in _state.value.transports) return
            _state.update {
                it.copy(
                    transport = transport,
                    phase =
                        when (transport) {
                            PushTransport.NTFY -> ntfyPhase(it.distributors)
                            PushTransport.FIREBASE -> PushSetupPhase.FirebaseForm
                        },
                    error = null,
                )
            }
        }

        /** From a form back to the transport picker. */
        fun chooseTransport() {
            if (!_state.value.canChooseTransport) return
            _state.update { it.copy(phase = PushSetupPhase.ChooseTransport, error = null) }
        }

        // --- Firebase form ----------------------------------------------------------------------------

        fun onRelayUrlChange(url: String) =
            _state.update { it.copy(firebase = it.firebase.copy(relayUrl = url), error = null) }

        fun onRelayTokenChange(token: String) =
            _state.update { it.copy(firebase = it.firebase.copy(relayToken = token), error = null) }

        fun onFirebaseFieldChange(
            field: FirebaseConfigField,
            value: String,
        ) = _state.update { it.copy(firebase = it.firebase.with(field, value), error = null, firebaseImported = false) }

        /**
         * Fills the four Firebase values from a `google-services.json` picked with the system file picker.
         *
         * @param read reads the picked document (runs on the IO dispatcher).
         * @param packageName this app's package (`context.packageName`, `.debug` suffix in debug builds).
         */
        fun importGoogleServices(
            read: () -> String,
            packageName: String,
        ) {
            viewModelScope.launch {
                val text =
                    withContext(ioDispatcher) {
                        try {
                            read()
                        } catch (_: IOException) {
                            null
                        } catch (_: SecurityException) {
                            null
                        }
                    }
                val error: PushUiError? =
                    when (val result = text?.let { GoogleServicesParser.parse(it, packageName) }) {
                        null -> {
                            PushUiError.GoogleServicesInvalid(null)
                        }

                        is GoogleServicesParser.Result.Success -> {
                            _state.update {
                                it.copy(
                                    firebase = it.firebase.with(result.config),
                                    firebaseImported = true,
                                )
                            }
                            null
                        }

                        is GoogleServicesParser.Result.NoMatchingClient -> {
                            _state.update { it.copy(firebaseImported = false) }
                            PushUiError.GoogleServicesNoMatch(result.packageName, result.found)
                        }

                        is GoogleServicesParser.Result.Invalid -> {
                            PushUiError.GoogleServicesInvalid(result.missing)
                        }
                    }
                _state.update { it.copy(error = error) }
            }
        }

        // --- Setup ------------------------------------------------------------------------------------

        fun setUp() {
            val current = _state.value
            val config =
                when (current.transport) {
                    PushTransport.NTFY -> {
                        if (current.token.isBlank()) {
                            _state.update { it.copy(error = PushUiError.MissingToken) }
                            return
                        }
                        PushTransportConfig.Ntfy(current.token, current.selectedDistributor)
                    }

                    PushTransport.FIREBASE -> {
                        firebaseInputError(current.firebase)?.let { error ->
                            _state.update { it.copy(error = error) }
                            return
                        }
                        PushTransportConfig.Firebase(
                            relayUrl = current.firebase.relayUrl,
                            relayToken = current.firebase.relayToken,
                            firebase = current.firebase.config.trimmed(),
                        )
                    }
                }
            val formPhase =
                if (current.transport ==
                    PushTransport.FIREBASE
                ) {
                    PushSetupPhase.FirebaseForm
                } else {
                    PushSetupPhase.Form
                }
            _state.update { it.copy(phase = PushSetupPhase.Working, error = null, testState = TestState.Idle) }
            viewModelScope.launch {
                try {
                    repository.setUp(config, current.applyToAll)
                    _state.update { it.copy(phase = PushSetupPhase.Active, activeTransport = config.transport) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(phase = formPhase, error = PushUiError.from(e)) }
                    refreshInternal(initial = false)
                }
            }
        }

        private fun firebaseInputError(form: FirebaseFormState): PushUiError? =
            when {
                form.relayCheck == null || form.relayCheck == RelayUrl.Check.INVALID -> {
                    PushUiError.RelayUrlInvalid
                }

                form.relayToken.isBlank() -> {
                    PushUiError.MissingRelayToken
                }

                else -> {
                    form.config.invalidFields().takeIf { it.isNotEmpty() }?.let {
                        PushUiError.FirebaseConfigInvalid(
                            it,
                        )
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

        /** From the active state back to the form of the active transport (new token, other distributor). */
        fun reconfigure() {
            _state.update {
                val transport = it.activeTransport ?: it.transport
                it.copy(
                    transport = transport,
                    phase =
                        when (transport) {
                            PushTransport.NTFY -> ntfyPhase(it.distributors)
                            PushTransport.FIREBASE -> PushSetupPhase.FirebaseForm
                        },
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
                    _state.update {
                        it.copy(
                            phase = if (it.canChooseTransport) PushSetupPhase.ChooseTransport else PushSetupPhase.Form,
                            token = "",
                            firebase = it.firebase.copy(relayToken = ""),
                            activeTransport = null,
                            testState = TestState.Idle,
                        )
                    }
                    // Without the picker, this turns Form into InstallDistributor if ntfy is gone.
                    refreshInternal(initial = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(phase = PushSetupPhase.Active, error = PushUiError.from(e)) }
                }
            }
        }

        private suspend fun activeTransport(): PushTransport? =
            runCatching { repository.observeTransport().first() }.getOrNull()
    }
