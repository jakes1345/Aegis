package com.xat.aegis.comms

/**
 * Formats an Aegis number for display: "482 913 605". Aegis numbers are not
 * phone numbers; they only reach other Aegis apps on the same relay.
 */
fun formatAegisNumber(number: String): String =
    if (number.length == 9) "${number.substring(0, 3)} ${number.substring(3, 6)} ${number.substring(6)}" else number

/** Digits only, or null when it is not a well-formed Aegis number. */
fun parseAegisNumber(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    return if (digits.length == 9 && digits[0] != '0') digits else null
}

enum class Direction { IN, OUT }

/**
 * Someone this identity can talk to. The keys are pinned at first contact;
 * [verified] is true once the owner has scanned this contact's QR code (or
 * compared safety numbers) rather than trusting the relay's lookup.
 */
data class Contact(
    val number: String,
    val name: String,
    val ed25519: String,
    val curve25519: String,
    val sealing: String,
    val signature: String,
    val verified: Boolean,
    val addedTs: Long,
    /** Set when a message arrived from this identity with different keys than pinned. */
    val keyChanged: Boolean = false
)

/** One message in a conversation, stored on the phone with an encrypted body. */
data class ChatMessage(
    /** UUID chosen by the sender; also the delivery-receipt key. */
    val id: String,
    val peer: String,
    val direction: Direction,
    val body: String,
    val ts: Long,
    /** OUT: queued | sent | delivered | read | failed. IN: received. */
    val status: String,
    val read: Boolean,
    val error: String? = null
) {
    val failed: Boolean get() = status == "failed"
}

/** Status value of a [ChatMessage] that records a call in the conversation. */
const val STATUS_CALL = "call"
/** Status value of a [ChatMessage] that records an AegisCoin payment; its body is the line to show. */
const val STATUS_PAYMENT = "payment"
/** Status value of a [ChatMessage] that carries a voicemail; its body is [VoicemailBody] JSON. */
const val STATUS_VOICEMAIL = "voicemail"

/** The ticker every relay's coin community uses. */
const val COIN_SYMBOL = "AC"

/** An AegisCoin transfer with a contact, as this phone recorded it. */
data class CoinTx(
    /** UUID chosen by the payer's phone; also the id of the message that shows it in the conversation. */
    val id: String,
    /** The other party's Aegis number. */
    val peer: String,
    val direction: Direction,
    val amount: Long,
    val ts: Long,
    val note: String,
    /** The relay's transaction id. */
    val txid: String
)

/** What a [STATUS_VOICEMAIL] message's body holds: the recording, base64 AMR-NB, and its length. */
data class VoicemailBody(val audioB64: String, val durationMs: Long) {
    fun encode(): String = org.json.JSONObject().put("audio", audioB64).put("dur", durationMs).toString()

    companion object {
        fun decode(body: String): VoicemailBody? = runCatching {
            val o = org.json.JSONObject(body)
            val audio = o.optString("audio").takeIf { it.isNotBlank() } ?: return null
            VoicemailBody(audio, o.optLong("dur", 0L))
        }.getOrNull()
    }
}

/** "0:07" for seven seconds. */
fun durationLabel(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * A message's body as a line of text: what the notification and the
 * conversation list show. A voicemail's body is audio, not text.
 */
fun ChatMessage.preview(): String = when (status) {
    STATUS_VOICEMAIL -> "Voicemail · " + durationLabel(VoicemailBody.decode(body)?.durationMs ?: 0L)
    else -> body
}

enum class CallPhase { INCOMING, DIALING, CONNECTING, CONNECTED, RECONNECTING, ENDED }

/** The one call this phone can be in, from ring to hang-up. */
data class ActiveCall(
    /** UUID chosen by the caller; both phones use it in every signal. */
    val id: String,
    val peer: Contact,
    val direction: Direction,
    val phase: CallPhase,
    val startedAt: Long,
    val connectedAt: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** True once the media path is through a TURN relay rather than direct. */
    val relayed: Boolean = false,
    /** An outgoing call the other phone has reported ringing. */
    val ringing: Boolean = false,
    /** Why the call ended, once it has. */
    val endReason: String? = null
)

data class ChatThread(
    val contact: Contact,
    val lastMessage: ChatMessage?,
    val unread: Int
)

/** Everything the COMMS tab shows above the conversations. */
data class CommsState(
    val registered: Boolean = false,
    val relayUrl: String? = null,
    val number: String? = null,
    val displayName: String = "",
    val listed: Boolean = true,
    /** Whether the owner keeps the relay connection open in the background. */
    val online: Boolean = false,
    val connected: Boolean = false,
    val pushRegistered: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val lastSync: Long = 0L,
    /** One-time keys the relay still holds for us. */
    val relayOneTimeKeys: Int = -1,
    /** AegisCoin on this relay, or -1 until the relay has said. */
    val balance: Long = -1L
)

/**
 * The pairing payload carried by a QR code: the relay, the Aegis number and the
 * full public key set, so a scan pins the identity with no trust in the relay.
 *
 * With [invite] set it is also an invitation: someone without an Aegis number
 * registers on [relayUrl] with that one-time code instead of the relay's
 * enrollment secret, and starts out with the inviter as a contact.
 */
data class PairingCode(
    val relayUrl: String,
    val number: String,
    val ed25519: String,
    val curve25519: String,
    val sealing: String,
    val signature: String,
    val name: String,
    val invite: String? = null
) {
    fun encode(): String {
        val q = (listOf(
            "r" to relayUrl, "n" to number, "k" to ed25519, "c" to curve25519,
            "s" to sealing, "g" to signature, "d" to name
        ) + listOfNotNull(invite?.let { "i" to it }))
            .joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "aegis:v1?$q"
    }

    /**
     * The link to send someone: the relay's invite page, with this payload in
     * the fragment, which the browser keeps to itself.
     */
    fun inviteLink(): String = "$relayUrl/i#" + java.net.URLEncoder.encode(encode(), "UTF-8")

    companion object {
        fun decode(text: String): PairingCode? {
            val t = text.trim()
            if (!t.startsWith("aegis:v1?")) return null
            // Any scanned QR code lands here; a malformed percent escape is
            // "not an Aegis code", not a crash.
            val params = runCatching {
                t.removePrefix("aegis:v1?").split("&").mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
                }.toMap()
            }.getOrNull() ?: return null
            val number = params["n"]?.let { parseAegisNumber(it) } ?: return null
            return PairingCode(
                relayUrl = params["r"]?.trimEnd('/') ?: return null,
                number = number,
                ed25519 = params["k"] ?: return null,
                curve25519 = params["c"] ?: return null,
                sealing = params["s"] ?: return null,
                signature = params["g"] ?: return null,
                name = params["d"] ?: "",
                invite = params["i"]?.takeIf { INVITE_CODE.matches(it) }
            )
        }

        private val INVITE_CODE = Regex("^[A-Za-z0-9_-]{22}$")

        /**
         * An invite or pairing code from whatever the owner pasted: the code
         * text itself, the invite link (payload in the fragment), or the
         * aegis://invite link the invite page opens the app with.
         */
        fun fromText(text: String): PairingCode? {
            val t = text.trim()
            decode(t)?.let { return it }
            val candidates = listOfNotNull(
                t.substringAfter('#', "").takeIf { it.isNotEmpty() },
                t.substringAfter("c=", "").substringBefore('&').takeIf { it.isNotEmpty() }
            )
            for (c in candidates) {
                val decoded = runCatching { java.net.URLDecoder.decode(c.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: continue
                decode(decoded)?.let { return it }
            }
            return null
        }
    }
}
