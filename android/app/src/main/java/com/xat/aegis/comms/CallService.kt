package com.xat.aegis.comms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service for the duration of a call. Its notification is the
 * ongoing-call card with a hang-up button; being a microphone-type service is
 * what lets the call keep recording when the app leaves the screen. It is
 * started from the app's own screen (placing a call or accepting one) and
 * stops itself when [CallManager] reports no call.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommsRepository.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            CallManager.hangUp()
            return START_NOT_STICKY
        }
        val current = CallManager.call.value
        if (current == null || current.phase == CallPhase.ENDED) {
            // Started for a call that ended before the service ran (a call that
            // failed at once, say). A service started in the foreground must go
            // foreground before it stops, or Android kills the whole app.
            runCatching { startForeground(CommsNotifications.CALL_NOTIFICATION_ID, CommsNotifications.callEndedPlaceholder(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        promote(current)
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                CallManager.call.collect { c ->
                    if (c == null || c.phase == CallPhase.ENDED) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        promote(c)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(call: ActiveCall) {
        val notification = CommsNotifications.ongoingCall(this, call)
        try {
            startForeground(CommsNotifications.CALL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Refused when started from the background or without the microphone
            // permission; the call itself is already ending in that case.
            Log.w(TAG, "could not go foreground: ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallService"
        const val ACTION_HANGUP = "com.xat.aegis.comms.CALL_HANGUP"

        fun start(context: Context) {
            runCatching { context.startForegroundCompat(Intent(context, CallService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the call service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
