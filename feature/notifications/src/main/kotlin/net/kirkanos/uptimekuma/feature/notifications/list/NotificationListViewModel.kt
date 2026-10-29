package net.kirkanos.uptimekuma.feature.notifications.list

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
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.NotificationAdminRepository
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.feature.notifications.runCatchingKuma
import javax.inject.Inject

/** One row of the list. [provider] is `null` for a type this app version does not know. */
data class NotificationRow(
    val notification: KumaNotification,
    val provider: ProviderSpec?,
    val isThisDevice: Boolean,
)

data class NotificationListUiState(
    /** `null` until the cache emitted. */
    val rows: List<NotificationRow>? = null,
    val refreshing: Boolean = false,
    /** Connecting to the server failed; the cached list is still shown. */
    val connectError: KumaError? = null,
) {
    val loading: Boolean get() = rows == null
    val isEmpty: Boolean get() = rows?.isEmpty() == true
}

private data class ListLocal(
    val refreshing: Boolean = false,
    val connectError: KumaError? = null,
)

@HiltViewModel
class NotificationListViewModel
    @Inject
    constructor(
        repository: KumaNotificationRepository,
        private val admin: NotificationAdminRepository,
    ) : ViewModel() {
        private val spec = NotificationProviderSpec.bundled
        private val local = MutableStateFlow(ListLocal())

        val state: StateFlow<NotificationListUiState> =
            combine(repository.observeNotifications(), local) { list, local ->
                NotificationListUiState(
                    rows = rows(list),
                    refreshing = local.refreshing,
                    connectError = local.connectError,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NotificationListUiState())

        init {
            refresh()
        }

        /**
         * Makes sure a connection exists, so Kuma pushes `notificationList` into the cache. `checkApprise` is
         * the cheapest authenticated call for that.
         */
        fun refresh() {
            if (local.value.refreshing) return
            local.update { it.copy(refreshing = true, connectError = null) }
            viewModelScope.launch {
                val error = runCatchingKuma { admin.isAppriseAvailable() }
                local.update { it.copy(refreshing = false, connectError = error) }
            }
        }

        private fun rows(list: List<KumaNotification>): List<NotificationRow> =
            list
                .sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER, KumaNotification::name).thenBy { it.id },
                ).map { NotificationRow(it, spec.provider(it.type), NotificationForm.isThisDevicePush(it)) }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
