package net.kirkanos.uptimekuma.core.data.connection

import android.util.Log
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.KumaClient
import javax.inject.Inject

/** Creates the socket client for a base URL; replaced by a fake in tests. */
internal fun interface KumaApiFactory {
    fun create(baseUrl: String): KumaApi
}

internal class SocketKumaApiFactory
    @Inject
    constructor() : KumaApiFactory {
        override fun create(baseUrl: String): KumaApi =
            KumaClient(baseUrl, logger = { message, error -> DataLog.w(message, error) })
    }

/** Thin wrapper so JVM unit tests (android.jar stubs return defaults) never crash on logging. */
internal object DataLog {
    private const val TAG = "KumaData"

    fun w(
        message: String,
        error: Throwable? = null,
    ) {
        Log.w(TAG, message, error)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }
}

/** Timing knobs of [KumaConnectionManager]; shortened in tests. */
internal data class ConnectionTimings(
    /** Socket stays open this long after the app went to background. */
    val backgroundGraceMillis: Long = 30_000,
    /** Wait for `loginRequired`/`autoLogin` and for authentication of a (re)opened socket. */
    val readyTimeoutMillis: Long = 20_000,
    /** Wait for the post-login `info` (version gate) during interactive login. */
    val infoTimeoutMillis: Long = 5_000,
    val logoutTimeoutMillis: Long = 3_000,
    /** After the sync burst is complete, let trailing stats events land before disconnecting. */
    val syncDrainMillis: Long = 300,
    /** Retry delay for `loginByToken` failures that are not token rejections (e.g. timeout). */
    val tokenRetryMillis: Long = 5_000,
)
