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
 *   call     "ck": offer | ringing | answer | ice | reoffer | end, "cid": call id, and
 *            offer "ts", "sdp" · answer "sdp" · reoffer "sdp" · ice "cands": [{"m","i","c"}] · end "reason"
 *            (ringing carries nothing more: the callee's phone is ringing)
 *   payment  "id", "txid" (the relay's transaction id), "ts", "amt" (AegisCoin), "note" (optional)
 *            — the coins moved through the relay already; this tells the recipient, privately
 *   voicemail "id", "ts", "audio" (base64 AMR-NB), "dur" (ms)
 *   media    "id", "ts", "mime", "w", "h", "dur" (video ms, else 0), "size" (bytes), "n" (chunk count),
 *            "cap" (caption, optional), "thumb" (small JPEG, base64, optional)
 *            — announces a photo or video; its bytes follow in "n" mchunk payloads
 *   mchunk   "id" (the media's), "i" (0-based index), "data" (base64 of at most [MEDIA_CHUNK_BYTES] bytes)
 *
 * The relay takes envelopes of at most 64 KB. A payload's bytes are base64
 * inside the Olm ciphertext and the sealed envelope is base64 again for the
 * relay, so each envelope carries roughly half its size in payload; that is why
 * media travels in chunks and why [MEDIA_CHUNK_BYTES] is what it is.
 *
 * Pure Kotlin and org.json, so the JVM tests use exactly this code.
 */
object CommsWire {

    const val VERSION = 1

    /**
     * Raw bytes per media chunk. 30 KB becomes 40 KB of base64 in the payload,
     * about 55 KB once Olm's ciphertext is base64 inside the sealed envelope, and
     * about 74 KB as the relay sees it: under its 64 KB (87 KB base64) limit,
     * with room for the sender's identity fields.
     */
    const val MEDIA_CHUNK_BYTES = 30 * 1024

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
    const val T_PAYMENT = "payment"
    const val T_VOICEMAIL = "voicemail"
    const val T_MEDIA = "media"
    const val T_MEDIA_CHUNK = "mchunk"

    // Organisation groups: fanned out client-side to every member, one envelope each.
    /** A text message to a group: "gid", "id", "body". */
    const val T_GROUP_MSG = "gmsg"
    /** A roster change: "gid", "op" (add | remove | roster) and the fields of that op. */
    const val T_GROUP_CTRL = "gctl"
    /** A threat detection shared with a group: "gid", "id", "ts", "kind", "sev", "lat", "lon", "title", "detail", "exp", optional "ble"/"cell". */
    const val T_THREAT = "threat"
    /** An SOS to a group: "gid", "id", "ts", "lat", "lon", "note". */
    const val T_PANIC = "panic"

    // group
    const val F_GID = "gid"
    const val F_OP = "op"
    const val F_MEMBER = "num"
    /** The added member's display name and identity key: "name" and "k" belong to the sender. */
    const val F_MEMBER_NAME = "mname"
    const val F_MEMBER_ED25519 = "med"
    const val F_ROLE = "role"
    const val F_MEMBERS = "members"
    const val F_GROUP_NAME = "gname"
    const val F_CREATED_BY = "by"
    const val F_CREATED_TS = "cts"
    /** Sent to a newly added member: the group's name ("gname"), creator, and its whole roster ("members"). */
    const val OP_ADD = "add"
    /** Sent to the existing members when someone was added: "num", "mname", "role", "med". */
    const val OP_JOIN = "join"
    /** Sent by an admin to everyone, the removed member included: "num". */
    const val OP_REMOVE = "remove"
    /** Sent by a member to everyone else as they go; it carries nothing more. */
    const val OP_LEAVE = "leave"
    /** Sent to a newly added member: the group's name and its whole roster. */
    const val OP_ROSTER = "roster"

    // threat / panic
    const val F_KIND = "kind"
    const val F_SEVERITY = "sev"
    const val F_LAT = "lat"
    const val F_LON = "lon"
    const val F_TITLE = "title"
    const val F_DETAIL = "detail"
    const val F_EXPIRES = "exp"
    const val F_BLE = "ble"
    const val F_CELL = "cell"

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
    /** Sent by the callee once its phone rings, so the caller hears ringback instead of silence. */
    const val CALL_RINGING = "ringing"
    /** A fresh offer for a call already under way (ICE restart after a network change); answered with "answer". */
    const val CALL_REOFFER = "reoffer"
    const val END_HANGUP = "hangup"
    const val END_CANCEL = "cancel"
    const val END_REJECT = "reject"
    const val END_BUSY = "busy"

    // payment
    const val F_TXID = "txid"
    const val F_AMOUNT = "amt"
    const val F_NOTE = "note"

    // voicemail
    /** Base64 of the AMR-NB recording. */
    const val F_AUDIO = "audio"
    /** Its length in milliseconds. */
    const val F_DURATION = "dur"

    // media / mchunk
    const val F_MIME = "mime"
    const val F_WIDTH = "w"
    const val F_HEIGHT = "h"
    const val F_SIZE = "size"
    const val F_CHUNKS = "n"
    const val F_CAPTION = "cap"
    const val F_THUMB = "thumb"
    const val F_INDEX = "i"
    const val F_DATA = "data"

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

    fun callRinging(cid: String): JSONObject = call(CALL_RINGING, cid)

    fun callAnswer(cid: String, sdp: String): JSONObject = call(CALL_ANSWER, cid).put(F_SDP, sdp)

    fun callIce(cid: String, candidates: List<Candidate>): JSONObject {
        val arr = JSONArray()
        for (c in candidates) arr.put(JSONObject().put(C_MID, c.mid).put(C_INDEX, c.index).put(C_SDP, c.sdp))
        return call(CALL_ICE, cid).put(F_CANDIDATES, arr)
    }

    fun callReoffer(cid: String, sdp: String): JSONObject = call(CALL_REOFFER, cid).put(F_SDP, sdp)

    fun callEnd(cid: String, reason: String): JSONObject = call(CALL_END, cid).put(F_REASON, reason)

    /** Tells the recipient about coins already moved to them by the relay, with the note only they can read. */
    fun payment(id: String, txid: String, ts: Long, amount: Long, note: String?): JSONObject {
        val o = base(T_PAYMENT).put(F_ID, id).put(F_TXID, txid).put(F_TS, ts).put(F_AMOUNT, amount)
        if (!note.isNullOrBlank()) o.put(F_NOTE, note)
        return o
    }

    fun voicemail(id: String, ts: Long, audioB64: String, durationMs: Long): JSONObject =
        base(T_VOICEMAIL).put(F_ID, id).put(F_TS, ts).put(F_AUDIO, audioB64).put(F_DURATION, durationMs)

    /** Announces a photo or video whose bytes follow in [chunks] mchunk payloads. */
    fun mediaHeader(
        id: String, ts: Long, mime: String, width: Int, height: Int, durationMs: Long,
        size: Long, chunks: Int, caption: String?, thumbB64: String?
    ): JSONObject {
        val o = base(T_MEDIA).put(F_ID, id).put(F_TS, ts).put(F_MIME, mime).put(F_WIDTH, width).put(F_HEIGHT, height)
            .put(F_DURATION, durationMs).put(F_SIZE, size).put(F_CHUNKS, chunks)
        if (!caption.isNullOrBlank()) o.put(F_CAPTION, caption)
        if (!thumbB64.isNullOrBlank()) o.put(F_THUMB, thumbB64)
        return o
    }

    /** Chunk [index] of media [id]; [dataB64] is base64 of at most [MEDIA_CHUNK_BYTES] bytes. */
    fun mediaChunk(id: String, index: Int, dataB64: String): JSONObject =
        base(T_MEDIA_CHUNK).put(F_ID, id).put(F_INDEX, index).put(F_DATA, dataB64)

    /** A text message to group [gid]; the same payload goes to every member. */
    fun groupMsg(gid: String, body: String, msgId: String, ts: Long): JSONObject =
        base(T_GROUP_MSG).put(F_GID, gid).put(F_ID, msgId).put(F_TS, ts).put(F_BODY, body)

    /**
     * A roster change in group [gid]: [op] is [OP_ADD], [OP_REMOVE] or
     * [OP_ROSTER], and [extra] holds that op's fields (null values are left out).
     * The sender's identity fields are reserved; see [withSender].
     */
    fun groupCtrl(gid: String, op: String, extra: Map<String, Any?> = emptyMap()): JSONObject {
        val o = base(T_GROUP_CTRL).put(F_GID, gid).put(F_OP, op)
        for ((k, v) in extra) {
            require(k !in SENDER_FIELDS) { "group control field \"$k\" is reserved for the sender's identity" }
            if (v != null) o.put(k, v)
        }
        return o
    }

    /** One member of a roster, as carried in [OP_ROSTER]'s "members" array. */
    fun rosterEntry(number: String, name: String, role: String, ed25519: String): JSONObject =
        JSONObject().put(F_MEMBER, number).put(F_NAME, name).put(F_ROLE, role).put(F_ED25519, ed25519)

    /** Shares a threat detection with group [gid]; [bleJson] and [cellJson] are optional JSON objects with the raw evidence. */
    fun threat(
        gid: String, threatId: String, ts: Long,
        kind: String, severity: String,
        lat: Double, lon: Double,
        title: String, detail: String,
        expiresAt: Long,
        bleJson: String? = null, cellJson: String? = null
    ): JSONObject {
        val o = base(T_THREAT).put(F_GID, gid).put(F_ID, threatId).put(F_TS, ts)
            .put(F_KIND, kind).put(F_SEVERITY, severity)
            .put(F_LAT, lat).put(F_LON, lon)
            .put(F_TITLE, title).put(F_DETAIL, detail)
            .put(F_EXPIRES, expiresAt)
        bleJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o.put(F_BLE, it) }
        cellJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o.put(F_CELL, it) }
        return o
    }

    /** An SOS to group [gid] with the sender's position; "lat"/"lon" are left out when the phone had no fix. */
    fun panic(gid: String, panicId: String, ts: Long, lat: Double?, lon: Double?, note: String?): JSONObject {
        val o = base(T_PANIC).put(F_GID, gid).put(F_ID, panicId).put(F_TS, ts).put(F_NOTE, note ?: "")
        if (lat != null && lon != null) o.put(F_LAT, lat).put(F_LON, lon)
        return o
    }

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

    /** One member as read back from an [OP_ROSTER] payload's "members" array. */
    data class RosterEntry(val number: String, val name: String, val role: String, val ed25519: String)

    fun rosterEntries(payload: JSONObject): List<RosterEntry> {
        val arr = payload.optJSONArray(F_MEMBERS) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val number = o.optString(F_MEMBER).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RosterEntry(number, o.optString(F_NAME), o.optString(F_ROLE), o.optString(F_ED25519))
        }
    }
}
