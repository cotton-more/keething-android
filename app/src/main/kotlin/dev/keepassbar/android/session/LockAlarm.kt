package dev.keepassbar.android.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.keepassbar.android.KeePassBarApplication
import kotlin.time.Duration

/**
 * Wakes the app when the lock timeout has passed in the background. Android freezes an app in the
 * background, so the session's own timer may not run until the app comes back; without the alarm the vault
 * would stay in memory until then. Inexact (no exact-alarm permission): it fires at the timeout or a little
 * later, and in Doze at the next maintenance window.
 */
internal class LockAlarm(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(after: Duration) {
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + after.inWholeMilliseconds,
            pendingIntent(),
        )
    }

    fun cancel() = alarms.cancel(pendingIntent())

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, LockAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

internal class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Session", "the lock alarm went off")
        (context.applicationContext as KeePassBarApplication).container.sessions.checkBackgroundTime()
    }
}
