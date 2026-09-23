package com.xat.aegis.comms

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The comms module's single source of truth for the UI and the push service:
 * pairing state, the cached conversation, and the relay calls that change them.
 *
 * Every relay call runs on the IO dispatcher. Sync is serialised with a mutex so a
 * push-triggered sync and a foreground one cannot interleave and double-apply a
 * page. The cache version ticks whenever stored messages change, and the screens
 * re-query on it.
 */
object CommsRepository {

    private lateinit var appContext: Context
    private lateinit var config: CommsConfig
    private lateinit var store: CommsStore
    private lateinit var api: CommsApi

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val _state = MutableStateFlow(CommsState())
    val state: StateFlow<CommsState> = _state.asStateFlow()

    /** Bumped after every change to the cache; screens query [threads]/[messages] on it. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    @Volatile
    private var initialised = false

    /** Idempotent; the Activity and the push service both call it. */
    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        config = CommsConfig(appContext)
        store = CommsStore(appContext)
        api = CommsApi(config)
        initialised = true
        _state.value = CommsState(
            paired = config.isPaired,
            workerUrl = config.workerUrl,
            number = config.number,
            pushRegistered = config.registeredPushToken != null
        )
        scope.launch { refreshUnread() }
    }

    val isPaired: Boolean get() = initialised && config.isPaired

    // ── Reads (blocking; call from IO) ────────────────────────────────────

    fun threads(): List<Thread> = store.threads()

    fun messages(peer: String): List<SmsMessage> = store.messages(peer)

    fun markRead(peer: String) {
        store.markRead(peer)
        refreshUnread()
        bump()
    }

    // ── Pairing ───────────────────────────────────────────────────────────

    /** Pairs with the relay at [workerUrl] using the one-time [secret]. */
    suspend fun pair(workerUrl: String, secret: String, deviceName: String): Result<Unit> = withContext(Dispatchers.IO) {
        busy(true)
        try {
            val base = normaliseUrl(workerUrl) ?: return@withContext fail("Enter the Worker URL, e.g. https://aegis-comms.example.workers.dev")
            val enrolled = api.enroll(base, secret.trim(), deviceName)
            store.clear()
            config.savePairing(base, enrolled.deviceId, enrolled.token, enrolled.number)
            _state.update {
                it.copy(paired = true, workerUrl = base, number = enrolled.number, pushRegistered = false, error = null, lastSync = 0L)
            }
            bump()
            registerPushIfPossible()
            syncLocked()
            Result.success(Unit)
        } catch (e: CommsException) {
            fail(e.message ?: "Pairing failed")
        } catch (e: Exception) {
            fail("Pairing failed: ${e.javaClass.simpleName}")
        } finally {
            busy(false)
        }
    }

    /** Forgets the relay on both sides and wipes the cache and its key. */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        busy(true)
        try {
            if (config.isPaired) runCatching { api.unpair() }
            store.clear()
            config.clear()
            CommsCrypto.destroy()
            _state.value = CommsState()
            bump()
            refreshUnread()
        } finally {
            busy(false)
        }
    }

    // ── Push registration ─────────────────────────────────────────────────

    /** Called by the push service when Firebase issues or rotates the token. */
    fun onPushToken(token: String) {
        if (!initialised || !config.isPaired) return
        scope.launch { registerPush(token) }
    }

    /**
     * Fetches the current Firebase token and registers it, if Firebase is
     * configured in this build (google-services.json present) and the token has
     * changed since the relay last acknowledged one.
     */
    fun registerPushIfPossible() {
        if (!initialised || !config.isPaired) return
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            _state.update { it.copy(pushRegistered = false) }
            return
        }
        scope.launch {
            // getToken() is marked deprecated in firebase-messaging 25.1 with no
            // replacement in the SDK; it remains the way to read the token.
            @Suppress("DEPRECATION")
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return@launch
            registerPush(token)
        }
    }

    private suspend fun registerPush(token: String) {
        if (config.registeredPushToken == token) {
            _state.update { it.copy(pushRegistered = true) }
            return
        }
        try {
            api.registerPush(token)
            config.setRegisteredPushToken(token)
            _state.update { it.copy(pushRegistered = true, error = null) }
        } catch (e: CommsException) {
            _state.update { it.copy(pushRegistered = false, error = "Push registration failed: ${e.message}") }
        }
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /**
     * Pulls everything newer than the cursor plus any status changes, stores it,
     * and returns the inbound messages the cache had not seen before (for
     * notifications). Safe to call from anywhere; serialised internally.
     */
    suspend fun sync(): List<SmsMessage> = withContext(Dispatchers.IO) {
        if (!initialised || !config.isPaired) return@withContext emptyList()
        syncMutex.withLock { syncLocked() }
    }

    private suspend fun syncLocked(): List<SmsMessage> {
        busy(true)
        val fresh = ArrayList<SmsMessage>()
        try {
            // New messages, page by page, until the relay says there are no more.
            var cursor = config.syncCursor
            var pages = 0
            do {
                val page = api.messagesAfter(cursor)
                fresh += store.upsert(page.messages).filter { it.direction == Direction.IN }
                cursor = page.next
                config.setSyncCursor(cursor)
                pages++
            } while (page.more && pages < 50)

            // Delivery-state changes to messages the cache already holds.
            val updates = api.updatesSince(config.updatesCursor)
            if (updates.messages.isNotEmpty()) store.upsert(updates.messages)
            config.setUpdatesCursor(updates.now)

            _state.update { it.copy(error = null, lastSync = System.currentTimeMillis()) }
        } catch (e: CommsException) {
            _state.update { it.copy(error = e.message) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Sync failed: ${e.javaClass.simpleName}") }
        } finally {
            busy(false)
            refreshUnread()
            bump()
        }
        return fresh
    }

    /** Sync in the background, from lifecycle hooks. */
    fun syncInBackground() {
        if (!isPaired) return
        scope.launch { sync() }
    }

    // ── Sending ───────────────────────────────────────────────────────────

    /** Sends an SMS. The stored copy comes back from the relay with Twilio's SID. */
    suspend fun send(to: String, text: String): Result<SmsMessage> = withContext(Dispatchers.IO) {
        if (!config.isPaired) return@withContext Result.failure(CommsException("Not paired with a relay"))
        busy(true)
        try {
            val sent = api.send(to, text)
            store.upsert(listOf(sent))
            // The relay assigned this message a seq; move the cursor past it so the
            // next sync does not re-fetch it, but never backwards.
            if (sent.seq > config.syncCursor) config.setSyncCursor(sent.seq)
            _state.update { it.copy(error = null) }
            bump()
            Result.success(sent)
        } catch (e: CommsException) {
            _state.update { it.copy(error = e.message) }
            Result.failure(e)
        } finally {
            busy(false)
        }
    }

    /** Re-reads one message's delivery status from Twilio via the relay. */
    suspend fun refresh(id: String) = withContext(Dispatchers.IO) {
        runCatching { api.refresh(id) }.getOrNull()?.let {
            store.upsert(listOf(it))
            bump()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun bump() { _version.update { it + 1 } }

    private fun busy(value: Boolean) { _state.update { it.copy(busy = value) } }

    private fun refreshUnread() {
        _unread.value = runCatching { store.unreadCount() }.getOrDefault(0)
    }

    private fun fail(message: String): Result<Unit> {
        _state.update { it.copy(error = message) }
        return Result.failure(CommsException(message))
    }

    private fun normaliseUrl(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        // The relay is only ever reached over TLS; a bearer token over plain HTTP
        // would be readable by every network in between.
        if (s.startsWith("http://")) s = "https://" + s.removePrefix("http://")
        return s
    }
}
