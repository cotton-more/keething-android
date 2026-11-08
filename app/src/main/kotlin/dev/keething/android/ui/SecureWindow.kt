package dev.keething.android.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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

/**
 * A secure text field holding [length] characters that gives accessibility services only that length while hidden, as
 * a View's password field does: Compose's `SecureTextField` gives them the typed text itself (keething-spec
 * app.accessibility AC2). The caller reads the length in composition, so the dots follow the typing. Shown, the text is
 * on the screen and read as it is.
 */
internal fun Modifier.secretToAccessibility(length: Int, shown: Boolean): Modifier =
    if (shown) this else semantics { editableText = AnnotatedString("\u2022".repeat(length)) }
