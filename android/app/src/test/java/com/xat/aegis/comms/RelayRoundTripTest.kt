package com.xat.aegis.comms

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import uniffi.aegis_comms_crypto.CryptoException
import uniffi.aegis_comms_crypto.Identity
import uniffi.aegis_comms_crypto.PeerKeys
import uniffi.aegis_comms_crypto.fingerprint
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Two simulated phones talking through a real relay, using the app's own
 * RelayClient, the real Rust crypto library and the app's wire format.
 *
 * Each phone registers a fresh unlisted identity, keeps the live WebSocket
 * open the way LiveLink does, and acks what it receives. Both mailboxes are
 * wiped at the end. Runs only when AEGIS_RELAY_URL and AEGIS_ENROLL_SECRET
 * are set in the environment; skipped otherwise (in CI, for example).
 */
class RelayRoundTripTest {

    private val relayUrl: String? = System.getenv("AEGIS_RELAY_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    private val secret: String? = System.getenv("AEGIS_ENROLL_SECRET")?.trim()?.takeIf { it.isNotEmpty() }

    private lateinit var alice: Phone
    private lateinit var bob: Phone

    /** A phone as the relay and the other phone see it. */
    private class Phone(private val relayUrl: String, secret: String, val label: String) {
        val identity: Identity = Identity.create().also { it.generateOneTimeKeys(20u) }
        @Volatile var number: String? = null
        val relay = RelayClient({ identity }, { relayUrl }, { number })
        private val inbox = LinkedBlockingQueue<RelayClient.Envelope>()
        private var socket: WebSocket? = null

        init {
            number = relay.register(relayUrl, identity, secret, listed = false).number
            identity.markKeysPublished()
        }

        val keys: PeerKeys
            get() = identity.publicBundle().let { PeerKeys(it.ed25519, it.curve25519, it.sealing, it.signature) }

        private val sender: CommsWire.Sender
            get() = identity.publicBundle().let { CommsWire.Sender(number!!, label, it.ed25519, it.curve25519, it.sealing, it.signature) }

        /** Opens the live socket and waits for the relay's "ready" after any backlog. */
        fun connect() {
            val ready = CountDownLatch(1)
            socket = relay.openSocket(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "envelope" -> {
                            val env = relay.parseEnvelope(json.getJSONObject("envelope"))
                            inbox.put(env)
                            webSocket.send(JSONObject().put("type", "ack").put("ids", JSONArray(listOf(env.id))).toString())
                        }
                        "ready" -> ready.countDown()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    System.err.println("$label socket failed: ${t.message}")
                }
            })
            assertTrue("$label: relay socket never became ready", ready.await(20, TimeUnit.SECONDS))
        }

        /** Encrypts [payload] for [peer] exactly as CommsRepository.deliver does, starting a session if needed. */
        fun send(peer: Phone, payload: JSONObject) {
            val peerKeys = peer.keys
            if (!identity.hasSession(peerKeys.curve25519)) {
                val bundle = relay.bundle(peer.number!!, fingerprint(peerKeys.ed25519))
                assertEquals(peerKeys.curve25519, bundle.curve25519)
                identity.startSession(peerKeys, bundle.sessionKey)
            }
            val plaintext = CommsWire.withSender(payload, sender).toString().toByteArray(Charsets.UTF_8)
            relay.send(peer.number!!, identity.encrypt(peerKeys, plaintext))
        }

        /** What CommsRepository.recoverSession does: a new outbound session, first in the list, old ones kept. */
        fun startFreshSession(peer: Phone) {
            val peerKeys = peer.keys
            val bundle = relay.bundle(peer.number!!, fingerprint(peerKeys.ed25519))
            identity.startSession(peerKeys, bundle.sessionKey)
        }

        /** Posts already-encrypted bytes, as a relay redelivery or a re-send would. */
        fun sendRaw(peer: Phone, envelope: ByteArray) = relay.send(peer.number!!, envelope)

        fun encryptFor(peer: Phone, payload: JSONObject): ByteArray {
            val peerKeys = peer.keys
            if (!identity.hasSession(peerKeys.curve25519)) startFreshSession(peer)
            return identity.encrypt(peerKeys, CommsWire.withSender(payload, sender).toString().toByteArray(Charsets.UTF_8))
        }

        class Received(val senderCurve: String, val payload: JSONObject, val newSession: Boolean)

        /** The next envelope off the live socket, decrypted and parsed. */
        fun receive(): Received {
            val env = inbox.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("$label: nothing arrived within 20 s")
            val d = identity.decrypt(env.data)
            val payload = CommsWire.parse(String(d.plaintext, Charsets.UTF_8)) ?: throw AssertionError("$label: payload did not parse")
            return Received(d.senderCurve25519, payload, d.newSession)
        }

        /** The next envelope, expected to fail decryption. */
        fun receiveUndecryptable(): CryptoException {
            val env = inbox.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("$label: nothing arrived within 20 s")
            try {
                identity.decrypt(env.data)
            } catch (e: CryptoException) {
                return e
            }
            throw AssertionError("$label: the envelope decrypted")
        }

        fun close() {
            socket?.close(1000, "test over")
            runCatching { relay.wipe() }
        }
    }

    @Before
    fun setUp() {
        assumeTrue("set AEGIS_RELAY_URL and AEGIS_ENROLL_SECRET to run the relay round-trip tests", relayUrl != null && secret != null)
        alice = Phone(relayUrl!!, secret!!, "Alice")
        bob = Phone(relayUrl, secret, "Bob")
        alice.connect()
        bob.connect()
    }

    @After
    fun tearDown() {
        if (::alice.isInitialized) alice.close()
        if (::bob.isInitialized) bob.close()
    }

    private fun msg(body: String) = CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), body)

    @Test
    fun severalMessagesBeforeAnyReplyAllArriveAndReceiptsComeBack() {
        val ids = (1..5).map { i -> msg("hello $i").also { alice.send(bob, it) }.getString(CommsWire.F_ID) }
        for ((i, id) in ids.withIndex()) {
            val got = bob.receive()
            assertEquals(alice.keys.curve25519, got.senderCurve)
            assertEquals(CommsWire.T_MSG, CommsWire.type(got.payload))
            assertEquals(id, got.payload.getString(CommsWire.F_ID))
            assertEquals("hello ${i + 1}", got.payload.getString(CommsWire.F_BODY))
            assertEquals(alice.number, CommsWire.senderOf(got.payload)?.number)
        }
        bob.send(alice, CommsWire.receipt(ids, CommsWire.STATUS_DELIVERED))
        val receipt = alice.receive()
        assertEquals(CommsWire.T_RECEIPT, CommsWire.type(receipt.payload))
        assertEquals(ids, CommsWire.receiptIds(receipt.payload))
        // And the conversation keeps going in both directions afterwards.
        repeat(4) { round ->
            alice.send(bob, msg("a$round")); assertEquals("a$round", bob.receive().payload.getString(CommsWire.F_BODY))
            bob.send(alice, msg("b$round")); assertEquals("b$round", alice.receive().payload.getString(CommsWire.F_BODY))
        }
    }

    @Test
    fun callSignalsArriveIntactInBothDirections() {
        val cid = UUID.randomUUID().toString()
        val offerSdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n" + "a=fingerprint:sha-256 AB:CD\r\n".repeat(150)
        alice.send(bob, CommsWire.callOffer(cid, System.currentTimeMillis(), offerSdp))
        alice.send(bob, CommsWire.callIce(cid, listOf(CommsWire.Candidate("0", 0, "candidate:1 1 udp 2122260223 10.0.0.2 50000 typ host"))))

        val offer = bob.receive().payload
        assertEquals(CommsWire.T_CALL, CommsWire.type(offer))
        assertEquals(CommsWire.CALL_OFFER, CommsWire.callKind(offer))
        assertEquals(cid, offer.getString(CommsWire.F_CALL_ID))
        assertEquals(offerSdp, offer.getString(CommsWire.F_SDP))
        val ice = bob.receive().payload
        assertEquals(CommsWire.CALL_ICE, CommsWire.callKind(ice))
        assertEquals(1, CommsWire.candidates(ice).size)

        bob.send(alice, CommsWire.callAnswer(cid, "v=0 answer"))
        bob.send(alice, CommsWire.callIce(cid, listOf(CommsWire.Candidate("0", 0, "candidate:2 1 udp 1686052607 198.51.100.4 40000 typ srflx"))))
        val answer = alice.receive().payload
        assertEquals(CommsWire.CALL_ANSWER, CommsWire.callKind(answer))
        assertEquals("v=0 answer", answer.getString(CommsWire.F_SDP))
        assertEquals(CommsWire.CALL_ICE, CommsWire.callKind(alice.receive().payload))

        alice.send(bob, CommsWire.callEnd(cid, "hangup"))
        val end = bob.receive().payload
        assertEquals(CommsWire.CALL_END, CommsWire.callKind(end))
        assertEquals("hangup", end.getString(CommsWire.F_REASON))
    }

    @Test
    fun bothPhonesStartingSessionsAtOnceStillConverge() {
        // Each adds the other and sends first, before anything has arrived.
        alice.send(bob, msg("from alice first"))
        bob.send(alice, msg("from bob first"))
        assertEquals("from alice first", bob.receive().payload.getString(CommsWire.F_BODY))
        assertEquals("from bob first", alice.receive().payload.getString(CommsWire.F_BODY))
        repeat(6) { round ->
            alice.send(bob, msg("a$round")); assertEquals("a$round", bob.receive().payload.getString(CommsWire.F_BODY))
            bob.send(alice, msg("b$round")); assertEquals("b$round", alice.receive().payload.getString(CommsWire.F_BODY))
        }
    }

    @Test
    fun aLostSessionRecoversThroughResync() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()

        // Bob loses his session (as recoverSession does after a failure).
        bob.identity.dropSessions(alice.keys.curve25519)
        alice.send(bob, msg("lost"))
        val failure = bob.receiveUndecryptable()
        assertTrue("expected NoSessionFor, got $failure", failure is CryptoException.NoSessionFor)
        assertEquals(alice.keys.curve25519, (failure as CryptoException.NoSessionFor).senderCurve25519)

        // Bob asks for a new session; the resync itself starts one.
        bob.send(alice, CommsWire.resync())
        val resync = alice.receive()
        assertEquals(CommsWire.T_RESYNC, CommsWire.type(resync.payload))
        assertTrue("the resync should arrive on a new session", resync.newSession)

        // From here on both directions work again.
        alice.send(bob, msg("after")); assertEquals("after", bob.receive().payload.getString(CommsWire.F_BODY))
        bob.send(alice, msg("reply")); assertEquals("reply", alice.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun messagesInFlightDuringARecoveryStillArrive() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()

        // Alice sends two more on the current session...
        alice.send(bob, msg("in flight 1"))
        alice.send(bob, msg("in flight 2"))
        // ...while Bob, reacting to some unreadable envelope, starts a new session
        // and sends a resync on it, keeping his old sessions (the v2.3 recovery).
        bob.startFreshSession(alice)
        bob.send(alice, CommsWire.resync())

        // Bob still reads what Alice sent on the old session.
        assertEquals("in flight 1", bob.receive().payload.getString(CommsWire.F_BODY))
        assertEquals("in flight 2", bob.receive().payload.getString(CommsWire.F_BODY))
        // Alice takes the resync on a new session and from then on uses it.
        val resync = alice.receive()
        assertEquals(CommsWire.T_RESYNC, CommsWire.type(resync.payload))
        assertTrue(resync.newSession)
        alice.send(bob, msg("after")); assertEquals("after", bob.receive().payload.getString(CommsWire.F_BODY))
        bob.send(alice, msg("reply")); assertEquals("reply", alice.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun aRedeliveredEnvelopeIsADuplicateAndHarmsNothing() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()
        val envelope = alice.encryptFor(bob, msg("twice"))
        alice.sendRaw(bob, envelope)
        alice.sendRaw(bob, envelope)
        assertEquals("twice", bob.receive().payload.getString(CommsWire.F_BODY))
        val second = bob.receiveUndecryptable()
        assertTrue("expected Duplicate, got $second", second is CryptoException.Duplicate)
        alice.send(bob, msg("still fine")); assertEquals("still fine", bob.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun identityLookupConfirmsKeysWithoutUsingAOneTimeKey() {
        val before = bob.relay.me().oneTimeKeys
        val id = alice.relay.identity(bob.number!!, fingerprint(bob.keys.ed25519))
        assertEquals(bob.keys.curve25519, id.curve25519)
        assertEquals(bob.keys.ed25519, id.ed25519)
        assertEquals(before, bob.relay.me().oneTimeKeys)
        // Unlisted, so a lookup without the pin is refused.
        try {
            alice.relay.identity(bob.number!!, null)
            fail("an unlisted identity was handed out without its pin")
        } catch (e: RelayException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun aClaimedOneTimeKeyIsNeverOfferedAgain() {
        // Take one key, then try to publish it again: the relay must refuse to re-add it.
        val bundle = alice.relay.bundle(bob.number!!, fingerprint(bob.keys.ed25519))
        val after = bob.relay.me().oneTimeKeys
        val again = bob.identity.publicBundle().let {
            uniffi.aegis_comms_crypto.PublicBundle(it.ed25519, it.curve25519, it.sealing, it.signature, null, listOf(bundle.sessionKey))
        }
        assertEquals(after, bob.relay.putKeys(again))
    }

    @Test
    fun relayClockIsLearnedFromResponses() {
        alice.relay.me()
        val skew = alice.relay.relayNow() - System.currentTimeMillis()
        assertTrue("relay clock offset $skew ms is implausible for a synced clock", kotlin.math.abs(skew) < 5_000)
        assertNotNull(alice.number)
    }

    @Test
    fun aSecondSocketForTheSameNumberStillGetsEnvelopes() {
        // LiveLink can briefly hold two sockets across a reconnect; neither may swallow envelopes.
        bob.connect()
        alice.send(bob, msg("to both sockets"))
        val first = bob.receive()
        assertEquals("to both sockets", first.payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun unknownVersionPayloadIsNotMistakenForAMessage() {
        val raw = JSONObject().put(CommsWire.F_VERSION, 99).put(CommsWire.F_TYPE, CommsWire.T_MSG)
        val plaintext = raw.toString().toByteArray()
        if (!alice.identity.hasSession(bob.keys.curve25519)) {
            val bundle = alice.relay.bundle(bob.number!!, fingerprint(bob.keys.ed25519))
            alice.identity.startSession(bob.keys, bundle.sessionKey)
        }
        alice.relay.send(bob.number!!, alice.identity.encrypt(bob.keys, plaintext))
        try {
            bob.receive()
            fail("a version-99 payload parsed")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains("did not parse"))
        }
    }
}
