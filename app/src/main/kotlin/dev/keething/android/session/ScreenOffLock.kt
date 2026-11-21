package dev.keething.android.session

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * The phone's screen going off (keething-spec security.auto-lock AC4). An app cannot tell the phone's lock screen
 * coming up from its screen going off, so on a phone with a screen lock (a PIN, pattern or password) the screen off
 * counts as the phone locking and every open database locks at once; on one without, only the timeout locks. Asked when
 * the screen goes off, since the screen lock can be set or removed while the app runs.
 *
 * Registered for the whole process at its start: Android delivers the broadcast at once while the app runs in front,
 * and holds it for an app it has frozen in the background until the app runs again.
 */
internal class ScreenOffLock(private val context: Context, private val sessions: Sessions) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            val secure = keyguard.isDeviceSecure
            Log.i(TAG, "screen off, ${if (secure) "a screen lock" else "no screen lock"}")
            sessions.screenTurnedOff(phoneHasScreenLock = secure)
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    private companion object {
        const val TAG = "Session" // not user text
    }
}
