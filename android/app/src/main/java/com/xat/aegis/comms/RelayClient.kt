package com.xat.aegis.comms

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.TimeUnit

/** A relay call that did not succeed, with the relay's message when it gave one. */
class RelayException(message: String, val code: Int = 0) : IOException(message)

/**
 * HTTP and WebSocket client for the relay Worker. Every request is signed with
 * the identity's Ed25519 key (see comms-worker/src/auth.ts); nothing else
 * authenticates a device. Calls are blocking and belong on an IO dispatcher.
 */
class RelayClient(private val identities: IdentityStore, private val config: CommsConfig) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        // Signed requests carry identity headers and the registration body carries
        // the enrollment secret; neither may be re-sent to wherever a redirect points.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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

    /** Registers a fresh identity and returns the Aegis number the relay allocated. */
    fun register(relayUrl: String, identity: Identity, secret: String, listed: Boolean): Registered {
        val bundle = identity.publicBundle()
        val body = JSONObject()
            .put("secret", secret)
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

    fun send(to: String, envelope: ByteArray): String {
        val body = JSONObject().put("to", to).put("envelope", Base64.encodeToString(envelope, Base64.NO_WRAP))
        val json = signed("POST", "/v1/send", body)
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

    /** Opens the live-delivery socket. The caller owns the returned socket. */
    fun openSocket(listener: WebSocketListener): WebSocket {
        val request = signedRequest("GET", "/v1/ws", null).build()
        return http.newWebSocket(request, listener)
    }

    fun parseEnvelope(o: JSONObject): Envelope = parsed {
        Envelope(o.getString("id"), o.getLong("ts"), Base64.decode(o.getString("data"), Base64.NO_WRAP))
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
        val relay = config.relayUrl ?: throw RelayException("Not registered with a relay")
        val number = config.number ?: throw RelayException("Not registered with a relay")
        val identity = identities.get() ?: throw RelayException("No identity on this device")
        val bodyText = body?.toString() ?: ""
        val ts = System.currentTimeMillis()
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        val payload = "$number\n$ts\n$nonce\n${method.uppercase()}\n$pathAndQuery\n${sha256Hex(bodyText)}"
        val builder = Request.Builder()
            .url(relay + pathAndQuery)
            .header("X-Aegis-Number", number)
            .header("X-Aegis-Ts", ts.toString())
            .header("X-Aegis-Nonce", nonce)
            .header("X-Aegis-Sig", identity.sign(payload.toByteArray(Charsets.UTF_8)))
            .header("Accept", "application/json")
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PUT" -> builder.put(bodyText.toRequestBody(JSON))
            else -> builder.post(bodyText.toRequestBody(JSON))
        }
        return builder
    }

    private fun execute(builder: Request.Builder): JSONObject {
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw RelayException("Could not reach the relay: ${e.message ?: e.javaClass.simpleName}")
        }
        response.use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!res.isSuccessful) {
                val reason = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${res.code}"
                throw RelayException(reason, res.code)
            }
            return json ?: throw RelayException("The relay returned something that is not JSON")
        }
    }

    private fun signedKeyJson(k: SignedKey) = JSONObject().put("id", k.id).put("key", k.key).put("signature", k.signature)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        /** [RelayException.code] for a response the client could not parse; not a network failure. */
        const val MALFORMED = -1
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
