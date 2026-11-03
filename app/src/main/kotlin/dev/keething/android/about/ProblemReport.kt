package dev.keething.android.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.net.URLEncoder

/**
 * Report a problem (SPEC §6.2 "About"): a new GitHub issue in the browser, its body begun with this build, Android and
 * the phone's model. Nothing from a database, and nothing is sent: the app has no network, the person sends the issue.
 */
internal object ProblemReport {
    const val ISSUES = "https://github.com/cotton-more/keething-android/issues/new"

    /** The new issue's address: a body asking what happened, then the build and the phone. */
    fun url(info: BuildInfo, android: String, sdk: Int, device: String): String {
        val body = """
            |**What happened**
            |
            |
            |**What you expected**
            |
            |
            |**Steps to make it happen again**
            |
            |
            |---
            |Keething ${info.versionText}, commit ${info.commitText}
            |Android $android (API $sdk)
            |$device
        """.trimMargin()
        return "$ISSUES?body=" + URLEncoder.encode(body, Charsets.UTF_8).replace("+", "%20")
    }

    /** "Google Pixel 6", "Samsung SM-S911B"; a model that names its maker already is left as it is. */
    fun device(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "${manufacturer.replaceFirstChar { it.uppercase() }} $model"

    fun open(context: Context) {
        val url = url(BuildInfo.read(context.assets), Build.VERSION.RELEASE, Build.VERSION.SDK_INT, device(Build.MANUFACTURER, Build.MODEL))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser opens $ISSUES", Toast.LENGTH_LONG).show()
        }
    }
}
