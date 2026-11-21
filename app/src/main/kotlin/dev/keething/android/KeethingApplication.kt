package dev.keething.android

import android.app.ActivityManager
import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.ProcessLifecycleOwner
import dev.keething.android.databases.DatabaseText
import dev.keething.android.session.ScreenOffLock
import dev.keething.argon2.NativeArgon2
import dev.keething.kdbx.crypto.Argon2Engine

class KeethingApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        DatabaseText.internalStorage = getString(R.string.databases_internal_storage)
        // Smart selection hands a selected text to the system's text classifier (another process) on a long press and
        // when the menu opens; a shown protected note is selectable (SPEC §6.4). Compose has no switch per container.
        ComposeFoundationFlags.isSmartSelectionEnabled = false
        // The native Argon2 where it loads, its memory the device's (keething-spec unlock.faster-argon2).
        val activities = getSystemService(ActivityManager::class.java)
        Argon2Engine.native = NativeArgon2.load { ActivityManager.MemoryInfo().also(activities::getMemoryInfo).availMem }
        container = AppContainer(this)
        // The theme chosen in Settings, told again in case the system forgot it (keething-spec app.theme-choice).
        getSystemService(UiModeManager::class.java).setApplicationNightMode(container.settings.theme.value.nightMode)
        // The app's whole process going to the background and back, not one screen (SPEC §4).
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.sessions)
        // The screen going off on a phone with a screen lock locks every database (keething-spec security.auto-lock AC4).
        ScreenOffLock(this, container.sessions).register()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DatabaseText.internalStorage = getString(R.string.databases_internal_storage)
    }
}
