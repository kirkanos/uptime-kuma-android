package net.kirkanos.uptimekuma.feature.monitors

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.model.Monitor

/** Display names of Kuma monitor types (product terms, identical in all languages except "group"). */
private val typeNames =
    mapOf(
        "http" to "HTTP(s)",
        "keyword" to "HTTP(s) – Keyword",
        "json-query" to "HTTP(s) – JSON Query",
        "port" to "TCP Port",
        "ping" to "Ping",
        "dns" to "DNS",
        "push" to "Push",
        "docker" to "Docker",
        "real-browser" to "Browser Engine",
        "steam" to "Steam Game Server",
        "gamedig" to "GameDig",
        "mqtt" to "MQTT",
        "kafka-producer" to "Kafka Producer",
        "grpc-keyword" to "gRPC(s) – Keyword",
        "sqlserver" to "Microsoft SQL Server",
        "postgres" to "PostgreSQL",
        "mysql" to "MySQL/MariaDB",
        "mongodb" to "MongoDB",
        "radius" to "Radius",
        "redis" to "Redis",
        "tailscale-ping" to "Tailscale Ping",
        "snmp" to "SNMP",
        "rabbitmq" to "RabbitMQ",
        "smtp" to "SMTP",
        "websocket-upgrade" to "WebSocket Upgrade",
    )

@Composable
fun monitorTypeLabel(type: String): String =
    if (type == Monitor.TYPE_GROUP) stringResource(R.string.monitors_type_group) else typeNames[type] ?: type

/** True when [target] can be opened in a browser. */
fun isWebUrl(target: String?): Boolean =
    target != null &&
        (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true))
