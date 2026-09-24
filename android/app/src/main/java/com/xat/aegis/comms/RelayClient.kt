package com.xat.aegis.comms

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.aegis_comms_crypto.Identity
import uniffi.aegis_comms_crypto.PublicBundle
import uniffi.aegis_comms_crypto.SignedKey
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A relay call that did not succeed, with the relay's message when it gave one. */
class RelayException(message: String, val code: Int = 0) : IOException(message)

/**
 * HTTP and WebSocket client for the relay Worker. Every request is signed with
 * the identity's Ed25519 key (see comms-worker/src/auth.ts); nothing else
 * authenticates a device. Calls are blocking and belong on an IO dispatcher.
 *
 * It depends on nothing Android-specific, so the relay round-trip tests run it
 * on the JVM against a real relay; the app hands it its identity, relay URL and
 * Aegis number as lookups, since all three change on registration.
 */
class RelayClient(
    private val identity: () -> Identity?,
    private val relayUrl: () -> String?,
    private val number: () -> String?,
) {

    /** What a signed request signs. The signature headers themselves are added per attempt by [signer]. */
    private class Signing(val method: String, val pathAndQuery: String, val bodyText: String)

    /**
     * Signs every network attempt afresh. OkHttp retries a request on a new
     * connection when the first one broke (a stale pooled connection, a
     * network switch mid-request). The relay accepts each nonce once, so a
     * retry that reused the first attempt's signature was refused with 401,
     * and a call offer that had in fact been delivered was reported as failed.
     * With a fresh nonce the retry goes through; a duplicate envelope is
     * dropped by the receiving phone, and duplicate keys by the relay.
     */
    private val signer = Interceptor { chain ->
        val request = chain.request()
        val signing = request.tag(Signing::class.java) ?: return@Interceptor chain.proceed(request)
        chain.proceed(sign(request.newBuilder(), signing).build())
    }

    /** The live socket's client: no overall call timeout, which would cut the socket itself. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        // Signed requests carry identity headers and the registration body carries
        // the enrollment secret; neither may be re-sent to wherever a redirect points.
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(signer)
        .build()

    /**
     * Requests: bounded as a whole, so one stalled request (Wi-Fi that lost
     * its internet, say) cannot hold up everything queued behind it.
     */
    private val rest: OkHttpClient by lazy { http.newBuilder().pingInterval(0, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build() }

    /** Sending an envelope: a call's signals must fail fast enough to be retried within the call. */
    private val sender: OkHttpClient by lazy { rest.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }

    data class Registered(val number: String)

    data class Bundle(
        val number: String,
        val ed25519: String,
        val curve25519: String,
        val sealing: String,
        val signature: String,
        /** A one-time key, or the fallback when none is left. */
        val sessionKey: SignedKey
    )

    data class Envelope(val id: String, val ts: Long, val data: ByteArray)

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Registers a fresh identity and returns the Aegis number the relay
     * allocated. Admission is either the relay's enrollment [secret] or a
     * one-time [invite] code from someone already registered.
     */
    fun register(relayUrl: String, identity: Identity, secret: String?, listed: Boolean, invite: String? = null): Registered {
        val bundle = identity.publicBundle()
        val body = JSONObject()
            .apply { if (invite != null) put("invite", invite) else put("secret", secret ?: "") }
            .put("ed25519", bundle.ed25519)
            .put("curve25519", bundle.curve25519)
            .put("sealing", bundle.sealing)
            .put("signature", bundle.signature)
            .put("fallback", bundle.fallback?.let { signedKeyJson(it) } ?: JSONObject.NULL)
            .put("oneTimeKeys", JSONArray(bundle.oneTimeKeys.map { signedKeyJson(it) }))
            .put("listed", listed)
            .toString()
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/register")
            .header("X-Aegis-Sig", identity.sign(body.toByteArray(Charsets.UTF_8)))
            .post(body.toRequestBody(JSON))
        val json = execute(request)
        return parsed { Registered(json.getString("number")) }
    }

    // ── Own mailbox ───────────────────────────────────────────────────────

    data class Me(val listed: Boolean, val oneTimeKeys: Int)

    fun me(): Me {
        val json = signed("GET", "/v1/me")
        return parsed { Me(json.getJSONObject("profile").optBoolean("listed", true), json.optInt("oneTimeKeys", 0)) }
    }

    fun putKeys(bundle: PublicBundle): Int {
        val body = JSONObject()
            .put("oneTimeKeys", JSONArray(bundle.oneTimeKeys.map { signedKeyJson(it) }))
            .put("fallback", bundle.fallback?.let { signedKeyJson(it) } ?: JSONObject.NULL)
        return signed("PUT", "/v1/keys", body).optInt("oneTimeKeys", 0)
    }

    data class Invite(val code: String, val expiresAt: Long)

    /** A one-time invite: lets one person register on this relay without the enrollment secret, for a week. */
    fun createInvite(): Invite {
        val json = signed("POST", "/v1/invites")
        return parsed { Invite(json.getString("code"), json.getLong("expiresAt")) }
    }

    fun setListed(listed: Boolean) { signed("PUT", "/v1/listed", JSONObject().put("listed", listed)) }

    fun setPush(endpoint: String?) { signed("PUT", "/v1/push", JSONObject().put("endpoint", endpoint ?: JSONObject.NULL)) }

    fun wipe() { signed("DELETE", "/v1/me") }

    // ── Contacts and envelopes ────────────────────────────────────────────

    /** A contact's keys plus one key to start a session; [pin] unlocks an unlisted number. */
    fun bundle(number: String, pin: String?): Bundle {
        val path = "/v1/bundle/$number" + (pin?.let { "?pin=$it" } ?: "")
        val json = signed("GET", path)
        return parsed {
            val b = json.getJSONObject("bundle")
            val key = b.optJSONObject("oneTimeKey") ?: b.optJSONObject("fallback")
                ?: throw RelayException("The relay has no session key for that number", MALFORMED)
            Bundle(
                number = b.getString("number"),
                ed25519 = b.getString("ed25519"),
                curve25519 = b.getString("curve25519"),
                sealing = b.getString("sealing"),
                signature = b.getString("signature"),
                sessionKey = SignedKey(key.getString("id"), key.getString("key"), key.getString("signature"))
            )
        }
    }

    /** A contact's public identity keys, without the one-time key a bundle claims. */
    data class IdentityKeys(val number: String, val ed25519: String, val curve25519: String, val sealing: String, val signature: String)

    /** Who holds [number] (unlisted numbers need their key's [pin]); claims none of their keys. */
    fun identity(number: String, pin: String?): IdentityKeys {
        val path = "/v1/identity/$number" + (pin?.let { "?pin=$it" } ?: "")
        val json = signed("GET", path)
        return parsed {
            val o = json.getJSONObject("identity")
            IdentityKeys(o.getString("number"), o.getString("ed25519"), o.getString("curve25519"), o.getString("sealing"), o.getString("signature"))
        }
    }

    fun send(to: String, envelope: ByteArray): String {
        val body = JSONObject().put("to", to).put("envelope", Base64.getEncoder().encodeToString(envelope))
        val json = execute(signedRequest("POST", "/v1/send", body), sender)
        return parsed { json.getString("id") }
    }

    fun inbox(): List<Envelope> {
        val json = signed("GET", "/v1/inbox")
        return parsed {
            val arr = json.getJSONArray("envelopes")
            (0 until arr.length()).map { parseEnvelope(arr.getJSONObject(it)) }
        }
    }

    fun ack(ids: List<String>) {
        if (ids.isEmpty()) return
        signed("POST", "/v1/ack", JSONObject().put("ids", JSONArray(ids)))
    }

    /** One STUN or TURN server for a call, as the relay hands it out. */
    data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

    /** The last ICE server list the relay gave, reused when a fetch is slow or fails. */
    @Volatile
    private var lastIceServers: Pair<Long, List<IceServer>>? = null

    /** A client that gives up quickly: a call should not wait 15 s on a slow relay for its server list. */
    private val quick: OkHttpClient by lazy { rest.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    /**
     * ICE servers for a call: STUN always, TURN with short-lived credentials when
     * the relay has a key. A list fetched in the last few hours is reused if the
     * relay does not answer within four seconds.
     */
    fun turn(): List<IceServer> {
        val cached = lastIceServers?.takeIf { System.currentTimeMillis() - it.first < ICE_CACHE_MS }?.second
        val json = try {
            execute(signedRequest("GET", "/v1/turn", null), quick)
        } catch (e: RelayException) {
            if (cached != null) return cached
            throw e
        }
        val servers = parsed {
            val arr = json.getJSONArray("iceServers")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val urls = o.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.getString(it) } } ?: return@mapNotNull null
                IceServer(urls, o.optString("username").takeIf { it.isNotEmpty() }, o.optString("credential").takeIf { it.isNotEmpty() })
            }
        }
        if (servers.isNotEmpty()) lastIceServers = System.currentTimeMillis() to servers
        return servers
    }

    /** Opens the live-delivery socket. The caller owns the returned socket. */
    fun openSocket(listener: WebSocketListener): WebSocket {
        // OkHttp runs no network interceptors for a WebSocket upgrade, so it is
        // signed here, once; a refused upgrade is retried by the caller.
        val builder = signedRequest("GET", "/v1/ws", null)
        val signing = builder.build().tag(Signing::class.java) ?: throw RelayException("Not registered with a relay")
        return http.newWebSocket(sign(builder, signing).build(), listener)
    }

    fun parseEnvelope(o: JSONObject): Envelope = parsed {
        Envelope(o.getString("id"), o.getLong("ts"), Base64.getDecoder().decode(o.getString("data")))
    }

    /**
     * Runs response parsing, turning a missing or malformed field into a
     * [RelayException] with [MALFORMED] so a version mismatch or a hostile relay
     * fails the one call instead of crashing the process.
     */
    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw RelayException("The relay returned an unexpected response: ${e.message}", MALFORMED)
    } catch (e: IllegalArgumentException) {
        throw RelayException("The relay returned an unexpected response: ${e.message}", MALFORMED)
    }

    // ── Signing ───────────────────────────────────────────────────────────

    private fun signed(method: String, pathAndQuery: String, body: JSONObject? = null): JSONObject =
        execute(signedRequest(method, pathAndQuery, body))

    private fun signedRequest(method: String, pathAndQuery: String, body: JSONObject?): Request.Builder {
        val relay = relayUrl() ?: throw RelayException("Not registered with a relay")
        val number = number() ?: throw RelayException("Not registered with a relay")
        if (identity() == null) throw RelayException("No identity on this device")
        val bodyText = body?.toString() ?: ""
        val builder = Request.Builder()
            .url(relay + pathAndQuery)
            .header("X-Aegis-Number", number)
            .header("Accept", "application/json")
            .tag(Signing::class.java, Signing(method.uppercase(), pathAndQuery, bodyText))
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PUT" -> builder.put(bodyText.toRequestBody(JSON))
            else -> builder.post(bodyText.toRequestBody(JSON))
        }
        return builder
    }

    /** Adds the signature headers for one attempt: a fresh nonce and the relay's current time. */
    private fun sign(builder: Request.Builder, s: Signing): Request.Builder {
        val number = number() ?: throw RelayException("Not registered with a relay")
        val identity = identity() ?: throw RelayException("No identity on this device")
        // The relay rejects a timestamp more than five minutes from its own clock,
        // so a phone whose clock is off signs with the relay's time once it knows it.
        val ts = relayNow()
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val payload = "$number\n$ts\n$nonce\n${s.method}\n${s.pathAndQuery}\n${sha256Hex(s.bodyText)}"
        return builder
            .header("X-Aegis-Number", number)
            .header("X-Aegis-Ts", ts.toString())
            .header("X-Aegis-Nonce", nonce)
            .header("X-Aegis-Sig", identity.sign(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun execute(builder: Request.Builder, client: OkHttpClient = rest): JSONObject {
        // Every network failure, including one while reading the response body
        // (a network switch mid-request), surfaces as a RelayException with code
        // 0, which callers treat as "offline, retry later".
        try {
            client.newCall(builder.build()).execute().use { res ->
                noteServerTime(res)
                val text = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!res.isSuccessful) {
                    val reason = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${res.code}"
                    throw RelayException(reason, res.code)
                }
                return json ?: throw RelayException("The relay returned something that is not JSON", MALFORMED)
            }
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException("Could not reach the relay: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── Relay clock ───────────────────────────────────────────────────────

    /** Relay time minus this phone's time, learned from the `Date` header of every response. */
    @Volatile
    private var clockOffsetMs: Long = 0L

    /**
     * The current time by the relay's clock. Envelope timestamps are the relay's,
     * so anything that measures an envelope's age (a call offer that waited too
     * long to ring) compares against this rather than the phone's own clock,
     * which may be minutes off.
     */
    fun relayNow(): Long = System.currentTimeMillis() + clockOffsetMs

    /**
     * Records the relay's clock from [response]; called for every HTTP and
     * socket response. Returns true when that moved this phone's idea of the
     * relay's time by more than half a minute.
     */
    fun noteServerTime(response: Response): Boolean {
        val server = response.headers.getDate("Date")?.time ?: return false
        // The header has one-second resolution; half a second centres the error.
        val offset = server + 500L - System.currentTimeMillis()
        val previous = clockOffsetMs
        clockOffsetMs = offset
        val moved = kotlin.math.abs(offset - previous) > CLOCK_WARN_MS
        if (kotlin.math.abs(offset) > CLOCK_WARN_MS && moved) {
            CommsLog.add("This phone's clock is ${offset / 1000}s off the relay's; using the relay's time")
        }
        return moved
    }

    private fun signedKeyJson(k: SignedKey) = JSONObject().put("id", k.id).put("key", k.key).put("signature", k.signature)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        /** [RelayException.code] for a response the client could not parse; not a network failure. */
        const val MALFORMED = -1
        /** Envelopes the relay returns per inbox fetch (Mailbox.inbox's limit). */
        const val INBOX_PAGE = 200
        private const val CLOCK_WARN_MS = 30_000L
        /** The relay mints TURN credentials for 24 h and hands them out for at most 6 h. */
        private const val ICE_CACHE_MS = 5 * 60 * 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
