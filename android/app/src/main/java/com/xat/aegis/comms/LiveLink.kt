package com.xat.aegis.comms

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
 *
 * A socket kept open by the background service has to survive a phone that
 * sleeps. The relay sends a heartbeat every couple of minutes, which also
 * wakes the phone long enough to notice a dead connection; reconnecting and
 * handling a delivery run under short wake locks so the phone does not fall
 * asleep halfway; a ping that gets no answer replaces a socket that only
 * looks open; and [LinkWatchdog] checks on it every few minutes in case all
 * of that went quiet.
 */
object LiveLink {

    private const val TAG = "LiveLink"
    private const val GRACE_MS = 4_000L
    private const val BACKOFF_MIN_MS = 2_000L
    private const val BACKOFF_MAX_MS = 60_000L
    /** How long a liveness ping may go unanswered before the socket is presumed dead. */
    private const val PROBE_TIMEOUT_MS = 6_000L
    /** A dropped socket is reconnected with the phone kept awake this long at most. */
    private const val RECONNECT_AWAKE_MS = 30_000L
    /** Back-offs longer than this are waited out asleep; a network change or the watchdog ends them. */
    private const val AWAKE_BACKOFF_MAX_MS = 8_000L
    /** Upper bound on handling one delivered envelope with the phone kept awake. */
    private const val ENVELOPE_AWAKE_MS = 20_000L
    /**
     * Nothing from the relay for this long means the socket is dead: the relay
     * sends a heartbeat every two minutes (comms-worker HEARTBEAT_MS).
     */
    const val SILENT_MS = 5 * 60_000L

    private var appContext: Context? = null

    /** Gives the link a context for its wake locks; called from [CommsRepository.init]. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun wakeLock(tag: String, counted: Boolean): PowerManager.WakeLock? =
        appContext?.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            ?.apply { setReferenceCounted(counted) }

    private val envelopeWake by lazy { wakeLock("aegis:relay-envelope", counted = true) }
    private val reconnectWake by lazy { wakeLock("aegis:relay-reconnect", counted = false) }
    private val probeWake by lazy { wakeLock("aegis:relay-probe", counted = false) }

    private fun PowerManager.WakeLock?.releaseQuietly() {
        this ?: return
        runCatching { if (isHeld) release() }
    }

    /** When the relay last sent anything on the current socket, by the clock that keeps running in sleep. */
    @Volatile
    private var lastFrameAt = 0L

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "relay connection failed", e)
        CommsLog.add("Relay connection error: ${e.javaClass.simpleName}: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val holders = HashSet<String>()
    private var loop: Job? = null
    private var stopper: Job? = null

    /** What the socket delivered, in the order the relay sent it. */
    private sealed class Inbound {
        class Envelope(val socket: WebSocket, val envelope: RelayClient.Envelope) : Inbound()
        /** The relay has replayed the backlog; what queued up locally can go now. */
        class Ready(val backlogFull: Boolean) : Inbound()
    }

    /**
     * Envelopes are handled one at a time in arrival order. Handling each in its
     * own coroutine let a call's ICE candidates or its cancel overtake the offer
     * they belong to, so they were dropped or the cancel was missed.
     */
    private val inbound = Channel<Inbound>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in inbound) {
                try {
                    when (item) {
                        is Inbound.Envelope -> try {
                            if (CommsRepository.handleEnvelope(item.envelope)) {
                                item.socket.send(JSONObject().put("type", "ack").put("ids", JSONArray(listOf(item.envelope.id))).toString())
                            }
                        } finally {
                            envelopeWake.releaseQuietly()
                        }
                        is Inbound.Ready -> {
                            CommsRepository.flushOutbox()
                            CommsRepository.flushReceipts()
                            CommsRepository.replenishKeysInBackground()
                            // The relay replays one page; a fuller mailbox is fetched by sync.
                            if (item.backlogFull) CommsRepository.syncInBackground()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad envelope must not stop delivery of the rest.
                    CommsLog.add("Error handling a relay delivery: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Ends the reconnect back-off early when a new hold arrives. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var socket: WebSocket? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** What is keeping the socket open, for the log: the screen, the background service, a call. */
    private fun holdersText(): String = synchronized(this) { holders.sorted().joinToString(", ").ifBlank { "no hold" } }

    /**
     * Keeps the socket open until [release] is called with the same tag. The
     * screen coming up and a call starting also check that an open socket
     * still reaches the relay, since both are about to depend on it.
     */
    fun hold(tag: String) {
        synchronized(this) {
            holders += tag
            stopper?.cancel()
            stopper = null
            if (loop?.isActive != true) loop = scope.launch { connectLoop() }
        }
        kicks.trySend(Unit)
        if (tag != "service") probe(tag)
    }

    /** Whether something holds the socket open. */
    fun held(): Boolean = synchronized(this) { holders.isNotEmpty() }

    /**
     * Checks that the socket really reaches the relay: a ping that gets no
     * answer within a few seconds means it only looks open (the phone changed
     * network, a carrier dropped the mapping), and it is replaced. A socket
     * that is not connected is reconnected straight away instead.
     */
    fun probe(why: String) {
        if (!held()) return
        val ws = socket
        if (ws == null || !_connected.value) {
            kicks.trySend(Unit)
            return
        }
        synchronized(this) {
            if (probeJob?.isActive == true) return
            val sentAt = SystemClock.elapsedRealtime()
            if (!ws.send(PING)) return
            probeWake?.acquire(PROBE_TIMEOUT_MS + 4_000L)
            probeJob = scope.launch {
                try {
                    delay(PROBE_TIMEOUT_MS)
                    if (lastFrameAt < sentAt && socket === ws) {
                        CommsLog.add("Relay connection did not answer ($why); reconnecting")
                        ws.cancel()
                        kicks.trySend(Unit)
                    }
                } finally {
                    probeWake.releaseQuietly()
                }
            }
        }
    }

    private var probeJob: Job? = null

    /**
     * The watchdog's periodic check: a socket the relay has been silent on for
     * longer than its heartbeat allows is dead and replaced; otherwise it is
     * probed. Keeps the phone awake until the answer or the reconnect is in.
     */
    fun checkAlive() {
        if (!held()) return
        val silentFor = SystemClock.elapsedRealtime() - lastFrameAt
        val ws = socket
        if (ws != null && _connected.value && silentFor > SILENT_MS) {
            CommsLog.add("Relay connection silent for ${silentFor / 60_000} min; reconnecting")
            ws.cancel()
            kicks.trySend(Unit)
            return
        }
        probe("watchdog")
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

    @Volatile
    private var network: String? = null

    /**
     * The phone's default network changed. A socket opened over the old one can
     * sit dead for a minute before its pings notice, and a call ringing in that
     * minute is lost; drop it and reconnect over the new network straight away.
     */
    fun onNetwork(id: String) {
        val previous = network
        network = id
        if (previous == null || previous == id) {
            // The same network came back (after a coverage gap, say): reconnect
            // now rather than at the end of a back-off.
            kicks.trySend(Unit)
            return
        }
        val current = socket
        if (current == null) {
            kicks.trySend(Unit)
            return
        }
        CommsLog.add("Network changed; reconnecting to the relay")
        current.cancel()
        kicks.trySend(Unit)
    }

    /** Closes the socket now, whatever holds exist (the identity is going away). */
    fun disconnect() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        loop?.cancel()
        loop = null
        attempt?.alive = false
        attempt = null
        socket?.close(1000, "released")
        socket = null
        setConnected(false)
        reconnectWake.releaseQuietly()
    }

    /**
     * One connection attempt. Its callbacks are ignored once the attempt is
     * abandoned: a socket still in its handshake when the link was stopped
     * used to open afterwards and report "connected" with nothing behind it.
     */
    private class Attempt {
        @Volatile var alive = true
    }

    @Volatile
    private var attempt: Attempt? = null

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
                // Kicks that arrived while connected are stale; only new ones cut a wait short.
                while (kicks.tryReceive().isSuccess) Unit
                // Stay awake until this attempt is in: a phone that sleeps mid-
                // reconnect would otherwise stay offline until something else wakes it.
                reconnectWake?.acquire(RECONNECT_AWAKE_MS)
                val closed = Channel<String>(Channel.CONFLATED)
                val current = Attempt()
                attempt = current
                val ws = try {
                    CommsRepository.relayClient().openSocket(listener(closed, current))
                } catch (e: Exception) {
                    CommsLog.add("Could not open the relay connection: ${e.message}")
                    null
                }
                socket = ws
                if (ws != null) {
                    val reason = try {
                        closed.receive()
                    } catch (e: CancellationException) {
                        // Stopped while connected: close the socket rather than leak it.
                        current.alive = false
                        ws.close(1000, "stopped")
                        throw e
                    }
                    current.alive = false
                    socket = null
                    setConnected(false)
                    CommsLog.add("Relay connection lost (${reason.removePrefix("ready").ifBlank { "dropped" }}); reconnecting")
                    if (reason == "ready") backoffMs = BACKOFF_MIN_MS
                }
                // Long waits happen asleep; a network change, a new hold or the
                // watchdog ends them early.
                if (backoffMs > AWAKE_BACKOFF_MAX_MS) reconnectWake.releaseQuietly()
                // A kick (new hold, network back, dead socket replaced) retries at
                // once from the shortest back-off; otherwise the wait doubles.
                val kicked = withTimeoutOrNull(backoffMs) { kicks.receive() } != null
                backoffMs = if (kicked) BACKOFF_MIN_MS else (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        } finally {
            socket = null
            setConnected(false)
            reconnectWake.releaseQuietly()
        }
    }

    private fun listener(closed: Channel<String>, current: Attempt) = object : WebSocketListener() {
        private var wasReady = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current.alive) {
                webSocket.cancel()
                return
            }
            CommsRepository.relayClient().noteServerTime(response)
            lastFrameAt = SystemClock.elapsedRealtime()
            CommsLog.add("Connected to the relay (${holdersText()})")
            setConnected(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current.alive) return
            // Anything at all from the relay, heartbeats and pongs included, shows the socket is alive.
            lastFrameAt = SystemClock.elapsedRealtime()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "envelope" -> {
                    val envelope = runCatching { CommsRepository.relayClient().parseEnvelope(json.getJSONObject("envelope")) }.getOrNull()
                    if (envelope == null) {
                        CommsLog.add("Relay sent an envelope this app could not parse")
                        return
                    }
                    // Held until the envelope is handled (a call offer rings, a
                    // message is stored and acked), however long the queue ahead is.
                    envelopeWake?.acquire(ENVELOPE_AWAKE_MS)
                    if (inbound.trySend(Inbound.Envelope(webSocket, envelope)).isFailure) envelopeWake.releaseQuietly()
                }
                "ready" -> {
                    wasReady = true
                    reconnectWake.releaseQuietly()
                    // The backlog has been replayed; now send what queued up while offline.
                    inbound.trySend(Inbound.Ready(json.optInt("pending") >= RelayClient.INBOX_PAGE))
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
            // A refused upgrade still carries the relay's clock. A phone whose
            // clock is off gets 401 for its timestamp; with the offset learned,
            // the next attempt is signed correctly, so it is made at once.
            if (response != null && current.alive) {
                val clockFixed = CommsRepository.relayClient().noteServerTime(response)
                if (response.code == 401 && clockFixed) kicks.trySend(Unit)
                runCatching { response.close() }
            }
            closed.trySend(if (wasReady) "ready" else "failure:${response?.code ?: t.message}")
        }
    }

    private const val PING = "{\"type\":\"ping\"}"
}
