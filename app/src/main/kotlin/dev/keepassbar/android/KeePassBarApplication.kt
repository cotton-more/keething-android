package dev.keepassbar.android

import android.app.Application

class KeePassBarApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
