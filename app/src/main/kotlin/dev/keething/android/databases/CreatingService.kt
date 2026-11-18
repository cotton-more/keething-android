package dev.keething.android.databases

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.keething.android.R

/**
 * Keeps the app's process while a new database is made (keething-spec databases.new-database-create AC5; plan AD7,
 * decided at its start): removing the app from recents ends a process without one, and the file being written would be
 * left half-made, a new key file without its database. A short foreground service (Android 14's `shortService`, no
 * permission of its own) holds it for the few seconds Create takes; what it cannot hold (a crash, the system's own
 * kill) the [dev.keething.android.storage.CreationNote] cleans up at the next start. Without the notification
 * permission its notification is not shown; the service runs all the same.
 */
internal class CreatingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.databases_creating_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.databases_creating_title))
            .setOngoing(true)
            .build()
        try {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            running = true
        } catch (failure: Exception) {
            // Not allowed now (started from the background): Create goes on without it; the note still cleans up.
            Log.w(TAG, "not in the foreground: ${failure.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Create was over before the service came up: in the foreground first, as Android requires, then gone.
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** A short service has about three minutes; Create takes seconds. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "timed out")
        stopSelf()
    }

    companion object {
        private const val TAG = "Creating" // not user text
        private const val CHANNEL = "creating"
        private const val ID = 7

        /** Create runs: a service that comes up after it ended stops itself at once. Main thread only, as the service's calls. */
        private var wanted = false

        /** The service is in the foreground: it may be stopped from outside (before, Android would end the app). */
        private var running = false

        /** Create was tapped, while the app is in the foreground (a short service may only start then). */
        fun start(context: Context) {
            wanted = true
            try {
                context.startForegroundService(Intent(context, CreatingService::class.java))
            } catch (failure: Exception) {
                Log.w(TAG, "not started: ${failure.javaClass.simpleName}")
            }
        }

        /** Create is over; a service not yet in the foreground stops itself once it is. */
        fun stop(context: Context) {
            wanted = false
            if (running) context.stopService(Intent(context, CreatingService::class.java))
        }
    }
}
