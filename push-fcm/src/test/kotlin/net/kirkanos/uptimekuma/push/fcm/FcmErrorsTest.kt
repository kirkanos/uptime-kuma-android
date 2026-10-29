package net.kirkanos.uptimekuma.push.fcm

import com.google.android.gms.common.ConnectionResult
import net.kirkanos.uptimekuma.push.data.PushSetupException
import net.kirkanos.uptimekuma.push.data.PushSetupException.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.util.concurrent.ExecutionException

class FcmErrorsTest {
    /** How FCM hands token failures to `getToken()` listeners: IOException(code) wrapped in ExecutionException. */
    private fun wrapped(code: String) = ExecutionException(IOException(code))

    @ParameterizedTest
    @CsvSource(
        "SERVICE_NOT_AVAILABLE, FCM_UNAVAILABLE",
        "INTERNAL_SERVER_ERROR, FCM_UNAVAILABLE",
        "TIMEOUT, FCM_UNAVAILABLE",
        "AUTHENTICATION_FAILED, FCM_CONFIG_REJECTED",
        "FIS_AUTH_ERROR, FCM_CONFIG_REJECTED",
        "INVALID_SENDER, FCM_CONFIG_REJECTED",
        "MISSING_INSTANCEID_SERVICE, PLAY_SERVICES_MISSING",
        "PHONE_REGISTRATION_ERROR, FCM_FAILED",
    )
    fun `GMS error codes`(
        code: String,
        reason: Reason,
    ) {
        val e = FcmErrors.toSetupException(wrapped(code))
        assertEquals(reason, e.reason)
        assertEquals(code, e.message)
    }

    @Test
    fun `Firebase Installations rejections count as bad config`() {
        val badKey =
            IllegalArgumentException(
                "Please set a valid API key. A Firebase API key is required to communicate with Firebase server APIs",
            )
        assertEquals(Reason.FCM_CONFIG_REJECTED, FcmErrors.toSetupException(RuntimeException(badKey)).reason)
        val unavailable = IOException("Firebase Installations Service is unavailable. Please try again later.")
        assertEquals(Reason.FCM_UNAVAILABLE, FcmErrors.toSetupException(ExecutionException(unavailable)).reason)
    }

    @Test
    fun `network failures are unavailable`() {
        val e = FcmErrors.toSetupException(ExecutionException(java.net.UnknownHostException("fcm.googleapis.com")))
        assertEquals(Reason.FCM_UNAVAILABLE, e.reason)
    }

    @Test
    fun `setup exceptions pass through unchanged`() {
        val original = PushSetupException(Reason.FCM_TOKEN_TIMEOUT)
        assertSame(original, FcmErrors.toSetupException(original))
    }

    @Test
    fun `exception without message is named by its type`() {
        assertEquals("IllegalStateException", FcmErrors.toSetupException(IllegalStateException()).message)
    }

    @Test
    fun `Play services availability`() {
        assertNull(FcmErrors.playServicesReason(ConnectionResult.SUCCESS))
        assertEquals(
            Reason.PLAY_SERVICES_UPDATE_REQUIRED,
            FcmErrors.playServicesReason(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED),
        )
        assertEquals(
            Reason.PLAY_SERVICES_UPDATE_REQUIRED,
            FcmErrors.playServicesReason(ConnectionResult.SERVICE_DISABLED),
        )
        assertEquals(
            Reason.PLAY_SERVICES_UPDATE_REQUIRED,
            FcmErrors.playServicesReason(ConnectionResult.SERVICE_UPDATING),
        )
        assertEquals(Reason.PLAY_SERVICES_MISSING, FcmErrors.playServicesReason(ConnectionResult.SERVICE_MISSING))
        assertEquals(Reason.PLAY_SERVICES_MISSING, FcmErrors.playServicesReason(ConnectionResult.SERVICE_INVALID))
    }
}
