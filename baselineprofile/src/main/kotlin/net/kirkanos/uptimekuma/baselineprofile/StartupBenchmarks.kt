package net.kirkanos.uptimekuma.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.awaitDashboard
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.scrollDashboard
import net.kirkanos.uptimekuma.baselineprofile.KumaJourneys.startToDashboard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the dashboard and dashboard scrolling, without and with the baseline profile.
 * Run manually on a real device: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`
 * (arguments as for the generator, see docs/performance.md). Compare `timeToInitialDisplayMs` and
 * `frameDurationCpuMs` between the `None` and `BaselineProfile` variants.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun scrollBaselineProfile() =
        rule.measureRepeated(
            packageName = KumaJourneys.packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = { startToDashboard() },
        ) {
            awaitDashboard()
            scrollDashboard()
        }

    private fun startup(compilationMode: CompilationMode) =
        rule.measureRepeated(
            packageName = KumaJourneys.packageName,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startToDashboard()
        }

    private companion object {
        const val ITERATIONS = 10
    }
}
