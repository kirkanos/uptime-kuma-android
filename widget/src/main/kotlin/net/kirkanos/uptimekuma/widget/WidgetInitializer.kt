package net.kirkanos.uptimekuma.widget

import net.kirkanos.uptimekuma.widget.state.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call [start] once from `Application.onCreate` (every process start, also those triggered by push or the
 * widget host). It observes the monitor cache and redraws the home-screen widget and the Quick Settings tile
 * when a monitor's status changes. Background syncs after a push additionally trigger a redraw through the
 * `MonitorStateListener` multibinding, so nothing else has to be wired.
 */
@Singleton
class WidgetInitializer
    @Inject
    constructor(
        private val updater: WidgetUpdater,
    ) {
        fun start() {
            updater.start()
        }
    }

/** Extras put on the launch intent when a widget row is tapped. */
object WidgetIntents {
    /**
     * Kuma monitor id (Long) of the tapped row. Deliberately the same value as
     * `net.kirkanos.uptimekuma.push.NotificationIntents.EXTRA_MONITOR_ID` (duplicated to avoid a widget -> push
     * module edge), so `MainActivity` handles widget taps like notification taps.
     */
    const val EXTRA_MONITOR_ID: String = "monitor_id"
}
