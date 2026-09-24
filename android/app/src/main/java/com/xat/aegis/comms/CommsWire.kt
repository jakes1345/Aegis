package com.xat.aegis.comms

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plaintext format inside every envelope: one place that names every
 * field, builds every payload and reads it back.
 *
 * Payloads used to be assembled by hand in several files. A call signal put
 * its kind in "k", which is also where the sender's identity key goes, so the
 * key overwrote the kind and no call offer was ever recognised. Everything now
 * goes through these builders, [withSender] refuses to overwrite a field, and
 * the unit tests round-trip every payload type through it.
 *
 * All payloads are JSON objects with:
 *   "v": 1, "t": type,
 *   the sender's identity (added by [withSender]):
 *   "from": Aegis number, "name": display name,
 *   "k": Ed25519 key, "c": Curve25519 key, "s": sealing key, "g": signature over "c|s"
 * and per type:
 *   msg      "id", "ts", "body"
 *   receipt  "status" ("delivered" | "read"), "ids": [message ids]
 *   resync   nothing more; its pre-key envelope starts a fresh session
 *   call     "ck": offer | answer | ice | reoffer | end, "cid": call id, and
 *            offer "ts", "sdp" · answer "sdp" · reoffer "sdp" · ice "cands": [{"m","i","c"}] · end "reason"
 *
 * Pure Kotlin and org.json, so the JVM tests use exactly this code.
 */
object CommsWire {

    const val VERSION = 1

    const val F_VERSION = "v"
    const val F_TYPE = "t"

    // The sender's identity, present in every payload.
    const val F_FROM = "from"
    const val F_NAME = "name"
    const val F_ED25519 = "k"
    const val F_CURVE25519 = "c"
    const val F_SEALING = "s"
    const val F_SIGNATURE = "g"
    val SENDER_FIELDS = setOf(F_FROM, F_NAME, F_ED25519, F_CURVE25519, F_SEALING, F_SIGNATURE)

    const val T_MSG = "msg"
    const val T_RECEIPT = "receipt"
    const val T_RESYNC = "resync"
    const val T_CALL = "call"

    // msg
    const val F_ID = "id"
    const val F_TS = "ts"
    const val F_BODY = "body"

    // receipt
    const val F_STATUS = "status"
    const val F_IDS = "ids"
    const val STATUS_DELIVERED = "delivered"
    const val STATUS_READ = "read"

    // call
    const val F_CALL_KIND = "ck"
    const val F_CALL_ID = "cid"
    const val F_SDP = "sdp"
    const val F_CANDIDATES = "cands"
    const val F_REASON = "reason"
    const val CALL_OFFER = "offer"
    const val CALL_ANSWER = "answer"
    const val CALL_ICE = "ice"
    const val CALL_END = "end"
    /** A fresh offer for a call already under way (ICE restart after a network change); answered with "answer". */
    const val CALL_REOFFER = "reoffer"
    const val END_HANGUP = "hangup"
    const val END_CANCEL = "cancel"
    const val END_REJECT = "reject"
    const val END_BUSY = "busy"

    // One ICE candidate inside "cands".
    private const val C_MID = "m"
    private const val C_INDEX = "i"
    private const val C_SDP = "c"

    /** This identity as it travels in every payload. */
    data class Sender(
        val number: String,
        val name: String,
        val ed25519: String,
        val curve25519: String,
        val sealing: String,
        val signature: String,
    )

    /** An ICE candidate as carried in a call signal. */
    data class Candidate(val mid: String, val index: Int, val sdp: String)

    // ── Builders ──────────────────────────────────────────────────────────

    private fun base(type: String) = JSONObject().put(F_VERSION, VERSION).put(F_TYPE, type)

    fun message(id: String, ts: Long, body: String): JSONObject =
        base(T_MSG).put(F_ID, id).put(F_TS, ts).put(F_BODY, body)

    fun receipt(ids: List<String>, status: String): JSONObject =
        base(T_RECEIPT).put(F_STATUS, status).put(F_IDS, JSONArray(ids))

    fun resync(): JSONObject = base(T_RESYNC)

    private fun call(kind: String, cid: String) = base(T_CALL).put(F_CALL_KIND, kind).put(F_CALL_ID, cid)

    fun callOffer(cid: String, ts: Long, sdp: String): JSONObject = call(CALL_OFFER, cid).put(F_TS, ts).put(F_SDP, sdp)

    fun callAnswer(cid: String, sdp: String): JSONObject = call(CALL_ANSWER, cid).put(F_SDP, sdp)

    fun callIce(cid: String, candidates: List<Candidate>): JSONObject {
        val arr = JSONArray()
        for (c in candidates) arr.put(JSONObject().put(C_MID, c.mid).put(C_INDEX, c.index).put(C_SDP, c.sdp))
        return call(CALL_ICE, cid).put(F_CANDIDATES, arr)
    }

    fun callReoffer(cid: String, sdp: String): JSONObject = call(CALL_REOFFER, cid).put(F_SDP, sdp)

    fun callEnd(cid: String, reason: String): JSONObject = call(CALL_END, cid).put(F_REASON, reason)

    /**
     * Adds the sender's identity to [payload]. Throws [IllegalStateException]
     * when the payload already uses one of those fields, rather than silently
     * replacing its content.
     */
    fun withSender(payload: JSONObject, sender: Sender): JSONObject {
        val clash = SENDER_FIELDS.filter { payload.has(it) }
        check(clash.isEmpty()) { "payload field(s) ${clash.joinToString()} are reserved for the sender's identity" }
        return payload
            .put(F_FROM, sender.number).put(F_NAME, sender.name)
            .put(F_ED25519, sender.ed25519).put(F_CURVE25519, sender.curve25519)
            .put(F_SEALING, sender.sealing).put(F_SIGNATURE, sender.signature)
    }

    // ── Readers ───────────────────────────────────────────────────────────

    /** The payload in [text], or null when it is not JSON of a version this app reads. */
    fun parse(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optInt(F_VERSION, 0) == VERSION }

    fun type(payload: JSONObject): String = payload.optString(F_TYPE)

    fun callKind(payload: JSONObject): String = payload.optString(F_CALL_KIND)

    /** The sender identity a payload claims, or null when a field is missing. */
    fun senderOf(payload: JSONObject): Sender? {
        val number = payload.optString(F_FROM).takeIf { it.isNotBlank() } ?: return null
        val ed = payload.optString(F_ED25519).takeIf { it.isNotBlank() } ?: return null
        val curve = payload.optString(F_CURVE25519).takeIf { it.isNotBlank() } ?: return null
        val sealing = payload.optString(F_SEALING).takeIf { it.isNotBlank() } ?: return null
        val sig = payload.optString(F_SIGNATURE).takeIf { it.isNotBlank() } ?: return null
        return Sender(number, payload.optString(F_NAME), ed, curve, sealing, sig)
    }

    fun candidates(payload: JSONObject): List<Candidate> {
        val arr = payload.optJSONArray(F_CANDIDATES) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sdp = o.optString(C_SDP).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Candidate(o.optString(C_MID), o.optInt(C_INDEX, 0), sdp)
        }
    }

    fun receiptIds(payload: JSONObject): List<String> {
        val arr = payload.optJSONArray(F_IDS) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
