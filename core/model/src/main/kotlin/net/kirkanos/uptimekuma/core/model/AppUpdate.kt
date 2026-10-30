package net.kirkanos.uptimekuma.core.model

/** A release of this app that is newer than the installed one. */
data class AppUpdate(
    val version: String,
    /** Release page with the APKs and the changelog. */
    val url: String,
    /** The user hid the dashboard hint for this version; the settings still show it. */
    val dismissed: Boolean = false,
)
