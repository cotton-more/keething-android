package dev.keething.android.clipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.text.Html
import android.os.SystemClock
import android.util.Log
import dev.keething.android.KeethingApplication
import kotlin.time.Duration

/**
 * The system clipboard. Each copy carries a token in its description, so the app can tell its own copy
 * from a later one. `EXTRA_IS_SENSITIVE` keeps the system's copy preview (Android 13+) from showing it.
 */
internal class AndroidSystemClipboard(context: Context) : SystemClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    override fun put(text: String, sensitive: Boolean, token: String) {
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, sensitive)
            putString(TOKEN, token)
        }
        manager.setPrimaryClip(clip)
    }

    // Null both when the clipboard is empty and when the app may not look (in the background).
    override fun owner(): ClipOwner {
        val description = manager.primaryClipDescription ?: return ClipOwner.Unknown
        return description.extras?.getString(TOKEN)?.let(ClipOwner::Ours) ?: ClipOwner.Other
    }

    override fun clear() = manager.clearPrimaryClip()

    // Only the text of the first item (an HTML copy's as text): a picture, a file or a link alone is no text, and a
    // URI's content is never opened. Reading the clip (not only its description) shows the system's "pasted from your
    // clipboard" when another app copied it.
    override fun text(): String? {
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        // A picture in the HTML becomes U+FFFC, the object replacement character: not text.
        return item.text?.toString() ?: item.htmlText?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFFFC", "") }
    }

    // Plain text or HTML, as text() reads them; the clip itself is not read, so the system shows no notice.
    override fun hasText(): Boolean = manager.primaryClipDescription?.let {
        it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    } == true

    private companion object {
        const val LABEL = "Keething"
        const val TOKEN = "dev.keething.android.clipToken"
    }
}

/**
 * The alarm that clears the clipboard when the app is frozen in the background. Inexact and not waking the
 * phone: when the screen is off nobody pastes, and it fires as soon as the phone is in use again.
 */
internal class ClipboardClearAlarm(private val context: Context) : ClearAlarm {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun schedule(token: String, after: Duration) {
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + after.inWholeMilliseconds, pendingIntent(token))
    }

    override fun cancel() = alarms.cancel(pendingIntent(null))

    // One request code: a new copy's alarm replaces the one before (the token extra does not matter for that).
    private fun pendingIntent(token: String?): PendingIntent {
        val intent = Intent(context, ClipboardClearReceiver::class.java).putExtra(EXTRA_TOKEN, token)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val EXTRA_TOKEN = "token"
    }
}

internal class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(ClipboardClearAlarm.EXTRA_TOKEN) ?: return
        Log.i("Clipboard", "the clear alarm went off")
        (context.applicationContext as KeethingApplication).container.clipboard.clearIfStill(token)
    }
}
