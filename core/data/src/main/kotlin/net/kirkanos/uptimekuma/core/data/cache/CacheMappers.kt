package net.kirkanos.uptimekuma.core.data.cache

import net.kirkanos.uptimekuma.core.data.db.CertEntity
import net.kirkanos.uptimekuma.core.data.db.HeartbeatEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorTagEntity
import net.kirkanos.uptimekuma.core.data.db.NotificationEntity
import net.kirkanos.uptimekuma.core.data.db.TagEntity
import net.kirkanos.uptimekuma.core.data.db.UptimeEntity
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.model.UptimeStats

internal fun List<Long>.toIdString(): String = joinToString(",")

internal fun String.toIdList(): List<Long> = split(',').mapNotNull { it.trim().toLongOrNull() }

internal fun MonitorDto.toEntity(): MonitorEntity {
    val model = toModel()
    return MonitorEntity(
        id = model.id,
        name = model.name,
        type = model.type,
        description = model.description,
        url = model.url,
        hostname = model.hostname,
        port = model.port,
        active = model.active,
        forceInactive = model.forceInactive,
        parentId = model.parentId,
        childrenIds = model.childrenIds.toIdString(),
        pathName = model.pathName,
        intervalSeconds = model.intervalSeconds,
        notificationIds = model.notificationIds.sorted().toIdString(),
        expiryNotification = model.expiryNotification,
        weight = model.weight,
    )
}

internal fun MonitorDto.tagEntities(): List<MonitorTagEntity> =
    tags.mapIndexed { index, tag ->
        MonitorTagEntity(
            monitorId = id,
            tagId = tag.tagId,
            value = tag.value.orEmpty(),
            name = tag.name,
            color = tag.color,
            position = index,
        )
    }

internal fun MonitorEntity.toModel(tags: List<MonitorTagEntity>): Monitor =
    Monitor(
        id = id,
        name = name,
        type = type,
        description = description,
        url = url,
        hostname = hostname,
        port = port,
        active = active,
        forceInactive = forceInactive,
        parentId = parentId,
        childrenIds = childrenIds.toIdList(),
        pathName = pathName,
        intervalSeconds = intervalSeconds,
        tags = tags.sortedBy { it.position }.map { MonitorTag(it.tagId, it.name, it.color, it.value) },
        notificationIds = notificationIds.toIdList().toSet(),
        expiryNotification = expiryNotification,
        weight = weight,
    )

internal fun Heartbeat.toEntity(): HeartbeatEntity =
    HeartbeatEntity(
        monitorId = monitorId,
        timeMillis = timeMillis,
        status = status.code,
        message = message,
        pingMs = pingMs,
        important = important,
        durationSeconds = durationSeconds,
        retries = retries,
    )

internal fun HeartbeatEntity.toModel(): Heartbeat =
    Heartbeat(
        monitorId = monitorId,
        status = MonitorStatus.fromCode(status),
        timeMillis = timeMillis,
        message = message,
        pingMs = pingMs,
        important = important,
        durationSeconds = durationSeconds,
        retries = retries,
    )

internal fun UptimeEntity.toModel(): UptimeStats =
    UptimeStats(
        monitorId = monitorId,
        uptime24h = uptime24h,
        uptime30d = uptime30d,
        uptime1y = uptime1y,
        avgPingMs = avgPingMs,
    )

internal fun CertInfo.toEntity(): CertEntity =
    CertEntity(
        monitorId = monitorId,
        valid = valid,
        subject = subject,
        issuer = issuer,
        validFromMillis = validFromMillis,
        validToMillis = validToMillis,
        daysRemaining = daysRemaining,
        fingerprint256 = fingerprint256,
    )

internal fun CertEntity.toModel(): CertInfo =
    CertInfo(
        monitorId = monitorId,
        valid = valid,
        subject = subject,
        issuer = issuer,
        validFromMillis = validFromMillis,
        validToMillis = validToMillis,
        daysRemaining = daysRemaining,
        fingerprint256 = fingerprint256,
    )

internal fun NotificationDto.toEntity(): NotificationEntity =
    NotificationEntity(
        id = id,
        name = toModel().name,
        active = active,
        isDefault = isDefault || isDefaultRaw,
        config = config,
    )

internal fun NotificationEntity.toModel(): KumaNotification =
    NotificationConfig.toModel(
        NotificationDto(id = id, name = name, active = active, isDefault = isDefault, config = config),
    )

internal fun Tag.toEntity(): TagEntity = TagEntity(id = id, name = name, color = color)

internal fun TagEntity.toModel(): Tag = Tag(id = id, name = name, color = color)
