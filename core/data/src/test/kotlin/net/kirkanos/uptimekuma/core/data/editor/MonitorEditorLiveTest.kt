package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.uptimekuma.core.data.FakeConfigStore
import net.kirkanos.uptimekuma.core.data.FakeKumaCache
import net.kirkanos.uptimekuma.core.data.FakeSecretStore
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.MonitorValidator
import net.kirkanos.uptimekuma.core.domain.editor.asId
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.testing.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Creates one monitor of every type (prefix `[P2-EDIT]`, created paused except http/push), edits one, clones
 * one, attaches/detaches a tag and deletes everything again, against the dev Kuma.
 * Run: `./gradlew :core:data:testDebugUnitTest -Pintegration --tests '*MonitorEditorLiveTest*'`.
 */
@Tag("integration")
class MonitorEditorLiveTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"
    private val spec = MonitorSpec.bundled

    private val values: Map<String, Map<String, Any?>> =
        mapOf(
            "http" to mapOf("url" to "https://example.com"),
            "keyword" to mapOf("url" to "https://example.com", "keyword" to "Example"),
            "json-query" to mapOf("url" to "https://example.com", "jsonPath" to "$.a", "expectedValue" to "1"),
            "port" to mapOf("hostname" to "example.com", "port" to "443"),
            "ping" to mapOf("hostname" to "example.com"),
            "dns" to
                mapOf(
                    "hostname" to "example.com",
                    "conditions" to
                        listOf(
                            mapOf(
                                "type" to "expression",
                                "variable" to "record",
                                "operator" to "contains",
                                "value" to "1",
                                "andOr" to "and",
                            ),
                        ),
                ),
            "system-service" to mapOf("system_service_name" to "nginx"),
            "pm2" to mapOf("system_service_name" to "app"),
            "real-browser" to mapOf("url" to "https://example.com"),
            "websocket-upgrade" to mapOf("url" to "wss://example.com/ws"),
            "sftp" to mapOf("hostname" to "example.com", "port" to 22, "sshUsername" to "u", "sshPassword" to "p"),
            "group" to emptyMap(),
            "push" to emptyMap(),
            "manual" to mapOf("manual_status" to 1),
            "globalping" to mapOf("hostname" to "example.com"),
            "grpc-keyword" to
                mapOf(
                    "grpcUrl" to "example.com:443",
                    "keyword" to "SERVING",
                    "grpcServiceName" to "Health",
                    "grpcMethod" to "check",
                ),
            "kafka-producer" to
                mapOf(
                    "kafkaProducerBrokers" to listOf("localhost:9092"),
                    "kafkaProducerTopic" to "t",
                    "kafkaProducerMessage" to "m",
                ),
            "mqtt" to
                mapOf(
                    "hostname" to "mqtt://localhost",
                    "port" to 1883,
                    "mqttTopic" to "t",
                    "mqttSuccessMessage" to "ok",
                ),
            "ntp" to mapOf("hostname" to "pool.ntp.org"),
            "rabbitmq" to
                mapOf(
                    "rabbitmqNodes" to listOf("http://localhost:15672"),
                    "rabbitmqUsername" to "u",
                    "rabbitmqPassword" to "p",
                ),
            "sip-options" to mapOf("hostname" to "example.com", "port" to 5060),
            "smtp" to mapOf("hostname" to "example.com", "port" to 25, "smtpSecurity" to "nostarttls"),
            "snmp" to
                mapOf(
                    "hostname" to "localhost",
                    "radiusPassword" to "public",
                    "snmpOid" to "1.3.6.1.2.1.1.1.0",
                    "expectedValue" to "x",
                ),
            "tailscale-ping" to mapOf("hostname" to "host"),
            "sqlserver" to emptyMap(),
            "mongodb" to mapOf("databaseQuery" to "{\"ping\":1}", "expectedValue" to "1"),
            "mysql" to emptyMap(),
            "oracledb" to mapOf("basic_auth_user" to "u", "basic_auth_pass" to "p"),
            "postgres" to emptyMap(),
            "radius" to
                mapOf(
                    "hostname" to "localhost",
                    "radiusUsername" to "u",
                    "radiusPassword" to "p",
                    "radiusSecret" to "s",
                    "radiusCalledStationId" to "a",
                    "radiusCallingStationId" to "b",
                ),
            "redis" to emptyMap(),
            "gamedig" to mapOf("game" to "minecraft", "hostname" to "example.com", "port" to 25565),
            "steam" to mapOf("hostname" to "example.com", "port" to 27015),
        )

    @Test
    fun `create every type, edit, clone, tag and delete`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                KumaConnectionManager(
                    SocketKumaApiFactory(),
                    FakeKumaCache(),
                    FakeConfigStore(),
                    FakeSecretStore(),
                    emptySet(),
                    scope,
                    ConnectionTimings(),
                )
            val remote = ManagerEditorRemote(manager)
            val tags = KumaTagRepository(remote, FakeMonitorRepository())
            val repo =
                KumaMonitorEditorRepository(remote, FakeMonitorRepository(), FakeKumaNotificationRepository(), tags)
            val created = mutableListOf<Long>()
            var tagId: Long? = null
            var tempDockerHost: Long? = null
            val report = linkedMapOf<String, String>()
            try {
                manager.setForeground(true)
                assertEquals(LoginResult.Success, manager.login(url, user, password, null))
                // A temporary docker host so the docker type can be created too (removed again below).
                val dockerHostAck =
                    remote.call(
                        "addDockerHost",
                        JsonValues.toJson(
                            mapOf(
                                "name" to "[P2-EDIT] docker",
                                "dockerType" to "socket",
                                "dockerDaemon" to "/var/run/docker.sock",
                            ),
                        ),
                        null,
                    )
                tempDockerHost = JsonValues.toKotlin(dockerHostAck["id"]).asId()
                val dockerHost =
                    withTimeoutOrNull(5_000) {
                        repo.observePickers().first { p -> p.dockerHosts.any { it.id == tempDockerHost } }
                    }?.dockerHosts?.first { it.id == tempDockerHost }
                val tag = tags.create("[P2-EDIT] tag", "#2563EB").also { tagId = it.id }

                for (type in spec.types.map { it.id }) {
                    var draft = repo.newDraft(type)
                    val extra = values[type].orEmpty().toMutableMap()
                    if (type == "docker") {
                        if (dockerHost == null) {
                            report[type] = "skipped (no docker host)"
                            continue
                        }
                        extra += mapOf("docker_container" to "kuma-dev", "docker_host" to dockerHost.id)
                    }
                    draft = extra.entries.fold(draft.with("name", "[P2-EDIT] $type")) { d, (k, v) -> d.with(k, v) }
                    if (type !in setOf("http", "push")) draft = draft.with("active", false)
                    if (type ==
                        "http"
                    ) {
                        draft = draft.copy(tags = listOf(MonitorTag(tag.id, tag.name, tag.color, "live")))
                    }
                    val validation = MonitorValidator.validate(spec, draft.fields)
                    report[type] =
                        try {
                            val id = repo.save(draft).also { created += it }
                            "accepted (id=$id)" + if (!validation.isValid) " client-errors=${validation.errors}" else ""
                        } catch (e: Exception) {
                            "REJECTED: ${e.message}" +
                                if (!validation.isValid) " client-errors=${validation.errors}" else ""
                        }
                }

                // Edit: rename http, change interval, detach the tag.
                val httpId = created.first()
                val loaded = repo.loadForEdit(httpId)
                assertEquals(listOf("live"), loaded.tags.map { it.value })
                val edited = loaded.with("name", "[P2-EDIT] http edited").with("interval", 45).copy(tags = emptyList())
                repo.save(edited)
                val reloaded = repo.loadForEdit(httpId)
                assertEquals("[P2-EDIT] http edited", reloaded.name)
                assertEquals(45L, reloaded["interval"])
                assertTrue(reloaded.tags.isEmpty())
                report["edit http"] = "ok (name, interval, tag detached)"

                // Clone keyword monitor with a tag.
                val keywordId = repo.loadForEdit(created[1]).id!!
                val clone: MonitorDraft = repo.clone(keywordId) { "[P2-EDIT] Copy of $it" }
                val cloneId = repo.save(clone.copy(tags = listOf(MonitorTag(tag.id, tag.name, tag.color, "clone"))))
                created += cloneId
                val cloned = repo.loadForEdit(cloneId)
                assertEquals("Example", cloned["keyword"])
                assertEquals(listOf("clone"), cloned.tags.map { it.value })
                report["clone keyword"] = "ok (id=$cloneId, tag attached)"
            } finally {
                for (id in created.reversed()) {
                    runCatching { withTimeout(10_000) { repo.delete(id, deleteChildren = true) } }
                        .onFailure { report["delete $id"] = "FAILED ${it.message}" }
                }
                tagId?.let { id ->
                    runCatching { tags.delete(id) }.onFailure {
                        report["delete tag"] =
                            "FAILED ${it.message}"
                    }
                }
                tempDockerHost?.let { id ->
                    runCatching { remote.call("deleteDockerHost", id) }.onFailure {
                        report["delete docker host"] =
                            "FAILED ${it.message}"
                    }
                }
                runCatching { manager.logout() }
                scope.cancel()
                println("P2-EDIT live check against $url:")
                report.forEach { (k, v) -> println("  $k: $v") }
                println("  cleanup: deleted ${created.size} monitors and the tag")
            }
            assertTrue(report.values.none { it.startsWith("FAILED") })
        }
}
