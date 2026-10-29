package net.kirkanos.uptimekuma.feature.notifications

import net.kirkanos.uptimekuma.core.model.KumaNotification

/** Sample notifications for previews and tests (configs as recorded from Kuma 2.5.8). */
internal object PreviewData {
    val webhook =
        KumaNotification(
            id = 1,
            name = "Dev webhook",
            type = "webhook",
            fields =
                mapOf(
                    "webhookURL" to "http://ntfy:80/upTESTTOPIC-webhook",
                    "webhookContentType" to "json",
                    "webhookCustomBody" to "",
                    "webhookAdditionalHeaders" to "",
                    "httpMethod" to "POST",
                ),
        )

    val ntfy =
        KumaNotification(
            id = 2,
            name = "Dev ntfy",
            type = "ntfy",
            isDefault = true,
            fields =
                mapOf(
                    "ntfyserverurl" to "http://ntfy:80",
                    "ntfytopic" to "upTESTTOPIC",
                    "ntfyPriority" to 5L,
                    "ntfyPriorityDown" to 5L,
                    "ntfyAuthenticationMethod" to "none",
                    "ntfyUseTemplate" to false,
                    "ntfyCall" to false,
                ),
        )

    val device =
        KumaNotification(
            id = 3,
            name = "Pixel 9 (UnifiedPush)",
            type = "webhook",
            isDefault = true,
            fields =
                mapOf(
                    "webhookURL" to "http://kuma-dev-ntfy:80/upAbCdEf?up=1",
                    "httpMethod" to "post",
                    "webhookContentType" to "custom",
                    "webhookCustomBody" to "{\"v\":1}",
                    "webhookAdditionalHeaders" to "{\"Authorization\":\"Bearer tk_x\"}",
                ),
        )

    val smtp =
        KumaNotification(
            id = 4,
            name = "Ops mail",
            type = "smtp",
            active = false,
            fields =
                mapOf(
                    "smtpHost" to "mail.example.org",
                    "smtpPort" to 587L,
                    "smtpSecure" to false,
                    "smtpFrom" to "kuma@example.org",
                ),
        )

    val unknown = KumaNotification(id = 5, name = "From the future", type = "hologram")

    val all = listOf(webhook, ntfy, device, smtp, unknown)
}
