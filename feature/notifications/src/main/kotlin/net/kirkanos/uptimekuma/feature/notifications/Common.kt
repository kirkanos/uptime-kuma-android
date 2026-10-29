package net.kirkanos.uptimekuma.feature.notifications

import kotlinx.coroutines.CancellationException
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException

/** Runs [block]; returns the error instead of throwing (cancellation still propagates). */
internal suspend fun runCatchingKuma(block: suspend () -> Unit): KumaError? =
    try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: KumaException) {
        e.error
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        KumaError(KumaError.Kind.UNKNOWN, e.message)
    }
