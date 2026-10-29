package net.kirkanos.uptimekuma.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.openFirstMonitor
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.scrollDashboard
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.startToDashboard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline + startup profile for :app (cold start -> dashboard -> scroll -> detail).
 *
 * `./gradlew :app:generateReleaseBaselineProfile -PsignReleaseWithDebugKey=true` with a device attached
 * and the instrumentation arguments described in docs/performance.md. The result is written to
 * `app/src/release/generated/baselineProfiles/` and must be committed.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = KumaJourneys.packageName, includeInStartupProfile = true) {
            startToDashboard()
            scrollDashboard()
            openFirstMonitor()
        }
}
