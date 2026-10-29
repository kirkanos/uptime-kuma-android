package net.kirkanos.uptimekuma.feature.notifications.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.NotificationAdminRepository
import net.kirkanos.uptimekuma.core.domain.notification.NotificationDraft
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationIssue
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.feature.notifications.NotificationEditRoute
import net.kirkanos.uptimekuma.feature.notifications.runCatchingKuma
import javax.inject.Inject

/** Outcome of "Test", shown once in a dialog. */
sealed interface TestResult {
    /** Kuma's (provider) success message, e.g. `Sent Successfully.`. */
    data class Success(
        val message: String?,
    ) : TestResult

    data class Failure(
        val error: KumaError,
    ) : TestResult
}

enum class Busy { TESTING, SAVING, DELETING }

data class NotificationEditUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** `null` for an unknown provider type: only the common fields are editable, the rest is kept. */
    val provider: ProviderSpec? = null,
    val draft: NotificationDraft? = null,
    val issues: List<NotificationIssue> = emptyList(),
    /** Field errors are shown after the first attempt to test or save. */
    val showErrors: Boolean = false,
    val busy: Busy? = null,
    val testResult: TestResult? = null,
    /** Save or delete failed. */
    val actionError: KumaError? = null,
    /** The device's own UnifiedPush webhook, managed by the push setup. */
    val isThisDevice: Boolean = false,
    /** `false` when the server has no apprise CLI (only checked for the apprise provider). */
    val appriseAvailable: Boolean? = null,
    /** Saved or deleted: leave the screen. */
    val done: Boolean = false,
) {
    val isNew: Boolean get() = draft?.isNew == true

    fun issueFor(field: String): NotificationIssue? = if (showErrors) issues.firstOrNull { it.field == field } else null
}

@HiltViewModel
class NotificationEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: KumaNotificationRepository,
        private val admin: NotificationAdminRepository,
    ) : ViewModel() {
        private val spec = NotificationProviderSpec.bundled
        private val notificationId: Long =
            savedStateHandle.get<Long>(NotificationEditRoute.ARG_ID) ?: NotificationEditRoute.NEW
        private val newType: String = savedStateHandle.get<String>(NotificationEditRoute.ARG_TYPE).orEmpty()
        private val mutableState = MutableStateFlow(NotificationEditUiState())
        val state: StateFlow<NotificationEditUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch { load() }
        }

        private suspend fun load() {
            if (notificationId == NotificationEditRoute.NEW) {
                val provider = spec.provider(newType)
                if (provider == null) {
                    mutableState.update { it.copy(loading = false, notFound = true) }
                    return
                }
                val names = repository.observeNotifications().first().map { it.name }
                setDraft(NotificationForm.newDraft(provider, names), provider)
                checkApprise(provider)
                return
            }
            // The cache may still be filling right after connecting: wait a moment for the row.
            val notification =
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    repository
                        .observeNotifications()
                        .map { list -> list.firstOrNull { it.id == notificationId } }
                        .filterNotNull()
                        .first()
                }
            if (notification == null) {
                mutableState.update { it.copy(loading = false, notFound = true) }
                return
            }
            val provider = spec.provider(notification.type)
            mutableState.update { it.copy(isThisDevice = NotificationForm.isThisDevicePush(notification)) }
            setDraft(NotificationForm.fromNotification(notification, provider), provider)
            checkApprise(provider)
        }

        private suspend fun checkApprise(provider: ProviderSpec?) {
            if (provider?.id != APPRISE) return
            var available: Boolean? = null
            runCatchingKuma { available = admin.isAppriseAvailable() }
            mutableState.update { it.copy(appriseAvailable = available) }
        }

        private fun setDraft(
            draft: NotificationDraft,
            provider: ProviderSpec?,
        ) {
            mutableState.update {
                it.copy(
                    loading = false,
                    provider = provider,
                    draft = draft,
                    issues = NotificationForm.validate(provider, draft),
                )
            }
        }

        private fun edit(transform: (NotificationDraft) -> NotificationDraft) {
            mutableState.update { state ->
                val draft = state.draft ?: return@update state
                val next = transform(draft)
                state.copy(draft = next, issues = NotificationForm.validate(state.provider, next))
            }
        }

        fun setName(name: String) = edit { it.copy(name = name) }

        fun setDefault(enabled: Boolean) = edit { it.copy(isDefault = enabled) }

        fun setApplyExisting(enabled: Boolean) = edit { it.copy(applyExisting = enabled) }

        fun setValue(
            field: String,
            value: Any?,
        ) = edit { it.copy(values = it.values + (field to value)) }

        fun setToggle(
            toggle: String,
            on: Boolean,
        ) = edit { it.copy(toggles = it.toggles + (toggle to on)) }

        /** Sends Kuma's test message through the current, unsaved configuration. */
        fun test() {
            val current = mutableState.value
            val draft = current.draft ?: return
            if (current.busy != null || !checkValid()) return
            mutableState.update { it.copy(busy = Busy.TESTING) }
            viewModelScope.launch {
                var message: String? = null
                val error =
                    runCatchingKuma {
                        message =
                            admin.sendTest(
                                NotificationForm.toNotification(current.provider, draft),
                            )
                    }
                mutableState.update {
                    it.copy(busy = null, testResult = error?.let(TestResult::Failure) ?: TestResult.Success(message))
                }
            }
        }

        fun save() {
            val current = mutableState.value
            val draft = current.draft ?: return
            if (current.busy != null || !checkValid()) return
            mutableState.update { it.copy(busy = Busy.SAVING, actionError = null) }
            viewModelScope.launch {
                val error =
                    runCatchingKuma {
                        repository.save(
                            NotificationForm.toNotification(current.provider, draft),
                            draft.isNew && draft.applyExisting,
                        )
                    }
                mutableState.update { it.copy(busy = null, actionError = error, done = error == null) }
            }
        }

        fun delete() {
            val id = mutableState.value.draft?.id ?: return
            if (mutableState.value.busy != null) return
            mutableState.update { it.copy(busy = Busy.DELETING, actionError = null) }
            viewModelScope.launch {
                val error = runCatchingKuma { repository.delete(id) }
                mutableState.update { it.copy(busy = null, actionError = error, done = error == null) }
            }
        }

        /** The test result dialog or the save/delete error was dismissed. */
        fun consumeMessages() = mutableState.update { it.copy(testResult = null, actionError = null) }

        private fun checkValid(): Boolean {
            mutableState.update { it.copy(showErrors = true) }
            return mutableState.value.issues.isEmpty()
        }

        private companion object {
            const val LOAD_TIMEOUT_MS = 10_000L
            const val APPRISE = "apprise"
        }
    }
