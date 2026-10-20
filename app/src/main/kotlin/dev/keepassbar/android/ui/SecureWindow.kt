package dev.keepassbar.android.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import java.io.File

/**
 * Every window that can show a secret: no screenshots, blank in recents (SPEC §4). Debug builds only: a
 * marker file lets the emulator be checked with screenshots
 * (`adb shell run-as dev.keepassbar.android touch files/allow-screenshots`). A release build ignores it.
 */
internal fun Activity.secureWindow() {
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (debuggable && File(filesDir, "allow-screenshots").exists()) return
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
