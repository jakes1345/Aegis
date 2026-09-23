package com.xat.aegis.comms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.aegis_comms_crypto.CryptoException
import uniffi.aegis_comms_crypto.PeerKeys
import uniffi.aegis_comms_crypto.fingerprint
import uniffi.aegis_comms_crypto.verify
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

    private lateinit var appContext: Context
    private lateinit var config: CommsConfig
    private lateinit var identities: IdentityStore
    private lateinit var store: CommsStore
    private lateinit var relay: RelayClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

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
        config = CommsConfig(appContext)
        identities = IdentityStore(appContext)
        store = CommsStore(appContext)
        relay = RelayClient(identities, config)
        initialised = true
        publishConfig()
        scope.launch { refreshUnread() }
        if (config.isRegistered && config.online) CommsService.start(appContext)
    }

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

    /** Called by [CommsService] as the live socket comes and goes. */
    fun setConnected(connected: Boolean) { _state.update { it.copy(connected = connected) } }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Creates this device's identity and registers it with the relay, which
     * allocates the Aegis number. The enrollment secret is used once and never
     * stored; from then on every request is signed by the identity key.
     */
    suspend fun register(relayUrl: String, secret: String, name: String, listed: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val url = relayUrl.trim().trimEnd('/')
                if (!url.startsWith("https://")) return@withLock Result.failure(RelayException("The relay URL must start with https://"))
                if (secret.isBlank()) return@withLock Result.failure(RelayException("Enter the enrollment secret"))
                setBusy(true)
                try {
                    val identity = identities.create()
                    identity.generateOneTimeKeys(ONE_TIME_KEYS_BATCH.toUInt())
                    identities.persist()
                    val registered = relay.register(url, identity, secret, listed)
                    identity.markKeysPublished()
                    identities.persist()
                    config.saveRegistration(url, registered.number, name.trim().take(40), listed)
                    publishConfig()
                    setBusy(false)
                    Result.success(registered.number)
                } catch (e: Exception) {
                    // Registration failed: keep no half-made identity around.
                    identities.destroy()
                    val reason = e.message ?: "Registration failed"
                    setBusy(false, reason)
                    Result.failure(e)
                }
            }
        }

    /** Deletes the mailbox on the relay (best effort) and everything local. */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        lock.withLock {
            setBusy(true)
            runCatching { relay.wipe() }.onFailure { Log.w(TAG, "relay wipe failed: ${it.message}") }
            runCatching { UnifiedPush.unregister(appContext) }
            CommsService.stop(appContext)
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
        val identity = identities.get() ?: return null
        val bundle = identity.publicBundle()
        return PairingCode(relayUrl, number, bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature, config.displayName)
    }

    fun myEd25519(): String? = identities.get()?.ed25519()

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

    /** Queues a message and tries to send it now. Queued messages retry on every sync. */
    suspend fun send(peer: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val text = body.trim().take(MAX_BODY)
        if (text.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty message"))
        store.contact(peer) ?: return@withContext Result.failure(RelayException("Unknown contact"))
        store.insertMessage(
            ChatMessage(UUID.randomUUID().toString(), peer, Direction.OUT, text, System.currentTimeMillis(), "queued", read = true)
        )
        bump()
        flushOutbox()
        val stillFailed = store.messages(peer).lastOrNull { it.direction == Direction.OUT }?.takeIf { it.failed }
        if (stillFailed != null) Result.failure(RelayException(stillFailed.error ?: "Not sent")) else Result.success(Unit)
    }

    /** Retries a message that failed. */
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        store.setStatus(id, "queued")
        bump()
        flushOutbox()
    }

    /** Sends everything queued, oldest first, stopping at the first network failure. */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext
        lock.withLock {
            for (m in store.queued()) {
                val contact = store.contact(m.peer)
                if (contact == null) {
                    store.setStatus(m.id, "failed", "Contact removed")
                    continue
                }
                val payload = JSONObject()
                    .put("v", 1).put("t", "msg").put("id", m.id).put("ts", m.ts).put("body", m.body)
                    .apply { putSelf(this) }
                when (val outcome = deliver(contact, payload)) {
                    Deliver.Sent -> store.setStatus(m.id, "sent")
                    is Deliver.Failed -> store.setStatus(m.id, "failed", outcome.reason)
                    is Deliver.Offline -> {
                        _state.update { it.copy(error = outcome.reason) }
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

    /** Encrypts [payload] for [contact] (starting a session if needed) and hands it to the relay. Caller holds [lock]. */
    private fun deliver(contact: Contact, payload: JSONObject): Deliver {
        val identity = identities.get() ?: return Deliver.Failed("No identity on this device")
        val peer = PeerKeys(contact.ed25519, contact.curve25519, contact.sealing, contact.signature)
        try {
            if (!identity.hasSession(contact.curve25519)) {
                val bundle = relay.bundle(contact.number, fingerprint(contact.ed25519))
                if (bundle.ed25519 != contact.ed25519 || bundle.curve25519 != contact.curve25519 || bundle.sealing != contact.sealing) {
                    store.upsertContact(contact.copy(keyChanged = true, verified = false))
                    return Deliver.Failed("${contact.name.ifBlank { formatAegisNumber(contact.number) }}'s keys have changed. Scan their code again before messaging them.")
                }
                identities.update { it.startSession(peer, bundle.sessionKey) }
            }
            val envelope = identities.update { it.encrypt(peer, payload.toString().toByteArray(Charsets.UTF_8)) }
            relay.send(contact.number, envelope)
            return Deliver.Sent
        } catch (e: CryptoException) {
            return Deliver.Failed("Encryption failed: ${e.message}")
        } catch (e: RelayException) {
            return when (e.code) {
                404 -> Deliver.Failed("No such Aegis number on this relay any more")
                413 -> Deliver.Failed("Their mailbox is full")
                401 -> Deliver.Failed("The relay no longer accepts this device's signature")
                0 -> Deliver.Offline(e.message ?: "Could not reach the relay")
                else -> Deliver.Failed(e.message ?: "Relay refused the message")
            }
        }
    }

    private fun putSelf(o: JSONObject) {
        val bundle = identities.get()?.publicBundle() ?: return
        o.put("from", config.number).put("name", config.displayName)
            .put("k", bundle.ed25519).put("c", bundle.curve25519).put("s", bundle.sealing).put("g", bundle.signature)
    }

    /** Sends a control message that is not stored locally (receipts, resync). Caller holds [lock]. */
    private fun sendControl(contact: Contact, payload: JSONObject) {
        putSelf(payload)
        when (val r = deliver(contact, payload)) {
            Deliver.Sent -> Unit
            is Deliver.Failed -> Log.w(TAG, "control message to ${contact.number} failed: ${r.reason}")
            is Deliver.Offline -> Log.w(TAG, "control message to ${contact.number} deferred: ${r.reason}")
        }
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /**
     * Opens one envelope from the relay and acts on it. Returns true when the
     * relay may delete it: it was stored, was a duplicate, or can never be read.
     * False means "try again later" (the relay was needed and unreachable).
     */
    suspend fun handleEnvelope(envelope: RelayClient.Envelope): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { handleLocked(envelope) }.also { bump() }
    }

    private fun handleLocked(envelope: RelayClient.Envelope): Boolean {
        if (!store.markEnvelopeSeen(envelope.id, envelope.ts)) return true
        val decrypted = try {
            identities.update { it.decrypt(envelope.data) }
        } catch (e: CryptoException.NoSessionFor) {
            store.contactByCurve(e.senderCurve25519)?.let { contact ->
                Log.w(TAG, "no session for ${contact.number}; asking for a new one")
                identities.update { it.dropSessions(contact.curve25519) }
                sendControl(contact, JSONObject().put("v", 1).put("t", "resync"))
            }
            return true
        } catch (e: CryptoException) {
            Log.w(TAG, "undecryptable envelope ${envelope.id}: ${e.message}")
            return true
        }
        val json = runCatching { JSONObject(String(decrypted.plaintext, Charsets.UTF_8)) }.getOrNull() ?: return true
        if (json.optInt("v", 0) != 1) return true

        val contact = resolveSender(decrypted.senderCurve25519, json) ?: return true
        when (json.optString("t")) {
            "msg" -> {
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return true
                val body = json.optString("body").take(MAX_BODY)
                if (store.hasMessage(id)) return true
                val ts = json.optLong("ts", envelope.ts).coerceIn(envelope.ts - 7L * 24 * 3600_000L, System.currentTimeMillis() + 60_000L)
                val onScreen = openPeer == contact.number
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, body, ts, "received", read = onScreen))
                sendControl(contact, JSONObject().put("v", 1).put("t", "receipt").put("status", if (onScreen) "read" else "delivered").put("ids", JSONArray(listOf(id))))
                if (!onScreen) {
                    val fresh = store.messages(contact.number).filter { it.direction == Direction.IN && !it.read }
                    CommsNotifications.notifyInbound(appContext, contact, fresh)
                }
            }
            "receipt" -> {
                val ids = json.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val status = json.optString("status")
                if (status == "delivered" || status == "read") {
                    // Only our own messages to this contact can be receipted by them.
                    val mine = store.messages(contact.number).filter { it.direction == Direction.OUT }.map { it.id }.toSet()
                    store.advanceStatus(ids.filter { it in mine }, status)
                }
            }
            "resync" -> {
                // Their pre-key message has already created a fresh session on our
                // side; anything still queued for them goes through it next flush.
                Log.i(TAG, "resync from ${contact.number}")
            }
        }
        return true
    }

    /**
     * Maps the Olm sender key to a contact. A first message from an unknown
     * identity carries its keys and Aegis number; those are pinned as an
     * unverified contact once the relay confirms that number publishes exactly
     * those keys, so nobody can send as a number they do not hold.
     */
    private fun resolveSender(senderCurve: String, json: JSONObject): Contact? {
        store.contactByCurve(senderCurve)?.let { return it }
        val from = parseAegisNumber(json.optString("from")) ?: return null
        val ed = json.optString("k"); val curve = json.optString("c"); val sealing = json.optString("s"); val sig = json.optString("g")
        if (curve != senderCurve || ed.isBlank() || sealing.isBlank() || sig.isBlank()) return null
        if (!verify(ed, "$curve|$sealing".toByteArray(Charsets.UTF_8), sig)) return null
        val relayView = runCatching { relay.bundle(from, fingerprint(ed)) }.getOrElse { e ->
            Log.w(TAG, "could not confirm sender $from with the relay: ${e.message}")
            return null
        }
        if (relayView.ed25519 != ed || relayView.curve25519 != curve || relayView.sealing != sealing) return null
        val existing = store.contact(from)
        val contact = if (existing != null) {
            // Same number, different keys: keep the pinned name, flag it, trust nothing.
            identities.update { it.dropSessions(existing.curve25519) }
            existing.copy(ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig, verified = false, keyChanged = true)
        } else {
            Contact(
                number = from, name = json.optString("name").trim().take(40),
                ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig,
                verified = false, addedTs = System.currentTimeMillis()
            )
        }
        store.upsertContact(contact)
        return contact
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /** Pulls the inbox, sends what is queued and tops up one-time keys. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext Result.success(Unit)
        setBusy(true)
        try {
            val envelopes = relay.inbox()
            val done = ArrayList<String>()
            for (env in envelopes) if (lock.withLock { handleLocked(env) }) done += env.id
            if (done.isNotEmpty()) relay.ack(done)
            flushOutbox()
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

    /** Publishes more one-time keys when the relay is running low. */
    private suspend fun replenishKeys() {
        lock.withLock {
            val me = relay.me()
            var count = me.oneTimeKeys
            if (count < ONE_TIME_KEYS_LOW) {
                val bundle = identities.update { id ->
                    id.generateOneTimeKeys(ONE_TIME_KEYS_BATCH.toUInt())
                    id.publicBundle()
                }
                count = relay.putKeys(bundle)
                identities.update { it.markKeysPublished() }
            }
            if (me.listed != config.listed) config.setListed(me.listed)
            _state.update { it.copy(relayOneTimeKeys = count, listed = me.listed) }
        }
    }

    /** Marks a conversation read and tells the sender. */
    suspend fun markRead(peer: String) = withContext(Dispatchers.IO) {
        val ids = store.markRead(peer)
        if (ids.isEmpty()) return@withContext
        bump()
        val contact = store.contact(peer) ?: return@withContext
        lock.withLock {
            sendControl(contact, JSONObject().put("v", 1).put("t", "receipt").put("status", "read").put("ids", JSONArray(ids)))
        }
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
