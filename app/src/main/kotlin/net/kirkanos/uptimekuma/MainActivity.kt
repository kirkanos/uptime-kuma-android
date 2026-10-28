package net.kirkanos.uptimekuma

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.push.NotificationIntents
import net.kirkanos.uptimekuma.ui.AppViewModel
import net.kirkanos.uptimekuma.ui.KumaApp
import net.kirkanos.uptimekuma.ui.SessionGate

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { appViewModel.gate.value == SessionGate.Loading }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            KumaTheme {
                KumaApp(appViewModel = appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val id = intent?.getLongExtra(NotificationIntents.EXTRA_MONITOR_ID, NO_MONITOR) ?: NO_MONITOR
        if (id != NO_MONITOR) {
            appViewModel.openMonitor(id)
            intent?.removeExtra(NotificationIntents.EXTRA_MONITOR_ID)
        }
    }

    companion object {
        private const val NO_MONITOR = -1L
    }
}
