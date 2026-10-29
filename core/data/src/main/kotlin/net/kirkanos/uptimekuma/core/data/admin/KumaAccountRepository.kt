package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kirkanos.uptimekuma.core.domain.AccountRepository
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.TwoFactorSetup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AccountRepository] over the 2FA/account events (`docs/kuma-api/socket-events.md` 3.2).
 *
 * `changePassword` invalidates every JWT of the user (claim `h`) and answers with a fresh one. Kuma sends `refresh`
 * only to the user's *other* sockets, so the app's live socket stays logged in; the new token is stored right away
 * so the next `loginByToken` (reconnect, background sync) succeeds.
 */
@Singleton
internal class KumaAccountRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : AccountRepository {
        override suspend fun changePassword(
            currentPassword: String,
            newPassword: String,
        ) {
            val payload =
                buildJsonObject {
                    put("currentPassword", currentPassword)
                    put("newPassword", newPassword)
                }
            val token = backend.call("changePassword", payload)["token"].asStringOrNull()
            if (token.isNullOrBlank()) {
                throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "changePassword without token"))
            }
            backend.storeSessionToken(token)
        }

        override suspend fun twoFactorEnabled(): Boolean =
            backend.call("twoFAStatus")["status"].asLenientBoolean() == true

        override suspend fun prepareTwoFactor(currentPassword: String): TwoFactorSetup {
            val uri =
                backend.call("prepare2FA", currentPassword)["uri"].asStringOrNull()
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "prepare2FA without uri"))
            return TwoFactorSetup(uri)
        }

        override suspend fun verifyTwoFactorToken(
            token: String,
            currentPassword: String,
        ): Boolean =
            try {
                backend.call("verifyToken", token.trim(), currentPassword)["valid"].asLenientBoolean() == true
            } catch (e: KumaException) {
                // {ok:false, msg:"authInvalidToken", valid:false}: a wrong code, not an error.
                if (e.error.message == INVALID_TOKEN) false else throw e
            }

        override suspend fun enableTwoFactor(currentPassword: String) {
            backend.call("save2FA", currentPassword)
        }

        override suspend fun disableTwoFactor(currentPassword: String) {
            backend.call("disable2FA", currentPassword)
        }

        private companion object {
            const val INVALID_TOKEN = "authInvalidToken"
        }
    }
