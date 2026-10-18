package dev.keepassbar.android

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner

class KeePassBarApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The app's whole process going to the background and back, not one screen (SPEC §4).
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.sessions)
    }
}
