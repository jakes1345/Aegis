package com.xat.aegis.comms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.aegis_comms_crypto.CryptoException
import uniffi.aegis_comms_crypto.PeerKeys
import uniffi.aegis_comms_crypto.fingerprint
import uniffi.aegis_comms_crypto.verify
import java.security.MessageDigest
import java.util.UUID

/**
 * The comms module's single entry point: registration with the relay, the
 * contact list, sending and receiving end-to-end encrypted messages, key
 * replenishment and push wiring.
 *
 * Everything that touches the network or the identity runs on the IO
 * dispatcher under one mutex, so a ratchet step is never taken twice and the
 * pickle on disk always matches what the relay has seen.
 *
 * Wire format inside an envelope (after Olm decryption), JSON:
 *   {"v":1,"t":"msg","id":uuid,"ts":ms,"body":text,"from":number,"name":text,
 *    "k":ed25519,"c":curve25519,"s":sealing,"g":signature}
 *   {"v":1,"t":"receipt","ids":[...],"status":"delivered"|"read","from":number}
 *   {"v":1,"t":"resync","from":number}   — sent when a message could not be read;
 *                                         it starts a fresh session by itself.
 * The sender's keys ride along so a first message from someone who has our
 * number pins their identity without trusting the relay for it; the relay's
 * record for that number must still match before the message is accepted.
 */
object CommsRepository {

    private const val TAG = "CommsRepository"
    private const val ONE_TIME_KEYS_LOW = 20
    private const val ONE_TIME_KEYS_BATCH = 50
    private const val MAX_BODY = 4000
    private const val HOLD_FOREGROUND = "foreground"
    private const val HOLD_CALL = "call"

    private lateinit var appContext: Context
    private lateinit var config: CommsConfig
    private lateinit var identities: IdentityStore
    private lateinit var store: CommsStore
    private lateinit var relay: RelayClient

    /** An unexpected failure in background work is recorded instead of taking the app down. */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "comms background work failed", e)
        CommsLog.add("Error: ${e.javaClass.simpleName}: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val lock = Mutex()

    /** When a resync was last sent to each contact, so a run of unreadable envelopes asks only once. */
    private val lastResync = HashMap<String, Long>()
    private const val RESYNC_MIN_INTERVAL_MS = 60_000L

    /** One outbox flush at a time, so a queued message is never encrypted and sent twice. */
    private val outboxLock = Mutex()
    private val receiptsLock = Mutex()
    private val keysLock = Mutex()
    /** When keys were last topped up from the live connection; see [replenishKeysInBackground]. */
    @Volatile
    private var lastReplenishAt = 0L
    private var retryJob: Job? = null
    @Volatile private var retryBackoffMs = RETRY_MIN_MS
    private const val RETRY_MIN_MS = 5_000L
    private const val RETRY_MAX_MS = 5 * 60_000L
    private const val RECEIPT_BATCH = 100
    private const val MAX_INBOX_PAGES = 25
    private const val ONE_TIME_KEYS_MAX_WAITING = 100
    private const val FALLBACK_ROTATE_MS = 7L * 24 * 3600_000L
    private const val REPLENISH_INTERVAL_MS = 3600_000L
    /** How far back unconfirmed messages are sent again when a contact asks for a new session. */
    private const val RESEND_WINDOW_MS = 7L * 24 * 3600_000L

    @Volatile
    private var initialised = false

    private val _state = MutableStateFlow(CommsState())
    val state: StateFlow<CommsState> = _state.asStateFlow()

    /** Bumped whenever contacts or messages change, so screens re-query. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** The conversation currently on screen; its messages do not raise notifications. */
    @Volatile
    var openPeer: String? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        CommsLog.init(appContext)
        config = CommsConfig(appContext)
        // The Twilio-era module left plaintext phone numbers and Keystore-readable
        // SMS bodies in its own database, with no screen left to delete them from.
        appContext.deleteDatabase("comms.db")
        config.purgeLegacy()
        identities = IdentityStore(appContext)
        store = CommsStore(appContext)
        relay = RelayClient({ identities.get() }, { config.relayUrl }, { config.number })
        LiveLink.init(appContext)
        CallManager.init(appContext)
        // Identities registered before the background connection became the default
        // kept it off, so calls to them could only ring while Aegis was on screen.
        // Switch it on once; the owner can still turn it off in settings.
        if (config.isRegistered && !config.onlineDefaultApplied) {
            config.setOnline(true)
            config.markOnlineDefaultApplied()
            CommsLog.add("Background connection switched on (calls ring while Aegis is closed)")
        }
        initialised = true
        publishConfig()
        scope.launch { refreshUnread() }
        if (config.isRegistered && config.online) CommsService.start(appContext)
        watchNetwork()
        if (config.isRegistered) scope.launch { pruneSessions() }
    }

    /**
     * Drops Olm sessions with anyone who is not a contact. A pre-key message
     * creates a session before the app knows who sent it, and sessions from
     * senders that were rejected used to stay in the saved state for good,
     * making every save slower. Payloads still waiting for their sender to be
     * confirmed keep theirs.
     */
    private suspend fun pruneSessions() {
        runCatching {
            lock.withLock {
                if (!identities.exists()) return@withLock
                val keep = store.contacts().map { it.curve25519 } + store.pending().map { it.senderCurve25519 }
                val dropped = identities.update { it.pruneSessions(keep) }
                if (dropped > 0u) CommsLog.add("Removed sessions with $dropped sender(s) who are not contacts")
            }
        }.onFailure { CommsLog.add("Could not tidy sessions: ${it.message}") }
    }

    /**
     * A new network (Wi-Fi to mobile, back in coverage) sends what was waiting
     * at once and drops a relay socket that belonged to the old network, rather
     * than waiting for a timer or for the socket's pings to notice.
     */
    private fun watchNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LiveLink.onNetwork(network.toString())
                    onNetworkAvailable()
                }
            })
        }.onFailure { CommsLog.add("Cannot watch network changes: ${it.message}") }
    }

    /** The relay's current time; see [RelayClient.relayNow]. */
    fun relayNow(): Long = if (initialised) relay.relayNow() else System.currentTimeMillis()

    private fun publishConfig() {
        _state.update {
            it.copy(
                registered = config.isRegistered,
                relayUrl = config.relayUrl,
                number = config.number,
                displayName = config.displayName,
                listed = config.listed,
                online = config.online,
                pushRegistered = config.pushEndpoint != null
            )
        }
    }

    private fun bump() {
        _version.update { it + 1 }
        scope.launch { refreshUnread() }
    }

    private fun refreshUnread() {
        _unread.value = runCatching { store.unreadCount() }.getOrDefault(0)
    }

    private fun setBusy(busy: Boolean, error: String? = null) {
        _state.update { it.copy(busy = busy, error = error) }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    /** Called by [LiveLink] as the live socket comes and goes. */
    fun setConnected(connected: Boolean) { _state.update { it.copy(connected = connected) } }

    /**
     * The app's screen came up or went away. While it is visible the relay
     * socket is held open, so messages and call offers land in the open
     * conversation instead of waiting for the next sync; a registered identity
     * is not needed to take the hold, the link checks that itself.
     */
    fun onAppVisible(visible: Boolean) {
        if (!initialised) return
        if (visible) {
            LiveLink.hold(HOLD_FOREGROUND)
            // The background service may have been refused at process start (a
            // push wake, say) or stopped by Android since. The app on screen may
            // always start it, and starting it again is harmless.
            if (config.isRegistered && config.online) CommsService.start(appContext)
        } else {
            LiveLink.release(HOLD_FOREGROUND)
        }
    }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Creates this device's identity and registers it with the relay, which
     * allocates the Aegis number. The enrollment secret is used once and never
     * stored; from then on every request is signed by the identity key.
     */
    suspend fun register(relayUrl: String, secret: String, name: String, listed: Boolean, invite: PairingCode? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val result = lock.withLock {
                val url = (invite?.relayUrl ?: relayUrl).trim().trimEnd('/')
                if (!url.startsWith("https://")) return@withLock Result.failure(RelayException("The relay URL must start with https://"))
                if (invite == null && secret.isBlank()) return@withLock Result.failure(RelayException("Enter the enrollment secret"))
                if (invite != null && invite.invite == null) return@withLock Result.failure(RelayException("That code is not an invite"))
                setBusy(true)
                try {
                    val identity = identities.create()
                    identity.generateOneTimeKeys(ONE_TIME_KEYS_BATCH.toUInt())
                    identities.persist()
                    val registered = relay.register(url, identity, secret.takeIf { invite == null }, listed, invite?.invite)
                    identity.markKeysPublished()
                    identities.persist()
                    config.saveRegistration(url, registered.number, name.trim().take(40), listed)
                    // Reachable from the start: the background connection is on until
                    // the owner switches it off, so calls ring with the app closed.
                    config.setOnline(true)
                    config.markOnlineDefaultApplied()
                    publishConfig()
                    setBusy(false)
                    CommsLog.add("Registered as ${formatAegisNumber(registered.number)} on $url" + if (invite != null) " with ${formatAegisNumber(invite.number)}'s invite" else "")
                    CommsService.start(appContext)
                    LiveLink.refresh()
                    Result.success(registered.number)
                } catch (e: Exception) {
                    // Registration failed: keep no half-made identity around.
                    identities.destroy()
                    val reason = e.message ?: "Registration failed"
                    setBusy(false, reason)
                    Result.failure(e)
                }
            }
            // The inviter's keys came in the invite itself, so they are pinned as a
            // verified contact, and a first message tells them who joined; their
            // phone adds this number when it arrives.
            if (invite != null && result.isSuccess) {
                addContactFromCode(invite.copy(invite = null))
                    .onSuccess { contact -> send(contact.number, "Joined Aegis with your invite.") }
                    .onFailure { CommsLog.add("Could not add ${formatAegisNumber(invite.number)} from the invite: ${it.message}") }
            }
            result
        }

    /** A shareable invite from this phone: one registration on this relay, valid for a week. */
    suspend fun createInvite(): Result<Pair<PairingCode, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val code = pairingCode() ?: throw RelayException("Not registered")
            val invite = relay.createInvite()
            CommsLog.add("Invite made; it works once until ${java.text.DateFormat.getDateInstance().format(java.util.Date(invite.expiresAt))}")
            code.copy(invite = invite.code) to invite.expiresAt
        }
    }

    /** Deletes the mailbox on the relay (best effort) and everything local. */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        lock.withLock {
            setBusy(true)
            runCatching { relay.wipe() }.onFailure { Log.w(TAG, "relay wipe failed: ${it.message}") }
            runCatching { UnifiedPush.unregister(appContext) }
            CommsService.stop(appContext)
            LiveLink.disconnect()
            store.clearAll()
            identities.destroy()
            config.clear()
            KeystoreBox.destroy()
            publishConfig()
            _state.update { it.copy(connected = false, lastSync = 0L, relayOneTimeKeys = -1) }
            setBusy(false)
            bump()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────

    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        config.setDisplayName(name.trim().take(40))
        publishConfig()
    }

    suspend fun setListed(listed: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { relay.setListed(listed) }
            .onSuccess { config.setListed(listed); publishConfig() }
            .onFailure { setBusy(false, it.message) }
    }

    /** Keeps (or stops keeping) the relay socket open in the background. */
    fun setOnline(online: Boolean) {
        config.setOnline(online)
        publishConfig()
        if (online) CommsService.start(appContext) else CommsService.stop(appContext)
    }

    // ── Push ──────────────────────────────────────────────────────────────

    /**
     * Asks the UnifiedPush distributor on this phone (ntfy, for example) for an
     * endpoint. Needs an Activity in case the user has to pick a distributor.
     */
    fun registerPush(activity: Activity) {
        if (!initialised || !config.isRegistered) return
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(appContext)
            else _state.update { it.copy(error = "No UnifiedPush distributor found. Install ntfy (or another distributor) for instant delivery while offline; messages still arrive when Aegis is open or online.") }
        }
    }

    /** Re-requests the endpoint from an already chosen distributor, without UI. */
    fun registerPushIfPossible() {
        if (!initialised || !config.isRegistered) return
        if (UnifiedPush.getAckDistributor(appContext) == null) return
        if (config.pushEndpoint == null) UnifiedPush.register(appContext)
    }

    fun onPushEndpoint(endpoint: String?) {
        if (!initialised || !config.isRegistered) return
        scope.launch {
            lock.withLock {
                runCatching { relay.setPush(endpoint) }
                    .onSuccess { config.setPushEndpoint(endpoint); publishConfig() }
                    .onFailure { Log.w(TAG, "push endpoint not accepted: ${it.message}") }
            }
        }
    }

    fun onPushFailed(reason: String) {
        _state.update { it.copy(error = "Push registration failed: $reason") }
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    fun contacts(): List<Contact> = store.contacts()
    fun contact(number: String): Contact? = store.contact(number)
    fun threads(): List<ChatThread> = store.threads()
    fun messages(peer: String): List<ChatMessage> = store.messages(peer)

    /** This identity's pairing payload, for the QR code others scan. */
    fun pairingCode(): PairingCode? {
        val relayUrl = config.relayUrl ?: return null
        val number = config.number ?: return null
        val identity = runCatching { identities.get() }.getOrNull() ?: return null
        val bundle = identity.publicBundle()
        return PairingCode(relayUrl, number, bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature, config.displayName)
    }

    fun myEd25519(): String? = runCatching { identities.get()?.ed25519() }.getOrNull()

    /**
     * Adds (or re-verifies) a contact from a scanned QR code. The code carries
     * the contact's keys, so nothing about their identity is taken from the relay.
     */
    suspend fun addContactFromCode(code: PairingCode, name: String? = null): Result<Contact> = withContext(Dispatchers.IO) {
        lock.withLock {
            val relayUrl = config.relayUrl ?: return@withLock Result.failure(RelayException("Not registered"))
            if (code.relayUrl != relayUrl) return@withLock Result.failure(RelayException("That code belongs to a different relay (${code.relayUrl}). Aegis numbers only work between apps on the same relay."))
            if (code.number == config.number) return@withLock Result.failure(RelayException("That is your own Aegis number"))
            if (!verify(code.ed25519, "${code.curve25519}|${code.sealing}".toByteArray(Charsets.UTF_8), code.signature)) {
                return@withLock Result.failure(RelayException("The code's keys are not signed by its identity key"))
            }
            val existing = store.contact(code.number)
            val existingByCurve = store.contactByCurve(code.curve25519)
            if (existingByCurve != null && existingByCurve.number != code.number) {
                return@withLock Result.failure(RelayException("Those keys already belong to ${existingByCurve.name.ifBlank { formatAegisNumber(existingByCurve.number) }}"))
            }
            if (existing != null && existing.curve25519 != code.curve25519) {
                identities.update { it.dropSessions(existing.curve25519) }
            }
            val contact = Contact(
                number = code.number,
                name = (name?.takeIf { it.isNotBlank() } ?: existing?.name?.takeIf { it.isNotBlank() } ?: code.name).trim().take(40),
                ed25519 = code.ed25519,
                curve25519 = code.curve25519,
                sealing = code.sealing,
                signature = code.signature,
                verified = true,
                addedTs = existing?.addedTs ?: System.currentTimeMillis(),
                keyChanged = false
            )
            store.upsertContact(contact)
            bump()
            Result.success(contact)
        }
    }

    /**
     * Adds a listed contact by Aegis number. The keys come from the relay, so
     * the contact stays unverified until their QR code is scanned or the safety
     * number is compared in person.
     */
    suspend fun addContactByNumber(rawNumber: String, name: String): Result<Contact> = withContext(Dispatchers.IO) {
        lock.withLock {
            val number = parseAegisNumber(rawNumber) ?: return@withLock Result.failure(RelayException("An Aegis number is nine digits"))
            if (number == config.number) return@withLock Result.failure(RelayException("That is your own Aegis number"))
            store.contact(number)?.let { return@withLock Result.success(it) }
            setBusy(true)
            try {
                val bundle = relay.bundle(number, null)
                if (!verify(bundle.ed25519, "${bundle.curve25519}|${bundle.sealing}".toByteArray(Charsets.UTF_8), bundle.signature)) {
                    throw RelayException("The relay returned keys that are not signed by that number's identity key")
                }
                store.contactByCurve(bundle.curve25519)?.let { other ->
                    // A relay answering with another contact's real keys would otherwise
                    // let messages from that identity land under this number.
                    throw RelayException("The relay returned keys that already belong to ${other.name.ifBlank { formatAegisNumber(other.number) }}")
                }
                val peer = PeerKeys(bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature)
                // The relay handed out one of their one-time keys; use it now rather than waste it.
                identities.update { it.startSession(peer, bundle.sessionKey) }
                val contact = Contact(
                    number = number, name = name.trim().take(40),
                    ed25519 = bundle.ed25519, curve25519 = bundle.curve25519, sealing = bundle.sealing, signature = bundle.signature,
                    verified = false, addedTs = System.currentTimeMillis()
                )
                store.upsertContact(contact)
                setBusy(false)
                bump()
                Result.success(contact)
            } catch (e: Exception) {
                val reason = when {
                    e is RelayException && e.code == 404 -> "No listed Aegis number ${formatAegisNumber(number)} on this relay. Unlisted numbers are added by scanning their QR code."
                    else -> e.message ?: "Lookup failed"
                }
                setBusy(false, reason)
                Result.failure(RelayException(reason))
            }
        }
    }

    suspend fun renameContact(number: String, name: String) = withContext(Dispatchers.IO) {
        store.contact(number)?.let { store.upsertContact(it.copy(name = name.trim().take(40))) }
        bump()
    }

    /** The owner compared safety numbers in person, or scanned the contact's code. */
    suspend fun markVerified(number: String) = withContext(Dispatchers.IO) {
        store.contact(number)?.let { store.upsertContact(it.copy(verified = true, keyChanged = false)) }
        bump()
    }

    suspend fun deleteContact(number: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            store.contact(number)?.let { c -> identities.update { it.dropSessions(c.curve25519) } }
            store.deleteContact(number)
            CommsNotifications.cancel(appContext, number)
            bump()
        }
    }

    /** The 60-digit safety number shared with [contact]; identical on both phones. */
    fun safetyNumber(contact: Contact): String? =
        runCatching { identities.get()?.safetyNumber(contact.ed25519) }.getOrNull()

    // ── Sending ───────────────────────────────────────────────────────────

    /** Queues a message and tries to send it now. Queued messages are retried until they go. */
    suspend fun send(peer: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val text = body.trim().take(MAX_BODY)
        if (text.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty message"))
        store.contact(peer) ?: return@withContext Result.failure(RelayException("Unknown contact"))
        val id = UUID.randomUUID().toString()
        store.insertMessage(ChatMessage(id, peer, Direction.OUT, text, System.currentTimeMillis(), "queued", read = true))
        bump()
        runCatching { flushOutbox() }.onFailure { CommsLog.add("Sending failed: ${it.message}") }
        val (status, error) = store.statusOf(id) ?: ("queued" to null)
        if (status == "failed") Result.failure(RelayException(error ?: "Not sent")) else Result.success(Unit)
    }

    /** Retries a message that failed. */
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        store.setStatus(id, "queued")
        bump()
        runCatching { flushOutbox() }.onFailure { CommsLog.add("Sending failed: ${it.message}") }
    }

    /**
     * Sends everything queued, oldest first, stopping at the first network
     * failure and trying again later. The repository lock is held only to
     * encrypt each message, never across the network round trip, so a slow
     * network does not hold up envelopes (and call signals) coming in.
     */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext
        outboxLock.withLock {
            for (m in store.queued()) {
                val contact = store.contact(m.peer)
                if (contact == null) {
                    store.setStatus(m.id, "failed", "Contact removed")
                    continue
                }
                when (val outcome = deliver(contact, CommsWire.message(m.id, m.ts, m.body))) {
                    Deliver.Sent -> { store.setStatus(m.id, "sent"); retryBackoffMs = RETRY_MIN_MS }
                    is Deliver.Failed -> {
                        CommsLog.add("Message to ${formatAegisNumber(contact.number)} failed: ${outcome.reason}")
                        store.setStatus(m.id, "failed", outcome.reason)
                    }
                    is Deliver.Offline -> {
                        _state.update { it.copy(error = outcome.reason) }
                        scheduleRetry()
                        break
                    }
                }
            }
        }
        bump()
    }

    private sealed class Deliver {
        object Sent : Deliver()
        class Failed(val reason: String) : Deliver()
        class Offline(val reason: String) : Deliver()
    }

    private sealed class Prepared {
        class Ready(val envelope: ByteArray) : Prepared()
        class Failed(val reason: String) : Prepared()
        class Offline(val reason: String) : Prepared()
    }

    /** Encrypts under the lock, then hands the envelope to the relay without holding it. */
    private suspend fun deliver(contact: Contact, payload: JSONObject): Deliver =
        when (val p = lock.withLock { prepareLocked(contact, payload) }) {
            is Prepared.Ready -> transmit(contact, p.envelope)
            is Prepared.Failed -> Deliver.Failed(p.reason)
            is Prepared.Offline -> Deliver.Offline(p.reason)
        }

    /**
     * Adds this identity to [payload] and encrypts it for [contact], starting a
     * session first if there is none. The ratchet step is saved before this
     * returns, so the envelope can go out after the lock is released and in any
     * order relative to others. Caller holds [lock].
     */
    private fun prepareLocked(contact: Contact, payload: JSONObject): Prepared {
        val label = contact.name.ifBlank { formatAegisNumber(contact.number) }
        // Nothing goes to keys the owner has not looked at since they changed.
        if (contact.keyChanged) return Prepared.Failed("$label's keys have changed. Scan their code or compare safety numbers before messaging them.")
        val peer = PeerKeys(contact.ed25519, contact.curve25519, contact.sealing, contact.signature)
        return try {
            putSelf(payload)
            val identity = identities.get() ?: return Prepared.Failed("No identity on this device")
            if (!identity.hasSession(contact.curve25519)) {
                val bundle = relay.bundle(contact.number, fingerprint(contact.ed25519))
                if (bundle.ed25519 != contact.ed25519 || bundle.curve25519 != contact.curve25519 || bundle.sealing != contact.sealing) {
                    store.upsertContact(contact.copy(keyChanged = true, verified = false))
                    return Prepared.Failed("$label's keys have changed. Scan their code again before messaging them.")
                }
                identities.update { it.startSession(peer, bundle.sessionKey) }
            }
            Prepared.Ready(identities.update { it.encrypt(peer, payload.toString().toByteArray(Charsets.UTF_8)) })
        } catch (e: RelayException) {
            when (val d = relayOutcome(e)) {
                is Deliver.Offline -> Prepared.Offline(d.reason)
                is Deliver.Failed -> Prepared.Failed(d.reason)
                Deliver.Sent -> Prepared.Failed(e.message ?: "Relay error")
            }
        } catch (e: CryptoException) {
            Prepared.Failed("Encryption failed: ${e.message}")
        } catch (e: IllegalStateException) {
            // The identity could not be read or saved; nothing was sent, so nothing is lost.
            Prepared.Failed(e.message ?: "The identity could not be saved")
        }
    }

    /** Posts an encrypted envelope. No lock is held. */
    private fun transmit(contact: Contact, envelope: ByteArray): Deliver = try {
        relay.send(contact.number, envelope)
        Deliver.Sent
    } catch (e: RelayException) {
        if (e.code == 401) {
            // The first request after start-up on a phone whose clock is off is
            // refused; its response taught the client the relay's time, so one
            // retry signs with that. Each attempt uses a fresh nonce.
            try {
                relay.send(contact.number, envelope)
                Deliver.Sent
            } catch (again: RelayException) {
                relayOutcome(again)
            }
        } else relayOutcome(e)
    }

    /** Which relay errors are worth retrying later (Offline) and which are final (Failed). */
    private fun relayOutcome(e: RelayException): Deliver = when {
        e.code == 404 -> Deliver.Failed("No such Aegis number on this relay any more")
        e.code == 401 -> Deliver.Failed("The relay does not accept this phone's signature (is its clock right?)")
        e.code == 0 || e.code == 408 || e.code == 413 || e.code == 429 || e.code >= 500 || e.code == RelayClient.MALFORMED ->
            Deliver.Offline(e.message ?: "The relay is not reachable right now")
        else -> Deliver.Failed(e.message ?: "Relay refused the message")
    }

    /** Tries the outbox and owed receipts again after a growing pause, and on every network change. */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = retryBackoffMs
        retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(RETRY_MAX_MS)
        retryJob = scope.launch {
            delay(wait)
            flushOutbox()
            flushReceipts()
        }
    }

    /** Called when the phone gets a (new) network: send what was waiting straight away. */
    private fun onNetworkAvailable() {
        if (!initialised || !config.isRegistered) return
        retryBackoffMs = RETRY_MIN_MS
        retryJob?.cancel()
        scope.launch {
            flushOutbox()
            flushReceipts()
        }
    }

    /** Adds this identity's number, name and keys to an outgoing payload; see [CommsWire.withSender]. */
    private fun putSelf(o: JSONObject) {
        val bundle = identities.get()?.publicBundle() ?: return
        val number = config.number ?: return
        CommsWire.withSender(
            o, CommsWire.Sender(number, config.displayName, bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature)
        )
    }

    /**
     * Sends a call signal (offer, answer, ICE, end) to [contact] through the
     * encrypted session. Returns null once the relay has it, or the reason it
     * could not be sent, which the call screen shows as it is.
     */
    suspend fun sendCallSignal(contact: Contact, payload: JSONObject): String? = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext "this phone is not registered with a relay"
        val kind = CommsWire.callKind(payload)
        when (val r = deliver(contact, payload)) {
            Deliver.Sent -> { if (kind != CommsWire.CALL_ICE) CommsLog.add("Call $kind sent to ${formatAegisNumber(contact.number)}"); null }
            is Deliver.Failed -> { CommsLog.add("Call $kind to ${formatAegisNumber(contact.number)} failed: ${r.reason}"); r.reason }
            is Deliver.Offline -> { CommsLog.add("Call $kind to ${formatAegisNumber(contact.number)} not sent: ${r.reason}"); r.reason }
        }
    }

    /** Records a call in the conversation with [contact]; a missed call shows as unread. */
    suspend fun logCall(contact: Contact, direction: Direction, body: String, unread: Boolean) = withContext(Dispatchers.IO) {
        store.insertMessage(
            ChatMessage(UUID.randomUUID().toString(), contact.number, direction, body, System.currentTimeMillis(), STATUS_CALL, read = !unread)
        )
        bump()
    }

    /**
     * Keeps the relay socket open for a call's signalling even when the app
     * leaves the screen and the owner has comms offline; released when the
     * call ends. The call's own microphone service keeps the process alive.
     */
    fun holdLiveLink() { if (initialised) LiveLink.hold(HOLD_CALL) }
    fun releaseLiveLink() { if (initialised) LiveLink.release(HOLD_CALL) }

    /**
     * Encrypts a control message now and sends it once the lock is released.
     * Caller holds [lock]. Used for the resync; receipts go through the
     * receipts table so a failed one is retried.
     */
    private fun sendControlLocked(contact: Contact, payload: JSONObject) {
        val type = CommsWire.type(payload)
        when (val p = prepareLocked(contact, payload)) {
            is Prepared.Ready -> scope.launch {
                when (val d = transmit(contact, p.envelope)) {
                    Deliver.Sent -> Unit
                    is Deliver.Failed -> CommsLog.add("$type to ${formatAegisNumber(contact.number)} failed: ${d.reason}")
                    is Deliver.Offline -> CommsLog.add("$type to ${formatAegisNumber(contact.number)} not sent: ${d.reason}")
                }
            }
            is Prepared.Failed -> CommsLog.add("$type to ${formatAegisNumber(contact.number)} failed: ${p.reason}")
            is Prepared.Offline -> CommsLog.add("$type to ${formatAegisNumber(contact.number)} not sent: ${p.reason}")
        }
    }

    /**
     * Sends the receipts owed to contacts, one batch per contact and status.
     * They are stored first (see processPayload and markRead), so a receipt
     * that cannot go now is tried again rather than lost.
     */
    suspend fun flushReceipts() = withContext(Dispatchers.IO) {
        if (!config.isRegistered || !store.hasPendingReceipts()) return@withContext
        receiptsLock.withLock {
            for ((peer, byStatus) in store.pendingReceipts()) {
                val contact = store.contact(peer)
                if (contact == null) { store.clearReceiptsFor(peer); continue }
                for ((status, ids) in byStatus) for (chunk in ids.chunked(RECEIPT_BATCH)) {
                    when (val d = deliver(contact, CommsWire.receipt(chunk, status))) {
                        Deliver.Sent -> store.clearReceipts(peer, chunk, status)
                        is Deliver.Failed -> {
                            // Will never go (their keys changed, the number is gone): drop it.
                            CommsLog.add("Receipt to ${formatAegisNumber(peer)} dropped: ${d.reason}")
                            store.clearReceipts(peer, chunk, status)
                        }
                        is Deliver.Offline -> { scheduleRetry(); return@withLock }
                    }
                }
            }
        }
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /**
     * Opens one envelope from the relay and acts on it. Returns true when the
     * relay may delete it: its payload is safely staged here (and processed, or
     * held for a retry), it was a duplicate, or it can never be read. It
     * returns false, leaving the envelope on the relay, only when the ratchet
     * step could not be saved or the identity could not be read.
     */
    suspend fun handleEnvelope(envelope: RelayClient.Envelope): Boolean = withContext(Dispatchers.IO) {
        val handled = try {
            lock.withLock {
                if (store.hasPending()) retryPendingLocked()
                handleLocked(envelope)
            }
        } catch (e: IllegalStateException) {
            CommsLog.add("Envelope ${shortId(envelope.id)} left on the relay: ${e.message}")
            false
        }
        bump()
        if (store.hasPendingReceipts()) scope.launch { flushReceipts() }
        handled
    }

    /** A digest of the envelope's bytes: duplicates are recognised by content, which the relay cannot change. */
    private fun envelopeKey(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun handleLocked(envelope: RelayClient.Envelope): Boolean {
        // The live socket and an inbox fetch can both hand over the same envelope,
        // and a relay could re-send an old one under a new id. A second copy must
        // not reach the ratchet.
        val key = envelopeKey(envelope.data)
        if (store.isEnvelopeSeen(key)) return true
        val decrypted = try {
            identities.update { it.decrypt(envelope.data) }
        } catch (e: CryptoException.Duplicate) {
            store.markEnvelopeSeen(key, envelope.ts)
            return true
        } catch (e: CryptoException.NoSessionFor) {
            store.markEnvelopeSeen(key, envelope.ts)
            recoverSession(e.senderCurve25519, "Envelope ${shortId(envelope.id)} came on a session this phone does not have")
            return true
        } catch (e: CryptoException.DecryptFrom) {
            store.markEnvelopeSeen(key, envelope.ts)
            recoverSession(e.senderCurve25519, "Envelope ${shortId(envelope.id)} could not be decrypted (${e.reason})")
            return true
        } catch (e: CryptoException) {
            store.markEnvelopeSeen(key, envelope.ts)
            CommsLog.add("Dropped envelope ${shortId(envelope.id)}: ${e.message}")
            return true
        }
        // Durable before anything that can fail: seen and staged in one transaction.
        val text = String(decrypted.plaintext, Charsets.UTF_8)
        if (!store.markSeenAndStage(key, envelope.ts, decrypted.senderCurve25519, text)) return true
        processStagedLocked(CommsStore.PendingInbound(key, envelope.ts, decrypted.senderCurve25519, text))
        return true
    }

    /**
     * Acts on a staged payload and removes it once done. Returns false when the
     * relay was needed and could not be reached, so later ones wait too. Caller
     * holds [lock].
     */
    private fun processStagedLocked(p: CommsStore.PendingInbound): Boolean {
        val json = CommsWire.parse(p.payload)
        if (json == null) {
            CommsLog.add("Envelope ${shortId(p.id)} decrypted but is not an Aegis payload; dropped")
            store.deletePending(p.id)
            return true
        }
        return try {
            when (val resolved = resolveSender(p.senderCurve25519, json)) {
                is Resolution.Found -> { processPayload(resolved.contact, p.ts, json); store.deletePending(p.id); true }
                is Resolution.Rejected -> {
                    CommsLog.add("Envelope ${shortId(p.id)} rejected: ${resolved.reason}")
                    // Its pre-key message created a session for a sender who is not
                    // a contact; nothing will ever be sent on it.
                    if (store.contactByCurve(p.senderCurve25519) == null) identities.update { it.dropSessions(p.senderCurve25519) }
                    store.deletePending(p.id)
                    true
                }
                Resolution.Unreachable -> { CommsLog.add("Relay unreachable while confirming who sent ${shortId(p.id)}; holding it"); false }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // Kept staged and tried again on the next envelope or sync.
            CommsLog.add("Envelope ${shortId(p.id)} held for a retry: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Something from [senderCurve] could not be decrypted. Every session with
     * them is kept, since envelopes already on their way may still need one;
     * instead a fresh session is started (it goes first, so the resync and
     * everything after it use it) and they are asked to switch to it. At most
     * once a minute per contact. Caller holds [lock].
     */
    private fun recoverSession(senderCurve: String, what: String) {
        val contact = store.contactByCurve(senderCurve)
        if (contact == null) {
            CommsLog.add("$what, from someone who is not a contact; dropped")
            return
        }
        val label = contact.name.ifBlank { formatAegisNumber(contact.number) }
        val now = System.currentTimeMillis()
        if (now - (lastResync[contact.number] ?: 0L) < RESYNC_MIN_INTERVAL_MS) {
            CommsLog.add("$what from $label; a new session was asked for moments ago")
            return
        }
        if (contact.keyChanged) {
            CommsLog.add("$what from $label, whose keys changed; verify them before anything is sent")
            return
        }
        lastResync[contact.number] = now
        try {
            val bundle = relay.bundle(contact.number, fingerprint(contact.ed25519))
            if (bundle.ed25519 != contact.ed25519 || bundle.curve25519 != contact.curve25519 || bundle.sealing != contact.sealing) {
                CommsLog.add("$what from $label, but the relay now holds different keys for them; nothing sent")
                return
            }
            identities.update { it.startSession(PeerKeys(contact.ed25519, contact.curve25519, contact.sealing, contact.signature), bundle.sessionKey) }
        } catch (e: RelayException) {
            lastResync.remove(contact.number)
            CommsLog.add("$what from $label; could not start a new session yet (${e.message})")
            return
        }
        CommsLog.add("$what from $label; started a new session and asked them to use it")
        sendControlLocked(contact, CommsWire.resync())
    }

    /** Processes staged payloads that were held for a retry. Caller holds [lock]. */
    private fun retryPendingLocked() {
        for (p in store.pending()) if (!processStagedLocked(p)) return
    }

    /** Acts on a decrypted payload from a known contact. Caller holds [lock]. */
    private fun processPayload(contact: Contact, envelopeTs: Long, json: JSONObject) {
        when (CommsWire.type(json)) {
            CommsWire.T_MSG -> {
                val id = json.optString(CommsWire.F_ID).takeIf { it.isNotBlank() } ?: return
                val alreadyRead = store.isRead(id)
                if (alreadyRead != null) {
                    // A copy of a message already here (they re-sent it after a network
                    // error): receipt it again, since their first receipt may be lost.
                    store.queueReceipt(contact.number, listOf(id), if (alreadyRead) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                    return
                }
                val body = json.optString(CommsWire.F_BODY).take(MAX_BODY)
                val earliest = envelopeTs - 7L * 24 * 3600_000L
                val latest = System.currentTimeMillis() + 60_000L
                val claimed = json.optLong(CommsWire.F_TS, envelopeTs)
                val ts = if (latest >= earliest) claimed.coerceIn(earliest, latest) else envelopeTs
                val onScreen = openPeer == contact.number
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, body, ts, "received", read = onScreen))
                store.queueReceipt(contact.number, listOf(id), if (onScreen) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                if (!onScreen) {
                    CommsNotifications.notifyInbound(appContext, contact, store.unreadInbound(contact.number, 5))
                }
            }
            CommsWire.T_RECEIPT -> {
                val status = json.optString(CommsWire.F_STATUS)
                if (status == CommsWire.STATUS_DELIVERED || status == CommsWire.STATUS_READ) {
                    // Only our own messages to this contact can be receipted by them.
                    store.advanceStatus(contact.number, CommsWire.receiptIds(json), status)
                }
            }
            CommsWire.T_RESYNC -> {
                // Their pre-key message has already created a fresh session on our
                // side, first in the list; everything to them from now on uses it.
                // Whatever they could not read is gone from the relay, so messages
                // they never confirmed go again (they drop ids they already have),
                // and so does the setup of a call with them.
                val resent = store.requeueUndelivered(contact.number, System.currentTimeMillis() - RESEND_WINDOW_MS)
                CommsLog.add(
                    "${contact.name.ifBlank { formatAegisNumber(contact.number) }} could not read something from this phone and started a new session" +
                        if (resent > 0) "; sending $resent message(s) again" else ""
                )
                if (resent > 0) bump()
                // After the caller releases the lock.
                scope.launch { flushOutbox() }
                CallManager.onPeerResync(contact)
            }
            CommsWire.T_CALL -> CallManager.onSignal(contact, json, envelopeTs)
            else -> CommsLog.add("Payload of type \"${CommsWire.type(json)}\" from ${formatAegisNumber(contact.number)} not understood; ignored")
        }
    }

    private sealed class Resolution {
        class Found(val contact: Contact) : Resolution()
        /** The payload claims a number its keys do not hold, or is malformed; drop it. */
        class Rejected(val reason: String) : Resolution()
        /** The relay was needed to confirm the sender and could not be reached; retry later. */
        object Unreachable : Resolution()
    }

    /**
     * Maps the Olm sender key to a contact. A first message from an unknown
     * identity carries its keys and Aegis number; those are pinned as an
     * unverified contact once the relay confirms that number publishes exactly
     * those keys, so nobody can send as a number they do not hold.
     */
    private fun resolveSender(senderCurve: String, json: JSONObject): Resolution {
        store.contactByCurve(senderCurve)?.let { return Resolution.Found(it) }
        val from = parseAegisNumber(json.optString(CommsWire.F_FROM))
            ?: return Resolution.Rejected("sender is not a contact and gave no Aegis number")
        val ed = json.optString(CommsWire.F_ED25519); val curve = json.optString(CommsWire.F_CURVE25519)
        val sealing = json.optString(CommsWire.F_SEALING); val sig = json.optString(CommsWire.F_SIGNATURE)
        if (curve != senderCurve || ed.isBlank() || sealing.isBlank() || sig.isBlank()) {
            return Resolution.Rejected("keys claimed by ${formatAegisNumber(from)} do not match the sender")
        }
        if (!verify(ed, "$curve|$sealing".toByteArray(Charsets.UTF_8), sig)) {
            return Resolution.Rejected("keys claimed by ${formatAegisNumber(from)} are not signed")
        }
        val relayView = try {
            // The identity lookup claims none of their one-time keys.
            relay.identity(from, fingerprint(ed))
        } catch (e: RelayException) {
            // 404 means the relay has no such number, or the pin (their identity key)
            // does not match it: the claim is false. Anything else is the relay's problem.
            return if (e.code == 404) Resolution.Rejected("the relay does not know ${formatAegisNumber(from)} with those keys")
            else Resolution.Unreachable
        }
        if (relayView.ed25519 != ed || relayView.curve25519 != curve || relayView.sealing != sealing) {
            return Resolution.Rejected("the relay holds different keys for ${formatAegisNumber(from)}")
        }
        val existing = store.contact(from)
        val contact = if (existing != null) {
            // Same number, different keys: keep the pinned name, flag it, trust nothing.
            identities.update { it.dropSessions(existing.curve25519) }
            existing.copy(ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig, verified = false, keyChanged = true)
        } else {
            Contact(
                number = from, name = json.optString(CommsWire.F_NAME).trim().take(40),
                ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig,
                verified = false, addedTs = System.currentTimeMillis()
            )
        }
        store.upsertContact(contact)
        return Resolution.Found(contact)
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /** Pulls the whole inbox, sends what is queued (messages and receipts) and tops up keys. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext Result.success(Unit)
        setBusy(true)
        try {
            lock.withLock { retryPendingLocked() }
            // The relay hands out its queue a page at a time; acked envelopes are
            // deleted, so each fetch returns the next page.
            for (page in 0 until MAX_INBOX_PAGES) {
                val envelopes = relay.inbox()
                if (envelopes.isEmpty()) break
                val done = ArrayList<String>()
                for (env in envelopes) {
                    val handled = try {
                        lock.withLock { handleLocked(env) }
                    } catch (e: IllegalStateException) {
                        CommsLog.add("Envelope ${shortId(env.id)} left on the relay: ${e.message}")
                        false
                    }
                    if (handled) done += env.id
                }
                if (done.isNotEmpty()) relay.ack(done)
                if (envelopes.size < RelayClient.INBOX_PAGE || done.size < envelopes.size) break
            }
            flushOutbox()
            flushReceipts()
            replenishKeys()
            _state.update { it.copy(busy = false, error = null, lastSync = System.currentTimeMillis()) }
            bump()
            Result.success(Unit)
        } catch (e: Exception) {
            setBusy(false, e.message ?: "Sync failed")
            bump()
            Result.failure(e)
        }
    }

    fun syncInBackground() {
        if (!initialised || !config.isRegistered) return
        scope.launch { sync() }
    }

    /**
     * Tops up one-time keys from the live connection, at most hourly. A phone
     * kept online by the background service rarely runs a full sync, and its
     * keys on the relay drained until every new session used the fallback key.
     */
    fun replenishKeysInBackground() {
        if (!initialised || !config.isRegistered) return
        val now = System.currentTimeMillis()
        if (now - lastReplenishAt < REPLENISH_INTERVAL_MS) return
        lastReplenishAt = now
        scope.launch {
            runCatching { replenishKeys() }.onFailure {
                lastReplenishAt = 0L
                CommsLog.add("Could not top up keys on the relay: ${it.message}")
            }
        }
    }

    /**
     * Publishes more one-time keys when the relay is running low, and a new
     * fallback key once a week. The network calls are made without the lock.
     * Keys generated for an upload that failed are uploaded next time rather
     * than piling up with new ones.
     */
    private suspend fun replenishKeys() = keysLock.withLock {
        val me = relay.me()
        var count = me.oneTimeKeys
        val now = System.currentTimeMillis()
        val rotate = now - config.fallbackRotatedAt > FALLBACK_ROTATE_MS
        if (count < ONE_TIME_KEYS_LOW || rotate) {
            val bundle = lock.withLock {
                identities.update { id ->
                    var waiting = id.publicBundle().oneTimeKeys.size
                    if (waiting > ONE_TIME_KEYS_MAX_WAITING) {
                        // Never uploaded, so never handed out: they can simply be retired.
                        id.markKeysPublished()
                        waiting = 0
                    }
                    if (count < ONE_TIME_KEYS_LOW && waiting < ONE_TIME_KEYS_BATCH) {
                        id.generateOneTimeKeys((ONE_TIME_KEYS_BATCH - waiting).toUInt())
                    }
                    if (rotate) id.rotateFallbackKey()
                    id.publicBundle()
                }
            }
            count = relay.putKeys(bundle)
            lock.withLock { identities.update { it.markKeysPublished() } }
            if (rotate) config.setFallbackRotatedAt(now)
        }
        if (me.listed != config.listed) config.setListed(me.listed)
        _state.update { it.copy(relayOneTimeKeys = count, listed = me.listed) }
    }

    /** Marks a conversation read and tells the sender (retried until it goes). */
    suspend fun markRead(peer: String) = withContext(Dispatchers.IO) {
        val ids = store.markRead(peer)
        if (ids.isEmpty()) return@withContext
        bump()
        store.queueReceipt(peer, ids, CommsWire.STATUS_READ)
        // On the repository's scope: leaving the screen must not cancel the receipt.
        scope.launch { flushReceipts() }
    }

    // ── Service helpers ───────────────────────────────────────────────────

    internal fun relayClient(): RelayClient = relay
    internal fun isRegistered(): Boolean = initialised && config.isRegistered
    internal fun number(): String? = config.number
}

/** Starts a foreground service in the way Android accepts from any process state. */
internal fun Context.startForegroundCompat(intent: Intent) {
    ContextCompat.startForegroundService(this, intent)
}
