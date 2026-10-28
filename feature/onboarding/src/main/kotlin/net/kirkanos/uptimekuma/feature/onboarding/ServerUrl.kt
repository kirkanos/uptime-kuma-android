package net.kirkanos.uptimekuma.feature.onboarding

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Validation and normalisation of the server address typed by the user. */
object ServerUrl {
    sealed interface Result {
        /** [url] has no trailing slash; [insecure] is true for plain `http`. */
        data class Valid(
            val url: String,
            val insecure: Boolean,
        ) : Result

        data class Invalid(
            val reason: Reason,
        ) : Result
    }

    enum class Reason { EMPTY, INVALID, UNSUPPORTED_SCHEME }

    private val dashboardSuffix = Regex("/dashboard(/.*)?$")

    /**
     * Adds `https://` when no scheme is given, lowercases scheme and host, strips trailing slashes and a
     * pasted `/dashboard...` suffix, and keeps sub-paths (reverse-proxy installs). Query and fragment are
     * rejected.
     */
    fun normalize(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri =
            try {
                URI(withScheme)
            } catch (_: URISyntaxException) {
                return Result.Invalid(Reason.INVALID)
            }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        return when {
            scheme != "http" && scheme != "https" -> {
                Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            }

            host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null -> {
                Result.Invalid(Reason.INVALID)
            }

            else -> {
                val port = if (uri.port == -1) "" else ":${uri.port}"
                val path =
                    uri.rawPath
                        .orEmpty()
                        .replace(dashboardSuffix, "")
                        .trimEnd('/')
                Result.Valid(url = "$scheme://$host$port$path", insecure = scheme == "http")
            }
        }
    }
}
