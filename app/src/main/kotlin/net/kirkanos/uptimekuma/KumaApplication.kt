package net.kirkanos.uptimekuma

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import javax.inject.Inject

@HiltAndroidApp
class KumaApplication : Application() {
    @Inject
    lateinit var sessionRepository: SessionRepository

    override fun onCreate() {
        super.onCreate()
        // Keep the live socket only while any activity is visible (ON_START / ON_STOP of the whole process).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = sessionRepository.setForeground(true)

                override fun onStop(owner: LifecycleOwner) = sessionRepository.setForeground(false)
            },
        )
    }
}
