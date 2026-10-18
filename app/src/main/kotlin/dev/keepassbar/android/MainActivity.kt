package dev.keepassbar.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.keepassbar.android.navigation.KeePassBarApp
import dev.keepassbar.android.theme.KeePassBarTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeePassBarApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every window that can show a secret: no screenshots, blank in recents (SPEC §4).
        if (!screenshotsAllowedForTesting()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            KeePassBarTheme {
                KeePassBarApp(container)
            }
        }
    }

    /**
     * Debug builds only: a marker file lets the emulator be checked with screenshots
     * (`adb shell run-as dev.keepassbar.android touch files/allow-screenshots`). A release build ignores it.
     */
    private fun screenshotsAllowedForTesting(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && File(filesDir, "allow-screenshots").exists()
}
