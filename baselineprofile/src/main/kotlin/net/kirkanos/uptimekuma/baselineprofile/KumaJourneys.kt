package net.kirkanos.uptimekuma.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * User journeys shared by the profile generator and the benchmarks. They drive the real app (release
 * build, real data layer), so a reachable Kuma server is needed; credentials come from instrumentation
 * arguments (`kumaUrl`, `kumaUser`, `kumaPassword`) and are only used when the app is not logged in.
 * Text matches cover the English and German UI.
 */
internal object KumaJourneys {
    /** Application id of the release build (no suffix). Override with the `targetAppId` argument. */
    val packageName: String
        get() = argument("targetAppId") ?: "net.kirkanos.uptimekuma"

    private const val UI_TIMEOUT = 15_000L
    private const val SYNC_TIMEOUT = 30_000L
    private const val SHORT_TIMEOUT = 5_000L
    private const val GESTURE_MARGIN_DIVISOR = 10

    private val continueButton = text("Continue|Weiter")
    private val loginButton = text("Log in|Anmelden")

    // The dashboard has a search field too, so detect onboarding by its heading, not by an EditText.
    private val onboardingTitle = text("Connect to Uptime Kuma|Mit Uptime Kuma verbinden")

    private fun text(regex: String): BySelector = By.text(Pattern.compile(regex, Pattern.CASE_INSENSITIVE))

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    /** Cold start to the dashboard; logs in first (then restarts) when the app shows the onboarding. */
    fun MacrobenchmarkScope.startToDashboard() {
        pressHome()
        startActivityAndWait()
        if (isOnboarding()) {
            login()
            // Fresh process so the measured/profiled start is the normal "already logged in" one.
            killProcess()
            pressHome()
            startActivityAndWait()
        }
        awaitDashboard()
    }

    private fun MacrobenchmarkScope.isOnboarding(): Boolean =
        device.wait(Until.hasObject(onboardingTitle), SHORT_TIMEOUT) == true

    private fun MacrobenchmarkScope.login() {
        val url =
            checkNotNull(argument("kumaUrl")) {
                "App is not logged in: pass -Pandroid.testInstrumentationRunnerArguments.kumaUrl=..."
            }
        val user = checkNotNull(argument("kumaUser")) { "kumaUser instrumentation argument missing" }
        val password = checkNotNull(argument("kumaPassword")) { "kumaPassword instrumentation argument missing" }

        device.findObject(By.clazz("android.widget.EditText")).text = url
        device.findObject(continueButton).click()
        check(device.wait(Until.hasObject(loginButton), UI_TIMEOUT) == true) { "Credentials step did not appear" }
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        fields[0].text = user
        fields[1].text = password
        // The step heading reads "Log in" as well; the button is the lowest match.
        device.findObjects(loginButton).maxBy { it.visibleBounds.top }.click()
        // The "done" step shows a Continue button once the login succeeded.
        check(device.wait(Until.hasObject(continueButton), SYNC_TIMEOUT) == true) { "Login did not succeed" }
    }

    /** Waits until the monitor list is shown and filled (first sync done). */
    fun MacrobenchmarkScope.awaitDashboard() {
        check(device.wait(Until.hasObject(By.scrollable(true)), SYNC_TIMEOUT) == true) { "Dashboard did not appear" }
        device.waitForIdle()
    }

    /** Flings through the monitor list and back; covers list item composition and heartbeat bars. */
    fun MacrobenchmarkScope.scrollDashboard() {
        val list = device.findObject(By.scrollable(true)) ?: return
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(2) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    /** Opens the first monitor's detail screen (chart, beats) and goes back. */
    fun MacrobenchmarkScope.openFirstMonitor() {
        val list = device.findObject(By.scrollable(true)) ?: return
        val firstRow = list.children.firstOrNull { it.isClickable } ?: list.findObject(By.clickable(true)) ?: return
        firstRow.click()
        device.wait(Until.gone(By.scrollable(true)), SHORT_TIMEOUT)
        device.waitForIdle()
        // Detail screens load chart data from the server; give it a moment so that code is profiled too.
        Thread.sleep(DETAIL_SETTLE_MILLIS)
        device.pressBack()
        awaitDashboard()
    }

    private const val DETAIL_SETTLE_MILLIS = 2_000L
}
