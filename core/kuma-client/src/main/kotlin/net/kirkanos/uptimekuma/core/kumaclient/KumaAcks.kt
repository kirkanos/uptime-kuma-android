package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException

/** Interpretation of Kuma's `{ok, msg, msgi18n}` acknowledgements. */
object KumaAcks {
    private val invalidCredentialKeys = setOf("authIncorrectCreds", "authInvalidToken", "authUserInactiveOrDeleted")
    private const val RATE_LIMIT_MARKER = "Too frequently"
    private const val NOT_LOGGED_IN = "You are not logged in."

    /**
     * Returns the ack object when `ok == true`, otherwise throws [KumaException] with the mapped error.
     * An ack that is not an object (or has no `ok`) is rejected as [KumaError.Kind.UNKNOWN].
     */
    fun requireOk(
        event: String,
        ack: JsonElement?,
    ): JsonObject {
        val obj = ack as? JsonObject
        val ok = obj?.get("ok").asLenientBoolean()
        if (obj != null && ok == true) return obj
        throw KumaException(
            if (obj != null && ok == false) {
                errorOf(obj)
            } else {
                KumaError(KumaError.Kind.UNKNOWN, "Unexpected ack for '$event': $ack")
            },
        )
    }

    /**
     * Interprets a `login` ack: `{tokenRequired:true}` (no `ok`!) -> [LoginResponse.TwoFactorRequired],
     * `{ok:true, token}` -> [LoginResponse.Success], everything else throws.
     */
    fun loginResponse(ack: JsonElement?): LoginResponse {
        if ((ack as? JsonObject)?.get("tokenRequired").asLenientBoolean() == true) {
            return LoginResponse.TwoFactorRequired
        }
        val token =
            requireOk("login", ack)["token"].asStringOrNull()
                ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "Login ack without token"))
        return LoginResponse.Success(token)
    }

    /** Maps an `ok:false` ack to a [KumaError]. */
    fun errorOf(ack: JsonObject): KumaError {
        val message = messageOf(ack)
        val kind =
            when {
                message == null -> KumaError.Kind.SERVER_REJECTED
                message in invalidCredentialKeys -> KumaError.Kind.INVALID_CREDENTIALS
                message.contains(RATE_LIMIT_MARKER, ignoreCase = true) -> KumaError.Kind.RATE_LIMITED
                message == NOT_LOGGED_IN -> KumaError.Kind.UNAUTHORIZED
                else -> KumaError.Kind.SERVER_REJECTED
            }
        return KumaError(kind, message)
    }

    /** `msg` as string; for the `{key, values}` object form the i18n key is returned. */
    fun messageOf(ack: JsonObject): String? =
        when (val msg = ack["msg"]) {
            is JsonPrimitive -> msg.asStringOrNull()
            is JsonObject -> msg["key"].asStringOrNull()
            else -> null
        }

    /** True when `msg` is an i18n key rather than display text. */
    fun isI18n(ack: JsonObject): Boolean = ack["msgi18n"].asLenientBoolean() == true
}
