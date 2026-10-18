package dev.keepassbar.android.clipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import dev.keepassbar.android.KeePassBarApplication
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

    private companion object {
        const val LABEL = "KeePassBar"
        const val TOKEN = "dev.keepassbar.android.clipToken"
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
        (context.applicationContext as KeePassBarApplication).container.clipboard.clearIfStill(token)
    }
}
