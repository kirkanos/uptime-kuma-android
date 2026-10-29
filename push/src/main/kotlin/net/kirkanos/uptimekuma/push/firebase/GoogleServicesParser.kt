package net.kirkanos.uptimekuma.push.firebase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.kirkanos.uptimekuma.core.model.FirebaseConfig

/**
 * Reads the four values the app needs from a `google-services.json` downloaded from the Firebase console:
 *
 * ```
 * project_info.project_id                      -> projectId
 * project_info.project_number                  -> gcmSenderId
 * client[i].client_info.mobilesdk_app_id       -> applicationId   (client whose package_name == ours)
 * client[i].api_key[0].current_key             -> apiKey
 * ```
 *
 * The file may list several Android apps of the project; only the one registered for this app's package
 * (`net.kirkanos.uptimekuma`, or `net.kirkanos.uptimekuma.debug` for debug builds) is usable, because FCM
 * tokens are bound to the package name.
 */
object GoogleServicesParser {
    sealed interface Result {
        data class Success(
            val config: FirebaseConfig,
        ) : Result

        /** No client entry for [packageName]; [found] lists the package names the file does contain. */
        data class NoMatchingClient(
            val packageName: String,
            val found: List<String>,
        ) : Result

        /** Not JSON, not a google-services.json, or [missing] (a JSON path) is absent or empty. */
        data class Invalid(
            val missing: String,
        ) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Thrown internally to bail out with a [Result]; never escapes [parse]. */
    private class Stop(
        val result: Result,
    ) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun parse(
        text: String,
        packageName: String,
    ): Result =
        try {
            Result.Success(read(text, packageName))
        } catch (stop: Stop) {
            stop.result
        }

    private fun read(
        text: String,
        packageName: String,
    ): FirebaseConfig {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: missing("{}")
        val projectInfo = root["project_info"] as? JsonObject ?: missing("project_info")
        val projectId = projectInfo.string("project_id") ?: missing("project_info.project_id")
        val senderId = projectInfo.string("project_number") ?: missing("project_info.project_number")
        val clients = (root["client"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (clients.isEmpty()) missing("client")

        val client =
            clients.firstOrNull { it.packageName() == packageName }
                ?: throw Stop(Result.NoMatchingClient(packageName, clients.mapNotNull { it.packageName() }))
        val appId =
            (client["client_info"] as? JsonObject)?.string("mobilesdk_app_id")
                ?: missing("client_info.mobilesdk_app_id")
        val apiKey =
            (client["api_key"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstNotNullOfOrNull { it.string("current_key") }
                ?: missing("api_key.current_key")
        return FirebaseConfig(projectId = projectId, applicationId = appId, apiKey = apiKey, gcmSenderId = senderId)
    }

    private fun missing(path: String): Nothing = throw Stop(Result.Invalid(path))

    private fun JsonObject.packageName(): String? =
        ((this["client_info"] as? JsonObject)?.get("android_client_info") as? JsonObject)?.string("package_name")

    /** String or number value, trimmed; `null` if absent or blank. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
