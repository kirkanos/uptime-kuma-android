package net.kirkanos.uptimekuma.feature.onboarding

import net.kirkanos.uptimekuma.feature.onboarding.ServerUrl.Reason
import net.kirkanos.uptimekuma.feature.onboarding.ServerUrl.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ServerUrlTest {
    @ParameterizedTest
    @CsvSource(
        "uptime.example.org, https://uptime.example.org, false",
        "'  https://Uptime.Example.org/  ', https://uptime.example.org, false",
        "http://192.168.1.10:3001, http://192.168.1.10:3001, true",
        "https://example.org/kuma/, https://example.org/kuma, false",
        "https://example.org/dashboard/12, https://example.org, false",
        "HTTPS://example.org:8443/status/kuma, https://example.org:8443/status/kuma, false",
    )
    fun `valid addresses are normalised`(
        input: String,
        expected: String,
        insecure: Boolean,
    ) {
        assertEquals(Result.Valid(expected, insecure), ServerUrl.normalize(input))
    }

    @ParameterizedTest
    @CsvSource(
        "'', EMPTY",
        "'   ', EMPTY",
        "ftp://example.org, UNSUPPORTED_SCHEME",
        "https://, INVALID",
        "https://exa mple.org, INVALID",
        "https://example.org/?a=1, INVALID",
        "https://user:pw@example.org, INVALID",
    )
    fun `invalid addresses are rejected`(
        input: String,
        reason: Reason,
    ) {
        assertEquals(Result.Invalid(reason), ServerUrl.normalize(input))
    }
}
