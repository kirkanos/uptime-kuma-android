package net.kirkanos.uptimekuma.core.kumaclient.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.kumaclient.KumaTime
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientBooleanSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientLongSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableDoubleSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableIntSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableLongSerializer
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.MonitorStatus

/**
 * Kuma's camelCase `HeartbeatJSON` (`heartbeat` event, `monitorImportantHeartbeatListPaged`).
 * `ping` is absent on maintenance beats, `duration` only present for push monitors,
 * `important` is a boolean in events but 0/1 in paged results.
 */
@Serializable
data class HeartbeatDto(
    @SerialName("monitorID") @Serializable(with = LenientLongSerializer::class) val monitorId: Long,
    val status: Int = MonitorStatus.PENDING.code,
    val time: String = "",
    val msg: String? = null,
    @Serializable(with = LenientNullableDoubleSerializer::class) val ping: Double? = null,
    @Serializable(with = LenientBooleanSerializer::class) val important: Boolean = false,
    @Serializable(with = LenientNullableIntSerializer::class) val duration: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val retries: Int? = null,
) {
    /** `null` when [time] cannot be parsed. */
    fun toModel(): Heartbeat? =
        KumaTime.parseUtcMillis(time)?.let { millis ->
            Heartbeat(
                monitorId = monitorId,
                status = MonitorStatus.fromCode(status),
                timeMillis = millis,
                message = msg.orEmpty(),
                pingMs = ping,
                important = important,
                durationSeconds = duration,
                retries = retries ?: 0,
            )
        }
}

/** Raw snake_case `heartbeat` DB row (`heartbeatList` event, `getMonitorBeats`). */
@Serializable
data class HeartbeatRowDto(
    @Serializable(with = LenientNullableLongSerializer::class) val id: Long? = null,
    @SerialName("monitor_id") @Serializable(with = LenientNullableLongSerializer::class) val monitorId: Long? = null,
    val status: Int = MonitorStatus.PENDING.code,
    val time: String = "",
    val msg: String? = null,
    @Serializable(with = LenientNullableDoubleSerializer::class) val ping: Double? = null,
    @Serializable(with = LenientBooleanSerializer::class) val important: Boolean = false,
    @Serializable(with = LenientNullableIntSerializer::class) val duration: Int? = null,
    @SerialName("down_count") @Serializable(with = LenientNullableIntSerializer::class) val downCount: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val retries: Int? = null,
    @SerialName("end_time") val endTime: String? = null,
) {
    /** [fallbackMonitorId] is used when the row lacks `monitor_id`. `null` when [time] is unparseable. */
    fun toModel(fallbackMonitorId: Long? = null): Heartbeat? {
        val millis = KumaTime.parseUtcMillis(time) ?: return null
        val monitor = monitorId ?: fallbackMonitorId ?: return null
        return Heartbeat(
            monitorId = monitor,
            status = MonitorStatus.fromCode(status),
            timeMillis = millis,
            message = msg.orEmpty(),
            pingMs = ping,
            important = important,
            durationSeconds = duration,
            retries = retries ?: 0,
        )
    }
}

/** One bucket of `getMonitorChartData`. Buckets without data are omitted by Kuma. */
@Serializable
data class ChartPointDto(
    @Serializable(with = LenientNullableIntSerializer::class) val up: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val down: Int? = null,
    @Serializable(with = LenientNullableDoubleSerializer::class) val avgPing: Double? = null,
    @Serializable(with = LenientNullableDoubleSerializer::class) val minPing: Double? = null,
    @Serializable(with = LenientNullableDoubleSerializer::class) val maxPing: Double? = null,
    /** Bucket start in unix **seconds**. */
    @Serializable(with = LenientLongSerializer::class) val timestamp: Long,
    @Serializable(with = LenientNullableIntSerializer::class) val maintenance: Int? = null,
) {
    fun toModel(): ChartPoint =
        ChartPoint(
            timeMillis = KumaTime.unixSecondsToMillis(timestamp),
            pingMs = avgPing,
            upCount = up ?: 0,
            downCount = down ?: 0,
        )
}
