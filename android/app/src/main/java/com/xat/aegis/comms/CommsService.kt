package com.xat.aegis.comms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app alive in the background while the owner has comms "online",
 * so [LiveLink] can hold the relay socket open and envelopes (and call offers)
 * arrive the moment they are queued.
 *
 * It is a foreground service of the remote-messaging type: Android would
 * otherwise kill the process within minutes of the app leaving the screen.
 * The socket itself belongs to [LiveLink]; this service only takes a hold on
 * it and mirrors its state in the notification.
 */
class CommsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommsRepository.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CommsRepository.setOnline(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CommsRepository.isRegistered()) {
            stopSelf()
            return START_NOT_STICKY
        }
        promote(LiveLink.connected.value)
        LiveLink.hold(HOLD)
        if (watcher?.isActive != true) {
            watcher = scope.launch { LiveLink.connected.collect { promote(it) } }
        }
        return START_STICKY
    }

    private fun promote(connected: Boolean) {
        val notification = CommsNotifications.linkNotification(this, connected, CommsRepository.number())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(CommsNotifications.LINK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(CommsNotifications.LINK_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        LiveLink.release(HOLD)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CommsService"
        private const val HOLD = "service"
        const val ACTION_STOP = "com.xat.aegis.comms.STOP"

        /**
         * Starts the connection service. Android refuses a foreground start from
         * a background process (a push wake, for example); that case is logged
         * and the next time the app opens it starts normally.
         */
        fun start(context: Context) {
            runCatching { context.startForegroundCompat(Intent(context, CommsService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the relay connection now: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommsService::class.java))
        }

        /** Waits briefly for a background sync; used by the push receiver under its wake lock. */
        suspend fun syncWithin(ms: Long): Boolean = withTimeoutOrNull(ms) { CommsRepository.sync().isSuccess } ?: false
    }
}
