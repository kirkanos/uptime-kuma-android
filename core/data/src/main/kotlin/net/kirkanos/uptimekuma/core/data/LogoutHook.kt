package net.kirkanos.uptimekuma.core.data

/**
 * Called by `SessionRepository.logout()` *before* the token and cache are cleared, while the
 * server connection is still usable (e.g. the push module deletes its Kuma notification and
 * unregisters from UnifiedPush). Contribute with Hilt:
 *
 * ```
 * @Binds @IntoSet abstract fun bindPushLogoutHook(impl: PushLogoutHook): LogoutHook
 * ```
 *
 * Failures are logged and ignored; logout always completes.
 */
fun interface LogoutHook {
    suspend fun onLogout()
}
