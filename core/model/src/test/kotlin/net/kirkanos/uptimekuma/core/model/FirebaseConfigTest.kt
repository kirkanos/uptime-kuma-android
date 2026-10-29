package net.kirkanos.uptimekuma.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FirebaseConfigTest {
    private val valid =
        FirebaseConfig(
            projectId = "kuma-push-1a2b3",
            applicationId = "1:123456789012:android:0123456789abcdef",
            apiKey = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q",
            gcmSenderId = "123456789012",
        )

    @Test
    fun `values as found in a google-services json are valid`() {
        assertEquals(39, valid.apiKey.length)
        assertTrue(valid.invalidFields().isEmpty())
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        val padded =
            FirebaseConfig(
                " ${valid.projectId} ",
                "${valid.applicationId}\n",
                " ${valid.apiKey}",
                "${valid.gcmSenderId} ",
            )
        assertTrue(padded.invalidFields().isEmpty())
        assertEquals(valid, padded.trimmed())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "Kuma-Push", "kuma", "1kuma-push", "kuma-push-", "kuma_push_123",
            "a-very-long-project-id-over-30-chars",
        ],
    )
    fun `invalid project ids`(projectId: String) {
        assertEquals(setOf(FirebaseConfigField.PROJECT_ID), valid.copy(projectId = projectId).invalidFields())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "AIza", "BIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q", "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q1",
            "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6!",
        ],
    )
    fun `invalid api keys`(apiKey: String) {
        assertEquals(setOf(FirebaseConfigField.API_KEY), valid.copy(apiKey = apiKey).invalidFields())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "123456789012", "1:123456789012:ios:0123abcd", "2:123456789012:android:0123abcd",
            "1:123456789012:android:XYZ",
        ],
    )
    fun `invalid app ids`(appId: String) {
        assertEquals(setOf(FirebaseConfigField.APPLICATION_ID), valid.copy(applicationId = appId).invalidFields())
    }

    @Test
    fun `app id of another project is rejected`() {
        val mixed = valid.copy(gcmSenderId = "999999999999")
        assertEquals(setOf(FirebaseConfigField.APPLICATION_ID), mixed.invalidFields())
    }

    @Test
    fun `non numeric sender id is reported once, without blaming the app id`() {
        assertEquals(setOf(FirebaseConfigField.SENDER_ID), valid.copy(gcmSenderId = "12a").invalidFields())
    }

    @Test
    fun `everything empty reports every field`() {
        assertEquals(FirebaseConfigField.entries.toSet(), FirebaseConfig("", "", "", "").invalidFields())
    }
}
