package app.clairix.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.clairix.BuildConfig
import app.clairix.MainActivity

/**
 * Keeps the process in the foreground while turn-by-turn navigation runs, so fused location keeps
 * delivering fixes every second with the screen off or another app on top. [Navigator] owns the
 * session; this service only holds the foreground state and the notification. If the system
 * refuses the foreground start, navigation carries on while Clairix is on screen.
 */
class NavigationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must come first: startForegroundService gives us only a few seconds.
        if (!enterForeground()) stopSelf()
        // Navigation is driven by the Navigator; if the process dies, do not come back on our own.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW)
        )
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            true
        } catch (e: Exception) {
            // SecurityException (location not granted) or
            // ForegroundServiceStartNotAllowedException (started from the background).
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Navigating")
            .setContentText("Turn-by-turn guidance is running")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 0x0D22

        fun start(context: Context) {
            // Declared only in the full flavour's manifest; the comms build has no navigation.
            if (!BuildConfig.FULL_ACCESS) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            }
        }

        fun stop(context: Context) {
            if (!BuildConfig.FULL_ACCESS) return
            runCatching { context.stopService(Intent(context, NavigationService::class.java)) }
        }
    }
}
