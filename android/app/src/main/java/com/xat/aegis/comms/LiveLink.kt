package com.xat.aegis.comms

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one live WebSocket to the relay, through which envelopes arrive the
 * moment they are queued and are acknowledged once stored.
 *
 * Several parts of the app want it open at different times: the screen while
 * the app is visible, [CommsService] while the owner keeps comms online in the
 * background, and [CallManager] for the length of a call. Each takes a hold
 * under its own tag; the socket stays up while any hold exists and is closed a
 * few seconds after the last one goes, so a permission dialog or a quick trip
 * to another app does not cost a reconnect.
 *
 * Without a hold, delivery falls back to the UnifiedPush wake-up
 * ([CommsPushReceiver]) and to syncing when the app opens.
 */
object LiveLink {

    private const val TAG = "LiveLink"
    private const val GRACE_MS = 4_000L
    private const val BACKOFF_MIN_MS = 2_000L
    private const val BACKOFF_MAX_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val holders = HashSet<String>()
    private var loop: Job? = null
    private var stopper: Job? = null

    /** Ends the reconnect back-off early when a new hold arrives. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var socket: WebSocket? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Keeps the socket open until [release] is called with the same tag. */
    fun hold(tag: String) {
        synchronized(this) {
            holders += tag
            stopper?.cancel()
            stopper = null
            if (loop?.isActive != true) loop = scope.launch { connectLoop() }
        }
        kicks.trySend(Unit)
    }

    /** Drops one hold; the socket closes shortly after the last one is released. */
    fun release(tag: String) {
        synchronized(this) {
            if (!holders.remove(tag) || holders.isNotEmpty()) return
            stopper?.cancel()
            stopper = scope.launch {
                delay(GRACE_MS)
                synchronized(this@LiveLink) { if (holders.isEmpty()) stopLocked() }
            }
        }
    }

    /** Starts the connection if something holds it but it is not running (after registering, say). */
    fun refresh() {
        synchronized(this) {
            if (holders.isEmpty()) return
            if (loop?.isActive != true) loop = scope.launch { connectLoop() }
        }
        kicks.trySend(Unit)
    }

    /** Closes the socket now, whatever holds exist (the identity is going away). */
    fun disconnect() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "released")
        socket = null
        setConnected(false)
    }

    private fun setConnected(connected: Boolean) {
        _connected.value = connected
        CommsRepository.setConnected(connected)
    }

    /** Connects, serves the socket until it drops, then reconnects with back-off. */
    private suspend fun connectLoop() {
        var backoffMs = BACKOFF_MIN_MS
        try {
            while (scope.isActive) {
                if (!CommsRepository.isRegistered()) return
                val closed = Channel<String>(Channel.CONFLATED)
                val ws = try {
                    CommsRepository.relayClient().openSocket(listener(closed))
                } catch (e: Exception) {
                    Log.w(TAG, "socket open failed: ${e.message}")
                    null
                }
                socket = ws
                if (ws != null) {
                    val reason = try {
                        closed.receive()
                    } catch (e: CancellationException) {
                        // Stopped while connected: close the socket rather than leak it.
                        ws.close(1000, "stopped")
                        throw e
                    }
                    socket = null
                    setConnected(false)
                    Log.i(TAG, "socket closed: $reason")
                    if (reason == "ready") backoffMs = BACKOFF_MIN_MS
                }
                // A new hold cuts the wait short; a socket that never became ready backs off.
                withTimeoutOrNull(backoffMs) { kicks.receive() }
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        } finally {
            socket = null
            setConnected(false)
        }
    }

    private fun listener(closed: Channel<String>) = object : WebSocketListener() {
        private var wasReady = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            setConnected(true)
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
}
