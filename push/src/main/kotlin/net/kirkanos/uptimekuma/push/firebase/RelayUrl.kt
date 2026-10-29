package net.kirkanos.uptimekuma.push.firebase

import java.net.URI
import java.net.URISyntaxException

/** Validation of the FCM relay base URL and the webhook URL Kuma posts to. */
object RelayUrl {
    /** Path prefix of the relay's FCM endpoint (relay spec v1). */
    const val FCM_PATH: String = "/v1/fcm/"

    enum class Check {
        /** `https://` with a host. */
        OK,

        /** `http://` with a host: works, but the relay token and alerts travel unencrypted. */
        INSECURE,

        /** Empty, unparseable, other scheme, no host, or with query / fragment. */
        INVALID,
    }

    fun check(input: String): Check {
        val uri = parse(input) ?: return Check.INVALID
        return if (uri.scheme.equals("https", ignoreCase = true)) Check.OK else Check.INSECURE
    }

    /** Trimmed URL without trailing slashes, or `null` if [check] is [Check.INVALID]. */
    fun normalize(input: String): String? = parse(input)?.let { input.trim().trimEnd('/') }

    /** The URL Kuma's webhook posts to: `<relay>/v1/fcm/<FCM token>`. */
    fun webhookUrl(
        relayBase: String,
        fcmToken: String,
    ): String = relayBase.trim().trimEnd('/') + FCM_PATH + fcmToken

    private fun parse(input: String): URI? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val uri =
            try {
                URI(text)
            } catch (_: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase()
        val valid =
            (scheme == "https" || scheme == "http") &&
                !uri.host.isNullOrBlank() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawUserInfo == null
        return uri.takeIf { valid }
    }
}
