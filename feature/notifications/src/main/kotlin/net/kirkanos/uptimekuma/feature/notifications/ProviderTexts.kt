package net.kirkanos.uptimekuma.feature.notifications

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import net.kirkanos.uptimekuma.core.domain.notification.ProviderCategory
import net.kirkanos.uptimekuma.core.domain.notification.ProviderFieldSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec

/** Hand-written label and optional help text of a provider field. */
internal data class FieldText(
    @param:StringRes val label: Int,
    @param:StringRes val help: Int? = null,
)

@StringRes
internal fun ProviderCategory.labelRes(): Int =
    when (this) {
        ProviderCategory.UNIVERSAL -> R.string.notif_category_universal
        ProviderCategory.CHAT -> R.string.notif_category_chat
        ProviderCategory.PUSH -> R.string.notif_category_push
        ProviderCategory.SMS -> R.string.notif_category_sms
        ProviderCategory.EMAIL -> R.string.notif_category_email
        ProviderCategory.INCIDENT -> R.string.notif_category_incident
        ProviderCategory.HOME_AUTOMATION -> R.string.notif_category_home
        ProviderCategory.REGIONAL -> R.string.notif_category_regional
        ProviderCategory.OTHER -> R.string.notif_category_other
    }

internal val ProviderCategory.icon: ImageVector
    get() =
        when (this) {
            ProviderCategory.UNIVERSAL -> Icons.Outlined.Hub
            ProviderCategory.CHAT -> Icons.Outlined.Forum
            ProviderCategory.PUSH -> Icons.Outlined.NotificationsActive
            ProviderCategory.SMS -> Icons.Outlined.Sms
            ProviderCategory.EMAIL -> Icons.Outlined.Email
            ProviderCategory.INCIDENT -> Icons.Outlined.ReportProblem
            ProviderCategory.HOME_AUTOMATION -> Icons.Outlined.Home
            ProviderCategory.REGIONAL -> Icons.Outlined.Language
            ProviderCategory.OTHER -> Icons.Outlined.Extension
        }

/** Icon of a provider: the icon of its first category. */
internal val ProviderSpec?.icon: ImageVector get() = (this?.categories?.firstOrNull() ?: ProviderCategory.OTHER).icon

/**
 * Labels for fields of providers without hand-written texts: the field name without the provider prefix,
 * split at camel case (`telegramBotToken` -> `Bot Token`, `smtpDkimDomain` -> `Dkim Domain`).
 */
internal object FieldLabels {
    private val acronyms = mapOf("url" to "URL", "id" to "ID", "api" to "API", "sms" to "SMS", "ttl" to "TTL")
    private val camel = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_+|-+")

    fun humanize(
        providerId: String,
        name: String,
    ): String {
        val stripped =
            name
                .takeIf { it.length > providerId.length && it.startsWith(providerId, ignoreCase = true) }
                ?.substring(providerId.length)
                ?.trimStart('_')
                ?: name
        val words = stripped.split(camel).filter { it.isNotBlank() }
        if (words.isEmpty()) return name
        return words
            .mapIndexed { index, word ->
                acronyms[word.lowercase()] ?: if (index == 0) word.replaceFirstChar { it.uppercaseChar() } else word
            }.joinToString(" ")
    }
}

/** Hand-tuned texts for the most used providers, keyed by `"<type>.<field>"`. */
@Suppress("LargeClass")
internal object ProviderTexts {
    private val template = FieldText(R.string.notif_label_message_template, R.string.notif_help_template)
    private val useTemplate = FieldText(R.string.notif_label_use_template)

    val fields: Map<String, FieldText> =
        buildMap {
            fun put(
                type: String,
                field: String,
                @StringRes label: Int,
                @StringRes help: Int? = null,
            ) = put("$type.$field", FieldText(label, help))

            // Telegram
            put("telegram", "telegramBotToken", R.string.notif_label_bot_token, R.string.notif_help_telegram_token)
            put("telegram", "telegramChatID", R.string.notif_label_chat_id, R.string.notif_help_telegram_chat)
            put(
                "telegram",
                "telegramMessageThreadID",
                R.string.notif_label_thread_id,
                R.string.notif_help_telegram_thread,
            )
            put("telegram", "telegramServerUrl", R.string.notif_label_server_url, R.string.notif_help_telegram_server)
            put("telegram.telegramUseTemplate", useTemplate)
            put("telegram", "telegramTemplateParseMode", R.string.notif_label_message_format)
            put("telegram.telegramTemplate", template)
            put("telegram", "telegramSendSilently", R.string.notif_label_send_silently)
            put("telegram", "telegramProtectContent", R.string.notif_label_protect_content)
            // Discord
            put("discord", "discordWebhookUrl", R.string.notif_label_webhook_url, R.string.notif_help_discord_webhook)
            put("discord", "discordUsername", R.string.notif_label_bot_name)
            put("discord", "discordPrefixMessage", R.string.notif_label_prefix_message)
            put("discord", "discordMessageFormat", R.string.notif_label_message_format)
            put("discord.discordMessageTemplate", template)
            put("discord", "discordChannelType", R.string.notif_label_channel_type)
            put("discord", "postName", R.string.notif_label_post_name)
            put("discord", "threadId", R.string.notif_label_discord_thread, R.string.notif_help_discord_thread)
            put("discord", "disableUrl", R.string.notif_label_disable_url)
            put("discord", "discordSuppressNotifications", R.string.notif_label_suppress)
            // Slack
            put("slack", "slackwebhookURL", R.string.notif_label_webhook_url, R.string.notif_help_slack_webhook)
            put("slack", "slackusername", R.string.notif_label_username)
            put("slack", "slackiconemo", R.string.notif_label_icon_emoji)
            put("slack", "slackchannel", R.string.notif_label_channel, R.string.notif_help_slack_channel)
            put("slack", "slackrichmessage", R.string.notif_label_rich_message)
            put("slack", "slackIncludeGroupName", R.string.notif_label_include_group)
            put("slack.slackUseTemplate", useTemplate)
            put("slack.slackTemplate", template)
            put("slack", "slackchannelnotify", R.string.notif_label_channel_notify)
            // Email (SMTP)
            put("smtp", "smtpHost", R.string.notif_label_smtp_host)
            put("smtp", "smtpPort", R.string.notif_label_port)
            put("smtp", "smtpSecure", R.string.notif_label_security, R.string.notif_help_smtp_security)
            put("smtp", "smtpIgnoreTLSError", R.string.notif_label_ignore_tls)
            put("smtp", "smtpIgnoreSTARTTLS", R.string.notif_label_ignore_starttls)
            put("smtp", "smtpUsername", R.string.notif_label_username)
            put("smtp", "smtpPassword", R.string.notif_label_password)
            put("smtp", "smtpFrom", R.string.notif_label_from, R.string.notif_help_smtp_from)
            put("smtp", "smtpTo", R.string.notif_label_to, R.string.notif_help_smtp_recipients)
            put("smtp", "smtpCC", R.string.notif_label_cc, R.string.notif_help_smtp_recipients)
            put("smtp", "smtpBCC", R.string.notif_label_bcc, R.string.notif_help_smtp_recipients)
            put("smtp", "customSubject", R.string.notif_label_subject, R.string.notif_help_smtp_subject)
            put("smtp", "customBody", R.string.notif_label_body, R.string.notif_help_smtp_body)
            put("smtp", "htmlBody", R.string.notif_label_html_body)
            put("smtp", "smtpAdditionalHeaders", R.string.notif_label_additional_headers, R.string.notif_help_headers)
            put("smtp", "smtpDkimDomain", R.string.notif_label_dkim_domain)
            put("smtp", "smtpDkimKeySelector", R.string.notif_label_dkim_selector)
            put("smtp", "smtpDkimPrivateKey", R.string.notif_label_dkim_key)
            put("smtp", "smtpDkimHashAlgo", R.string.notif_label_dkim_hash)
            put("smtp", "smtpDkimheaderFieldNames", R.string.notif_label_dkim_headers)
            put("smtp", "smtpDkimskipFields", R.string.notif_label_dkim_skip)
            // ntfy
            put("ntfy", "ntfyserverurl", R.string.notif_label_server_url, R.string.notif_help_ntfy_server)
            put("ntfy", "ntfytopic", R.string.notif_label_topic)
            put("ntfy", "ntfyPriority", R.string.notif_label_priority, R.string.notif_help_ntfy_priority)
            put("ntfy", "ntfyPriorityDown", R.string.notif_label_priority_down, R.string.notif_help_ntfy_priority)
            put("ntfy", "ntfyAuthenticationMethod", R.string.notif_label_authentication)
            put("ntfy", "ntfyusername", R.string.notif_label_username)
            put("ntfy", "ntfypassword", R.string.notif_label_password)
            put("ntfy", "ntfyaccesstoken", R.string.notif_label_access_token)
            put("ntfy", "ntfyIcon", R.string.notif_label_icon_url)
            put("ntfy", "ntfyCall", R.string.notif_label_ntfy_call, R.string.notif_help_ntfy_call)
            put("ntfy", "ntfyUseTemplate", R.string.notif_label_ntfy_use_template)
            put("ntfy", "ntfyCustomTitle", R.string.notif_label_title_template, R.string.notif_help_template)
            put("ntfy.ntfyCustomMessage", template)
            // Gotify
            put("gotify", "gotifyserverurl", R.string.notif_label_server_url)
            put("gotify", "gotifyapplicationToken", R.string.notif_label_app_token)
            put("gotify", "gotifyPriority", R.string.notif_label_priority, R.string.notif_help_gotify_priority)
            // Pushover
            put("pushover", "pushoveruserkey", R.string.notif_label_user_key)
            put("pushover", "pushoverapptoken", R.string.notif_label_app_token)
            put("pushover", "pushoverdevice", R.string.notif_label_device)
            put("pushover", "pushovertitle", R.string.notif_label_title)
            put("pushover", "pushoverpriority", R.string.notif_label_priority, R.string.notif_help_pushover_priority)
            put("pushover", "pushoversounds", R.string.notif_label_sound_down)
            put("pushover", "pushoversounds_up", R.string.notif_label_sound_up)
            put("pushover", "pushoverttl", R.string.notif_label_ttl)
            // Webhook
            put("webhook", "webhookURL", R.string.notif_label_post_url)
            put("webhook", "httpMethod", R.string.notif_label_method)
            put("webhook", "webhookContentType", R.string.notif_label_request_body, R.string.notif_help_webhook_body)
            put("webhook", "webhookCustomBody", R.string.notif_label_custom_body, R.string.notif_help_template)
            put(
                "webhook",
                "webhookAdditionalHeaders",
                R.string.notif_label_additional_headers,
                R.string.notif_help_headers,
            )
            // Matrix
            put("matrix", "homeserverUrl", R.string.notif_label_homeserver)
            put("matrix", "internalRoomId", R.string.notif_label_room_id, R.string.notif_help_matrix_room)
            put("matrix", "accessToken", R.string.notif_label_access_token, R.string.notif_help_matrix_token)
            put("matrix.matrixUseTemplate", useTemplate)
            put("matrix.matrixTemplate", template)
            // Microsoft Teams
            put("teams", "webhookUrl", R.string.notif_label_webhook_url, R.string.notif_help_teams_webhook)
            put("teams", "teamsEnableTags", R.string.notif_label_teams_tags)
            // Signal
            put("signal", "signalURL", R.string.notif_label_post_url, R.string.notif_help_signal_url)
            put("signal", "signalNumber", R.string.notif_label_signal_number)
            put("signal", "signalRecipients", R.string.notif_label_recipients, R.string.notif_help_signal_recipients)
            put("signal.signalUseTemplate", useTemplate)
            put("signal.signalTemplate", template)
            // Apprise
            put("apprise", "appriseURL", R.string.notif_label_apprise_url, R.string.notif_help_apprise_url)
            put("apprise", "title", R.string.notif_label_title)
            // Home Assistant
            put("HomeAssistant", "homeAssistantUrl", R.string.notif_label_ha_url)
            put("HomeAssistant", "longLivedAccessToken", R.string.notif_label_ha_token, R.string.notif_help_ha_token)
            put("HomeAssistant", "notificationService", R.string.notif_label_ha_service, R.string.notif_help_ha_service)
            // Pushbullet
            put(
                "pushbullet",
                "pushbulletAccessToken",
                R.string.notif_label_access_token,
                R.string.notif_help_pushbullet,
            )
            // Mattermost
            put(
                "mattermost",
                "mattermostWebhookUrl",
                R.string.notif_label_webhook_url,
                R.string.notif_help_mattermost_webhook,
            )
            put("mattermost", "mattermostusername", R.string.notif_label_username)
            put("mattermost", "mattermosticonurl", R.string.notif_label_icon_url)
            put("mattermost", "mattermosticonemo", R.string.notif_label_icon_emoji)
            put("mattermost", "mattermostchannel", R.string.notif_label_channel)
        }

    /** Translated option labels, keyed by `"<type>.<field>"` then the option value as string. */
    val options: Map<String, Map<String, Int>> =
        mapOf(
            "smtp.smtpSecure" to
                mapOf("false" to R.string.notif_option_smtp_none, "true" to R.string.notif_option_smtp_tls),
            "webhook.webhookContentType" to
                mapOf(
                    "json" to R.string.notif_option_ct_json,
                    "form-data" to R.string.notif_option_ct_form,
                    "custom" to R.string.notif_option_ct_custom,
                ),
            "ntfy.ntfyAuthenticationMethod" to
                mapOf(
                    "none" to R.string.notif_option_auth_none,
                    "usernamePassword" to R.string.notif_option_auth_userpass,
                    "accessToken" to R.string.notif_option_auth_token,
                ),
            "discord.discordMessageFormat" to
                mapOf(
                    "normal" to R.string.notif_option_format_normal,
                    "minimalist" to R.string.notif_option_format_minimal,
                    "custom" to R.string.notif_option_format_custom,
                ),
            "discord.discordChannelType" to
                mapOf(
                    "channel" to R.string.notif_option_channel_channel,
                    "createNewForumPost" to R.string.notif_option_channel_forum,
                    "postToThread" to R.string.notif_option_channel_thread,
                ),
            "telegram.telegramTemplateParseMode" to mapOf("plain" to R.string.notif_option_plain),
        )

    /** Labels of UI-only switches (visibility toggles that are not stored). */
    val toggles: Map<String, Int> = mapOf("showAdditionalHeadersField" to R.string.notif_label_additional_headers)

    /** Short explanation shown above the form of a provider. */
    val intro: Map<String, Int> =
        mapOf(
            "telegram" to R.string.notif_intro_telegram,
            "webhook" to R.string.notif_intro_webhook,
            "ntfy" to R.string.notif_intro_ntfy,
            "smtp" to R.string.notif_intro_smtp,
            "apprise" to R.string.notif_intro_apprise,
        )

    fun field(
        type: String,
        field: ProviderFieldSpec,
    ): FieldText? = fields["$type.${field.name}"]
}

/** A titled group of fields in the editor. [title] `null` = untitled (generic providers). */
internal data class FormSection(
    @param:StringRes val title: Int?,
    val fields: List<ProviderFieldSpec>,
    val collapsed: Boolean = false,
)

/** Section layout of the hand-tuned providers; others get one untitled section in spec order. */
internal object ProviderLayouts {
    private class Group(
        @param:StringRes val title: Int,
        val fields: List<String>,
        val collapsed: Boolean = false,
    )

    private val layouts: Map<String, List<Group>> =
        mapOf(
            "smtp" to
                listOf(
                    Group(
                        R.string.notif_section_server,
                        listOf("smtpHost", "smtpPort", "smtpSecure", "smtpIgnoreSTARTTLS", "smtpIgnoreTLSError"),
                    ),
                    Group(R.string.notif_section_auth, listOf("smtpUsername", "smtpPassword")),
                    Group(R.string.notif_section_addresses, listOf("smtpFrom", "smtpTo", "smtpCC", "smtpBCC")),
                    Group(R.string.notif_section_content, listOf("customSubject", "customBody", "htmlBody")),
                    Group(
                        R.string.notif_section_advanced,
                        listOf(
                            "smtpAdditionalHeaders",
                            "smtpDkimDomain",
                            "smtpDkimKeySelector",
                            "smtpDkimPrivateKey",
                            "smtpDkimHashAlgo",
                            "smtpDkimheaderFieldNames",
                            "smtpDkimskipFields",
                        ),
                        collapsed = true,
                    ),
                ),
            "telegram" to
                listOf(
                    Group(
                        R.string.notif_section_connection,
                        listOf("telegramBotToken", "telegramChatID", "telegramMessageThreadID", "telegramServerUrl"),
                    ),
                    Group(
                        R.string.notif_section_message,
                        listOf("telegramUseTemplate", "telegramTemplateParseMode", "telegramTemplate"),
                    ),
                    Group(R.string.notif_section_options, listOf("telegramSendSilently", "telegramProtectContent")),
                ),
            "discord" to
                listOf(
                    Group(
                        R.string.notif_section_connection,
                        listOf("discordWebhookUrl", "discordUsername", "discordPrefixMessage"),
                    ),
                    Group(R.string.notif_section_message, listOf("discordMessageFormat", "discordMessageTemplate")),
                    Group(R.string.notif_section_channel, listOf("discordChannelType", "postName", "threadId")),
                    Group(R.string.notif_section_options, listOf("disableUrl", "discordSuppressNotifications")),
                ),
            "webhook" to
                listOf(
                    Group(
                        R.string.notif_section_request,
                        listOf("webhookURL", "httpMethod", "webhookContentType", "webhookCustomBody"),
                    ),
                    Group(R.string.notif_section_headers, listOf("webhookAdditionalHeaders")),
                ),
            "ntfy" to
                listOf(
                    Group(R.string.notif_section_server, listOf("ntfyserverurl", "ntfytopic")),
                    Group(R.string.notif_section_priority, listOf("ntfyPriority", "ntfyPriorityDown")),
                    Group(
                        R.string.notif_section_auth,
                        listOf("ntfyAuthenticationMethod", "ntfyusername", "ntfypassword", "ntfyaccesstoken"),
                    ),
                    Group(
                        R.string.notif_section_message,
                        listOf("ntfyIcon", "ntfyCall", "ntfyUseTemplate", "ntfyCustomTitle", "ntfyCustomMessage"),
                    ),
                ),
            "slack" to
                listOf(
                    Group(
                        R.string.notif_section_connection,
                        listOf("slackwebhookURL", "slackusername", "slackiconemo", "slackchannel"),
                    ),
                    Group(
                        R.string.notif_section_message,
                        listOf(
                            "slackrichmessage",
                            "slackIncludeGroupName",
                            "slackchannelnotify",
                            "slackUseTemplate",
                            "slackTemplate",
                        ),
                    ),
                ),
            "pushover" to
                listOf(
                    Group(
                        R.string.notif_section_connection,
                        listOf("pushoveruserkey", "pushoverapptoken", "pushoverdevice"),
                    ),
                    Group(
                        R.string.notif_section_message,
                        listOf(
                            "pushovertitle",
                            "pushoverpriority",
                            "pushoversounds",
                            "pushoversounds_up",
                            "pushoverttl",
                        ),
                    ),
                ),
        )

    /** Field names of hand-tuned layouts, for tests. */
    val curatedFieldNames: Map<String, List<String>> get() =
        layouts.mapValues { (_, groups) ->
            groups.flatMap { it.fields }
        }

    /**
     * Sections of [provider]. Spec fields missing from a hand-tuned layout (e.g. after a Kuma update) are
     * appended in a "More options" section, so nothing is ever hidden.
     */
    fun sections(provider: ProviderSpec): List<FormSection> {
        val groups = layouts[provider.id] ?: return listOf(FormSection(null, provider.fields))
        val used = mutableSetOf<String>()
        val sections =
            groups.mapNotNull { group ->
                val fields = group.fields.mapNotNull { provider.field(it) }
                used += fields.map { it.name }
                fields.takeIf { it.isNotEmpty() }?.let { FormSection(group.title, it, group.collapsed) }
            }
        val rest = provider.fields.filterNot { it.name in used }
        return if (rest.isEmpty()) sections else sections + FormSection(R.string.notif_section_more, rest)
    }
}
