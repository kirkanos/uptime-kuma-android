package net.kirkanos.uptimekuma.core.model

/** Heartbeat status codes as used by Uptime Kuma (`status` field of a heartbeat). */
enum class MonitorStatus(
    val code: Int,
) {
    DOWN(0),
    UP(1),
    PENDING(2),
    MAINTENANCE(3),
    ;

    companion object {
        fun fromCode(code: Int): MonitorStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}
