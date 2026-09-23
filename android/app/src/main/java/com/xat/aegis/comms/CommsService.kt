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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the WebSocket to the relay open while the owner has comms "online",
 * so envelopes arrive the moment they are queued, and acknowledges each one
 * only after the repository has stored it.
 *
 * It is a foreground service of the remote-messaging type: Android would
 * otherwise kill the socket within minutes of the app leaving the screen.
 * When it is not running, delivery falls back to the UnifiedPush wake-up
 * ([CommsPushReceiver]) and to syncing when the app opens.
 */
class CommsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile
    private var socket: WebSocket? = null

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
        promote(connected = false)
        if (loop?.isActive != true) loop = scope.launch { connectLoop() }
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

    /** Connects, serves the socket until it drops, then reconnects with backoff. */
    private suspend fun connectLoop() {
        var backoffMs = 2_000L
        while (scope.isActive) {
            val closed = Channel<String>(Channel.CONFLATED)
            val ws = try {
                CommsRepository.relayClient().openSocket(listener(closed))
            } catch (e: Exception) {
                Log.w(TAG, "socket open failed: ${e.message}")
                null
            }
            socket = ws
            if (ws != null) {
                val reason = closed.receive()
                socket = null
                CommsRepository.setConnected(false)
                promote(connected = false)
                Log.i(TAG, "socket closed: $reason")
                if (reason == "ready") backoffMs = 2_000L
            }
            if (!scope.isActive) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun listener(closed: Channel<String>) = object : WebSocketListener() {
        private var wasReady = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            CommsRepository.setConnected(true)
            promote(connected = true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "envelope" -> {
                    val envelope = runCatching { CommsRepository.relayClient().parseEnvelope(json.getJSONObject("envelope")) }.getOrNull() ?: return
                    scope.launch {
                        if (CommsRepository.handleEnvelope(envelope)) {
                            webSocket.send(JSONObject().put("type", "ack").put("ids", JSONArray(listOf(envelope.id))).toString())
                        }
                    }
                }
                "ready" -> {
                    wasReady = true
                    // The backlog has been replayed; now send what queued up while offline.
                    scope.launch { CommsRepository.flushOutbox() }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.trySend(if (wasReady) "ready" else "closed:$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.trySend(if (wasReady) "ready" else "failure:${t.message}")
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        socket?.close(1000, "service stopped")
        socket = null
        scope.cancel()
        CommsRepository.setConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CommsService"
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
