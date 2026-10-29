package net.kirkanos.uptimekuma.push.firebase

import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.push.firebase.GoogleServicesParser.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GoogleServicesParserTest {
    /** Shape of a real google-services.json with two Android apps (release and debug package). */
    private fun json(
        projectInfo: String = PROJECT_INFO,
        clients: String = "${client(
            RELEASE,
            "1:123456789012:android:aaaa1111",
        )},${client(DEBUG, "1:123456789012:android:bbbb2222")}",
    ) = """
        {
          "project_info": $projectInfo,
          "client": [$clients],
          "configuration_version": "1"
        }
        """.trimIndent()

    private fun client(
        packageName: String,
        appId: String,
        apiKeys: String = """[{"current_key": "$API_KEY"}]""",
    ) = """
        {
          "client_info": {
            "mobilesdk_app_id": "$appId",
            "android_client_info": { "package_name": "$packageName" }
          },
          "oauth_client": [],
          "api_key": $apiKeys,
          "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
        }
        """.trimIndent()

    @Test
    fun `picks the client of the release package`() {
        assertEquals(
            Result.Success(
                FirebaseConfig("kuma-push-1a2b3", "1:123456789012:android:aaaa1111", API_KEY, "123456789012"),
            ),
            GoogleServicesParser.parse(json(), RELEASE),
        )
    }

    @Test
    fun `picks the client of the debug package`() {
        val result = GoogleServicesParser.parse(json(), DEBUG) as Result.Success
        assertEquals("1:123456789012:android:bbbb2222", result.config.applicationId)
    }

    @Test
    fun `no client for this package lists the packages found`() {
        val onlyDebug = json(clients = client(DEBUG, "1:123456789012:android:bbbb2222"))
        assertEquals(Result.NoMatchingClient(RELEASE, listOf(DEBUG)), GoogleServicesParser.parse(onlyDebug, RELEASE))
    }

    @Test
    fun `project number as JSON number is accepted`() {
        val numeric = json(projectInfo = """{"project_number": 123456789012, "project_id": "kuma-push-1a2b3"}""")
        assertEquals(
            "123456789012",
            (GoogleServicesParser.parse(numeric, RELEASE) as Result.Success).config.gcmSenderId,
        )
    }

    @Test
    fun `first non-empty api key is used`() {
        val keys =
            json(clients = client(RELEASE, "1:1:android:aa", """[{"current_key": ""}, {"current_key": "$API_KEY"}]"""))
        assertEquals(API_KEY, (GoogleServicesParser.parse(keys, RELEASE) as Result.Success).config.apiKey)
    }

    @Test
    fun `missing fields are named`() {
        assertEquals(Result.Invalid("project_info"), GoogleServicesParser.parse("""{"client": []}""", RELEASE))
        assertEquals(
            Result.Invalid("project_info.project_id"),
            GoogleServicesParser.parse(json(projectInfo = """{"project_number": "1"}"""), RELEASE),
        )
        assertEquals(
            Result.Invalid("project_info.project_number"),
            GoogleServicesParser.parse(json(projectInfo = """{"project_id": "p"}"""), RELEASE),
        )
        assertEquals(Result.Invalid("client"), GoogleServicesParser.parse(json(clients = ""), RELEASE))
        assertEquals(
            Result.Invalid("api_key.current_key"),
            GoogleServicesParser.parse(json(clients = client(RELEASE, "1:1:android:aa", "[]")), RELEASE),
        )
        assertEquals(
            Result.Invalid("client_info.mobilesdk_app_id"),
            GoogleServicesParser.parse(json(clients = client(RELEASE, " ")), RELEASE),
        )
    }

    @Test
    fun `not JSON or not an object is invalid`() {
        assertEquals(Result.Invalid("{}"), GoogleServicesParser.parse("not json", RELEASE))
        assertEquals(Result.Invalid("{}"), GoogleServicesParser.parse("[1, 2]", RELEASE))
        assertEquals(Result.Invalid("{}"), GoogleServicesParser.parse("", RELEASE))
    }

    private companion object {
        const val RELEASE = "net.kirkanos.uptimekuma"
        const val DEBUG = "net.kirkanos.uptimekuma.debug"
        const val API_KEY = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
        const val PROJECT_INFO =
            """{"project_number": "123456789012", "project_id": "kuma-push-1a2b3", "storage_bucket": "x.appspot.com"}"""
    }
}
