package net.kirkanos.uptimekuma

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.push.PushInitializer
import net.kirkanos.uptimekuma.widget.WidgetInitializer
import javax.inject.Inject

@HiltAndroidApp
class KumaApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var pushInitializer: PushInitializer

    @Inject
    lateinit var widgetInitializer: WidgetInitializer

    // :push removes the default WorkManager initializer, so WorkManager is configured here with Hilt workers.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Notification channels and re-registration with the saved UnifiedPush distributor.
        pushInitializer.initialize()
        // Redraws widget and Quick Settings tile when monitor states change (no polling).
        widgetInitializer.start()
        // Keep the live socket only while any activity is visible (ON_START / ON_STOP of the whole process).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = sessionRepository.setForeground(true)

                override fun onStop(owner: LifecycleOwner) = sessionRepository.setForeground(false)
            },
        )
    }
}
