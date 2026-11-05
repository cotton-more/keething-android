package dev.keething.android.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import java.io.File

/**
 * Every window that can show a secret: no screenshots, blank in recents (SPEC §4), and its views left out of what
 * autofill services are shown. Debug builds only: a marker file lets the emulator be checked with screenshots
 * (`adb shell run-as dev.keething.android touch files/allow-screenshots`). A release build ignores it.
 */
internal fun Activity.secureWindow() {
    window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (debuggable && File(filesDir, "allow-screenshots").exists()) return
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/**
 * A text field no autofill service (Keething's or another, such as Google's) is asked to fill or save from: the
 * master password, an entry's password, a one-time code's secret (keething-spec autofill.own-screens). Every text
 * field in the app carries it: Compose tells autofill about its fields itself, whatever the window says, and leaves
 * out only a field whose data type is None.
 */
internal fun Modifier.keepOutOfAutofill(): Modifier = semantics { contentDataType = ContentDataType.None }
