package net.kirkanos.uptimekuma.core.domain.editor

import net.kirkanos.uptimekuma.core.model.MonitorTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorFormLogicTest {
    private val spec = MonitorSpec.bundled

    private fun draft(
        type: String,
        vararg values: Pair<String, Any?>,
    ): Map<String, Any?> = MonitorDrafts.newDraft(spec, type).fields + values

    private fun visible(values: Map<String, Any?>) = MonitorFieldRules.visibleFields(spec, values).map { it.name }

    @Test
    fun `http form shows auth sub-fields only for the selected method`() {
        val none = visible(draft("http"))
        assertTrue("url" in none && "authMethod" in none && "proxyId" in none)
        assertFalse("basic_auth_user" in none || "bearer_token" in none || "tlsCert" in none)
        assertFalse("retryInterval" in none, "hidden while maxretries == 0")
        assertTrue("bearer_token" in visible(draft("http", "authMethod" to "bearer")))
        val ntlm = visible(draft("http", "authMethod" to "ntlm"))
        assertTrue(ntlm.containsAll(listOf("basic_auth_user", "basic_auth_pass", "authDomain", "authWorkstation")))
        assertTrue(visible(draft("http", "maxretries" to "2")).contains("retryInterval"))
    }

    @Test
    fun `group and push have no target fields`() {
        val group = visible(draft("group"))
        assertFalse(group.any { it in setOf("url", "hostname", "port") })
        val push = visible(draft("push"))
        assertTrue("pushToken" in push)
        assertFalse("url" in push)
    }

    @Test
    fun `globalping sub forms follow subtype and check mode`() {
        val ping = visible(draft("globalping"))
        assertTrue("hostname" in ping && "ping_count" in ping)
        assertFalse("url" in ping || "port" in ping)
        assertTrue("port" in visible(draft("globalping", "protocol" to "TCP")))
        val http = visible(draft("globalping", "subtype" to "http"))
        assertTrue("url" in http && "responsecheck" in http && "method" in http)
        assertFalse("keyword" in http)
        assertTrue("keyword" in visible(draft("globalping", "subtype" to "http", "responsecheck" to "keyword")))
        assertTrue("jsonPath" in visible(draft("globalping", "subtype" to "http", "responsecheck" to "json-query")))
    }

    @Test
    fun `conditions only for types with condition variables`() {
        assertTrue("conditions" in visible(draft("dns")))
        assertTrue("conditions" in visible(draft("mqtt")))
        assertFalse("conditions" in visible(draft("postgres")))
        assertFalse("conditions" in visible(draft("http")))
    }

    @Test
    fun `sftp and snmp toggles`() {
        assertTrue("sshPassword" in visible(draft("sftp")))
        val key = visible(draft("sftp", "sshAuthMethod" to "privateKey"))
        assertTrue("sshPrivateKey" in key && "sshPassphrase" in key && "sshPassword" !in key)
        assertFalse("snmpV3Username" in visible(draft("snmp")))
        assertTrue("snmpV3Username" in visible(draft("snmp", "snmpVersion" to "3")))
    }

    @Test
    fun `validation mirrors the server rules`() {
        val ok = draft("http", "name" to "x", "url" to "https://example.com")
        assertTrue(MonitorValidator.validate(spec, ok).isValid)

        val errors =
            MonitorValidator
                .validate(
                    spec,
                    ok + mapOf("interval" to "0", "url" to "ftp://x", "headers" to "[1]"),
                ).errors
        assertEquals(FieldIssue.Code.TOO_SMALL, errors["interval"]?.code)
        assertEquals(FieldIssue.Code.INVALID_URL, errors["url"]?.code)
        assertEquals(FieldIssue.Code.INVALID_JSON, errors["headers"]?.code)

        assertEquals(
            FieldIssue.Code.INVALID_JSON,
            MonitorValidator.validate(spec, ok + ("body" to "{")).errors["body"]?.code,
        )
        assertNull(
            MonitorValidator.validate(spec, ok + mapOf("body" to "a=b", "httpBodyEncoding" to "form")).errors["body"],
        )
        assertEquals(
            FieldIssue.Code.INVALID_STATUS_CODE,
            MonitorValidator
                .validate(
                    spec,
                    ok + ("accepted_statuscodes" to listOf("2xx")),
                ).errors["accepted_statuscodes"]
                ?.code,
        )
        assertEquals(
            FieldIssue.Code.TOO_SMALL,
            MonitorValidator.validate(spec, ok + ("retryInterval" to 0)).errors["retryInterval"]?.code,
        )
        assertEquals(
            FieldIssue.Code.NOT_A_NUMBER,
            MonitorValidator.validate(spec, ok + ("interval" to "abc")).errors["interval"]?.code,
        )
    }

    @Test
    fun `required fields depend on the type`() {
        val keyword = MonitorValidator.validate(spec, draft("keyword", "name" to "k", "url" to "https://a.b")).errors
        assertEquals(FieldIssue.Code.REQUIRED, keyword["keyword"]?.code)
        val docker = MonitorValidator.validate(spec, draft("docker", "name" to "d")).errors
        assertEquals(FieldIssue.Code.REQUIRED, docker["docker_host"]?.code)
        assertEquals(FieldIssue.Code.REQUIRED, docker["docker_container"]?.code)
        val group = MonitorValidator.validate(spec, draft("group"))
        assertEquals(FieldIssue.Code.REQUIRED, group.errors["name"]?.code)
        assertTrue(MonitorValidator.validate(spec, draft("group", "name" to "g")).isValid)
        assertTrue(MonitorValidator.validate(spec, draft("push", "name" to "p")).isValid)
        val ping = draft("ping", "hostname" to "example.com")
        assertTrue(MonitorValidator.validate(spec, ping).isValid, "name defaults to the hostname")
    }

    @Test
    fun `short interval is only a warning`() {
        val result =
            MonitorValidator.validate(
                spec,
                draft(
                    "http",
                    "name" to "x",
                    "url" to "https://a.b",
                    "interval" to 10L,
                    "timeout" to 8L,
                ),
            )
        assertTrue(result.isValid)
        assertTrue(result.shortIntervalWarning)
    }

    @Test
    fun `interval change drags retry interval and clamps timeout`() {
        var v = draft("http")
        v = MonitorDrafts.change(spec, v, "interval", 30L)
        assertEquals(30L, v["retryInterval"])
        assertEquals(24L, v["timeout"])
        v = MonitorDrafts.change(spec, v, "retryInterval", 45L)
        v = MonitorDrafts.change(spec, v, "interval", 20L)
        assertEquals(45L, v["retryInterval"], "decoupled once different")
        assertEquals(16L, v["timeout"])

        var ping = draft("ping")
        ping = MonitorDrafts.change(spec, ping, "interval", 5L)
        assertEquals(10L, ping["timeout"], "ping timeout is not clamped")
    }

    @Test
    fun `type change applies the web UI defaults`() {
        var v = draft("http", "conditions" to listOf(mapOf("x" to 1)))
        v = MonitorDrafts.change(spec, v, "type", "dns")
        assertEquals(53L, v["port"])
        assertEquals(emptyList<Any?>(), v["conditions"])
        v = MonitorDrafts.change(spec, v, "type", "snmp")
        assertEquals(161L, v["port"])
        assertEquals(5L, v["timeout"])
        v = MonitorDrafts.change(spec, v, "type", "push")
        assertTrue((v["pushToken"] as String).length == MonitorDrafts.PUSH_TOKEN_LENGTH)
        v = MonitorDrafts.change(spec, v, "type", "postgres")
        assertEquals(MonitorDrafts.CONNECTION_TEMPLATES["postgres"], v["databaseConnectionString"])
        v = MonitorDrafts.change(spec, v, "type", "mysql")
        assertEquals(MonitorDrafts.CONNECTION_TEMPLATES["mysql"], v["databaseConnectionString"])
        v = MonitorDrafts.change(spec, v, "ignoreTls", true)
        assertEquals(false, v["expiryNotification"])
    }

    @Test
    fun `push url and token`() {
        val token = MonitorDrafts.newPushToken()
        assertTrue(token.matches(Regex("[A-Za-z0-9]{32}")))
        assertEquals(
            "https://kuma.example/api/push/abc?status=up&msg=OK&ping=",
            MonitorDrafts.pushUrl("https://kuma.example/", "abc"),
        )
    }

    @Test
    fun `tag diff adds, deletes and edits`() {
        val env = MonitorTag(1, "env", "#000", "dev")
        val team = MonitorTag(2, "team", "#111", "android")
        val diff = TagDiff.of(listOf(env, team), listOf(env.copy(value = "prod"), MonitorTag(3, "new", "#222")))
        assertEquals(listOf(env to "prod"), diff.toEdit)
        assertEquals(listOf(team), diff.toDelete)
        assertEquals(listOf(3L), diff.toAdd.map { it.tagId })
        assertTrue(TagDiff.of(listOf(env), listOf(env)).isEmpty)
        val multi = TagDiff.of(listOf(env, env.copy(value = "x")), listOf(env.copy(value = "y")))
        assertEquals(2, multi.toDelete.size)
        assertEquals(1, multi.toAdd.size)
    }
}
