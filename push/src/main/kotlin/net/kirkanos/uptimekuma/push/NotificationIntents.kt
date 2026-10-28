package net.kirkanos.uptimekuma.push

/** Extras put on the launcher intent of alert notifications. Read them in the app's activity. */
object NotificationIntents {
    /** Kuma monitor id (Long) the tapped notification refers to. Absent for test/generic alerts. */
    const val EXTRA_MONITOR_ID: String = "monitor_id"
}
