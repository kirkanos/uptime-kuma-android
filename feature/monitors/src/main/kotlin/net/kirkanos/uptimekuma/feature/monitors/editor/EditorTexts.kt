package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.domain.editor.FieldIssue
import net.kirkanos.uptimekuma.core.domain.editor.MonitorTypeSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.feature.monitors.R

/** Labels of the spec's fields (generated from the field catalogue, EN + DE). */
private val fieldLabels: Map<String, Int> =
    mapOf(
        "subtype" to R.string.editor_field_subtype,
        "name" to R.string.editor_field_name,
        "manual_status" to R.string.editor_field_manual_status,
        "url" to R.string.editor_field_url,
        "wsSubprotocol" to R.string.editor_field_ws_subprotocol,
        "headers" to R.string.editor_field_headers,
        "grpcUrl" to R.string.editor_field_grpc_url,
        "pushToken" to R.string.editor_field_push_token,
        "keyword" to R.string.editor_field_keyword,
        "invertKeyword" to R.string.editor_field_invert_keyword,
        "remote_browser" to R.string.editor_field_remote_browser,
        "game" to R.string.editor_field_game,
        "kafkaProducerBrokers" to R.string.editor_field_kafka_producer_brokers,
        "kafkaProducerTopic" to R.string.editor_field_kafka_producer_topic,
        "kafkaProducerMessage" to R.string.editor_field_kafka_producer_message,
        "kafkaProducerSsl" to R.string.editor_field_kafka_producer_ssl,
        "kafkaProducerAllowAutoTopicCreation" to R.string.editor_field_kafka_producer_allow_auto_topic_creation,
        "rabbitmqNodes" to R.string.editor_field_rabbitmq_nodes,
        "rabbitmqUsername" to R.string.editor_field_rabbitmq_username,
        "rabbitmqPassword" to R.string.editor_field_rabbitmq_password,
        "hostname" to R.string.editor_field_hostname,
        "location" to R.string.editor_field_location,
        "ipFamily" to R.string.editor_field_ip_family,
        "dns_resolve_server" to R.string.editor_field_dns_resolve_server,
        "port" to R.string.editor_field_port,
        "dns_resolve_type" to R.string.editor_field_dns_resolve_type,
        "protocol" to R.string.editor_field_protocol,
        "gamedigToken" to R.string.editor_field_gamedig_token,
        "radiusPassword" to R.string.editor_field_radius_password,
        "snmpOid" to R.string.editor_field_snmp_oid,
        "snmpVersion" to R.string.editor_field_snmp_version,
        "snmpV3Username" to R.string.editor_field_snmp_v3_username,
        "smtpSecurity" to R.string.editor_field_smtp_security,
        "sshUsername" to R.string.editor_field_ssh_username,
        "sshAuthMethod" to R.string.editor_field_ssh_auth_method,
        "sshPassword" to R.string.editor_field_ssh_password,
        "sshPrivateKey" to R.string.editor_field_ssh_private_key,
        "sshPassphrase" to R.string.editor_field_ssh_passphrase,
        "sftpPath" to R.string.editor_field_sftp_path,
        "ntpStratumThreshold" to R.string.editor_field_ntp_stratum_threshold,
        "ntpTimeOffsetThreshold" to R.string.editor_field_ntp_time_offset_threshold,
        "ntpRootDispersionThreshold" to R.string.editor_field_ntp_root_dispersion_threshold,
        "jsonPath" to R.string.editor_field_json_path,
        "jsonPathOperator" to R.string.editor_field_json_path_operator,
        "expectedValue" to R.string.editor_field_expected_value,
        "docker_container" to R.string.editor_field_docker_container,
        "docker_host" to R.string.editor_field_docker_host,
        "mqttUsername" to R.string.editor_field_mqtt_username,
        "mqttPassword" to R.string.editor_field_mqtt_password,
        "mqttTopic" to R.string.editor_field_mqtt_topic,
        "mqttWebsocketPath" to R.string.editor_field_mqtt_websocket_path,
        "mqttCheckType" to R.string.editor_field_mqtt_check_type,
        "mqttSuccessMessage" to R.string.editor_field_mqtt_success_message,
        "radiusUsername" to R.string.editor_field_radius_username,
        "radiusSecret" to R.string.editor_field_radius_secret,
        "radiusCalledStationId" to R.string.editor_field_radius_called_station_id,
        "radiusCallingStationId" to R.string.editor_field_radius_calling_station_id,
        "databaseConnectionString" to R.string.editor_field_database_connection_string,
        "basic_auth_user" to R.string.editor_field_basic_auth_user,
        "basic_auth_pass" to R.string.editor_field_basic_auth_pass,
        "system_service_name" to R.string.editor_field_system_service_name,
        "databaseQuery" to R.string.editor_field_database_query,
        "conditions" to R.string.editor_field_conditions,
        "interval" to R.string.editor_field_interval,
        "maxretries" to R.string.editor_field_maxretries,
        "retryInterval" to R.string.editor_field_retry_interval,
        "retryOnlyOnStatusCodeFailure" to R.string.editor_field_retry_only_on_status_code_failure,
        "timeout" to R.string.editor_field_timeout,
        "resendInterval" to R.string.editor_field_resend_interval,
        "expiryNotification" to R.string.editor_field_expiry_notification,
        "screenshot_delay" to R.string.editor_field_screenshot_delay,
        "domainExpiryNotification" to R.string.editor_field_domain_expiry_notification,
        "wsIgnoreSecWebsocketAcceptHeader" to R.string.editor_field_ws_ignore_sec_websocket_accept_header,
        "ignoreTls" to R.string.editor_field_ignore_tls,
        "cacheBust" to R.string.editor_field_cache_bust,
        "upsideDown" to R.string.editor_field_upside_down,
        "gamedigGivenPortOnly" to R.string.editor_field_gamedig_given_port_only,
        "ping_count" to R.string.editor_field_ping_count,
        "ping_numeric" to R.string.editor_field_ping_numeric,
        "packetSize" to R.string.editor_field_packet_size,
        "ping_per_request_timeout" to R.string.editor_field_ping_per_request_timeout,
        "accepted_statuscodes" to R.string.editor_field_accepted_statuscodes,
        "maxredirects" to R.string.editor_field_maxredirects,
        "saveErrorResponse" to R.string.editor_field_save_error_response,
        "saveResponse" to R.string.editor_field_save_response,
        "responseMaxLength" to R.string.editor_field_response_max_length,
        "parent" to R.string.editor_field_parent,
        "description" to R.string.editor_field_description,
        "tags" to R.string.editor_field_tags,
        "notificationIDList" to R.string.editor_field_notification_idlist,
        "authMethod" to R.string.editor_field_auth_method,
        "bearer_token" to R.string.editor_field_bearer_token,
        "oauth_auth_method" to R.string.editor_field_oauth_auth_method,
        "oauth_token_url" to R.string.editor_field_oauth_token_url,
        "oauth_client_id" to R.string.editor_field_oauth_client_id,
        "oauth_client_secret" to R.string.editor_field_oauth_client_secret,
        "oauth_scopes" to R.string.editor_field_oauth_scopes,
        "oauth_audience" to R.string.editor_field_oauth_audience,
        "tlsCert" to R.string.editor_field_tls_cert,
        "tlsKey" to R.string.editor_field_tls_key,
        "tlsCa" to R.string.editor_field_tls_ca,
        "authDomain" to R.string.editor_field_auth_domain,
        "authWorkstation" to R.string.editor_field_auth_workstation,
        "proxyId" to R.string.editor_field_proxy_id,
        "kafkaProducerSaslOptions" to R.string.editor_field_kafka_producer_sasl_options,
        "method" to R.string.editor_field_method,
        "httpBodyEncoding" to R.string.editor_field_http_body_encoding,
        "body" to R.string.editor_field_body,
        "responsecheck" to R.string.editor_field_responsecheck,
        "grpcEnableTls" to R.string.editor_field_grpc_enable_tls,
        "grpcServiceName" to R.string.editor_field_grpc_service_name,
        "grpcMethod" to R.string.editor_field_grpc_method,
        "grpcProtobuf" to R.string.editor_field_grpc_protobuf,
        "grpcBody" to R.string.editor_field_grpc_body,
        "grpcMetadata" to R.string.editor_field_grpc_metadata,
    )

/** Localized label of field [name]; the raw name for fields without a translation. */
@Composable
internal fun fieldLabel(name: String): String = fieldLabels[name]?.let { stringResource(it) } ?: name

internal fun hasFieldLabel(name: String): Boolean = name in fieldLabels

/** Localized type name: "Group" is translated, all other names are product terms from the spec. */
@Composable
internal fun typeLabel(type: MonitorTypeSpec): String =
    if (type.id == Monitor.TYPE_GROUP) stringResource(R.string.monitors_type_group) else type.label

private val optionLabels: Map<String, Map<Any?, Int>> =
    mapOf(
        "authMethod" to
            mapOf(
                null to R.string.editor_option_none,
                "basic" to R.string.editor_option_auth_basic,
                "bearer" to R.string.editor_option_auth_bearer,
                "oauth2-cc" to R.string.editor_option_auth_oauth,
                "ntlm" to R.string.editor_option_auth_ntlm,
                "mtls" to R.string.editor_option_auth_mtls,
            ),
        "ipFamily" to
            mapOf(
                null to R.string.editor_option_auto,
                "ipv4" to R.string.editor_option_ipv4,
                "ipv6" to R.string.editor_option_ipv6,
            ),
        "smtpSecurity" to
            mapOf(
                "secure" to R.string.editor_option_smtp_secure,
                "nostarttls" to R.string.editor_option_smtp_nostarttls,
                "starttls" to R.string.editor_option_smtp_starttls,
            ),
        "sshAuthMethod" to
            mapOf("password" to R.string.editor_option_ssh_password, "privateKey" to R.string.editor_option_ssh_key),
        "mqttCheckType" to
            mapOf("keyword" to R.string.editor_option_keyword, "json-query" to R.string.editor_option_json_query),
        "responsecheck" to
            mapOf(
                null to R.string.editor_option_status_only,
                "keyword" to R.string.editor_option_keyword,
                "json-query" to R.string.editor_option_json_query,
            ),
        "manual_status" to
            mapOf(
                null to R.string.editor_option_none,
                1L to R.string.editor_option_up,
                0L to R.string.editor_option_down,
            ),
        "oauth_auth_method" to
            mapOf(
                "client_secret_basic" to R.string.editor_option_oauth_basic,
                "client_secret_post" to R.string.editor_option_oauth_post,
            ),
        "protocol" to mapOf(null to R.string.editor_option_default),
        "subtype" to
            mapOf(
                "ping" to R.string.editor_option_ping,
                "http" to R.string.editor_option_http,
                "dns" to R.string.editor_option_dns,
            ),
    )

/** Display text of select option [value] of [field]. */
@Composable
internal fun optionLabel(
    field: String,
    value: Any?,
): String {
    val key = if (value is Int) value.toLong() else value
    val res = optionLabels[field]?.get(key) ?: if (key == null) R.string.editor_option_none else null
    return res?.let { stringResource(it) } ?: key.toString()
}

@Composable
internal fun sectionTitle(
    kind: SectionKind,
    type: MonitorTypeSpec?,
): String =
    when (kind) {
        SectionKind.GENERAL -> stringResource(R.string.editor_section_general)
        SectionKind.TYPE -> type?.let { stringResource(R.string.editor_section_type, typeLabel(it)) }.orEmpty()
        SectionKind.MONITORING -> stringResource(R.string.editor_section_monitoring)
        SectionKind.HTTP_REQUEST -> stringResource(R.string.editor_section_http)
        SectionKind.AUTH -> stringResource(R.string.editor_section_auth)
        SectionKind.ADVANCED -> stringResource(R.string.editor_section_advanced)
        SectionKind.PROXY -> stringResource(R.string.editor_section_proxy)
        SectionKind.NOTIFICATIONS -> stringResource(R.string.editor_section_notifications)
        SectionKind.TAGS -> stringResource(R.string.editor_section_tags)
    }

@Composable
internal fun issueText(issue: FieldIssue): String {
    val arg = issue.arg?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
    return when (issue.code) {
        FieldIssue.Code.REQUIRED -> stringResource(R.string.editor_error_required)
        FieldIssue.Code.NOT_A_NUMBER -> stringResource(R.string.editor_error_number)
        FieldIssue.Code.TOO_SMALL -> stringResource(R.string.editor_error_min, arg)
        FieldIssue.Code.TOO_LARGE -> stringResource(R.string.editor_error_max, arg)
        FieldIssue.Code.INVALID_URL -> stringResource(R.string.editor_error_url)
        FieldIssue.Code.INVALID_JSON -> stringResource(R.string.editor_error_json)
        FieldIssue.Code.INVALID_FORMAT -> stringResource(R.string.editor_error_format)
        FieldIssue.Code.INVALID_STATUS_CODE -> stringResource(R.string.editor_error_status_code)
        FieldIssue.Code.INVALID_NODE_URL -> stringResource(R.string.editor_error_node_url)
        FieldIssue.Code.NO_COMMA -> stringResource(R.string.editor_error_no_comma)
    }
}

/** Kuma ack messages (i18n keys and fixed server texts) the editor can translate. */
private val serverMessages: Map<String, Int> =
    mapOf(
        "Interval cannot be less than 1 seconds" to R.string.editor_server_interval,
        "Retry interval cannot be less than 1 seconds" to R.string.editor_server_retry_interval,
        "Accepted status codes are not all strings" to R.string.editor_server_status_codes,
        "PM2 process name is required." to R.string.editor_server_pm2_name,
        "Service Name is required." to R.string.editor_server_service_name,
        "Invalid Monitor Group" to R.string.editor_server_invalid_group,
        "Permission denied." to R.string.editor_server_permission,
        "tagNotFound" to R.string.editor_server_tag_not_found,
        "Monitor not found" to R.string.editor_server_monitor_not_found,
    )

@StringRes
internal fun serverMessageRes(message: String?): Int? = message?.let { serverMessages[it.trim()] }

/** Translated Kuma message when known, Kuma's own text for other rejections, otherwise the generic error text. */
@Composable
internal fun editorErrorText(error: KumaError): String {
    val known = serverMessageRes(error.message)
    return when {
        known != null -> stringResource(known)
        error.kind == KumaError.Kind.SERVER_REJECTED && !error.message.isNullOrBlank() -> error.message.orEmpty()
        else -> error.localizedMessage()
    }
}
