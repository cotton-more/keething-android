package dev.keething.android

import android.app.ActivityManager
import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.ProcessLifecycleOwner
import dev.keething.argon2.NativeArgon2
import dev.keething.kdbx.crypto.Argon2Engine

class KeethingApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Smart selection hands a selected text to the system's text classifier (another process) on a long press and
        // when the menu opens; a shown protected note is selectable (SPEC §6.4). Compose has no switch per container.
        ComposeFoundationFlags.isSmartSelectionEnabled = false
        // The native Argon2 where it loads, its memory the device's (keething-spec unlock.faster-argon2).
        val activities = getSystemService(ActivityManager::class.java)
        Argon2Engine.native = NativeArgon2.load { ActivityManager.MemoryInfo().also(activities::getMemoryInfo).availMem }
        container = AppContainer(this)
        // The app's whole process going to the background and back, not one screen (SPEC §4).
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.sessions)
    }
}
