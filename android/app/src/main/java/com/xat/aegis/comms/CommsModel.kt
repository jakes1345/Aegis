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
    val relayOneTimeKeys: Int = -1
)

/**
 * The pairing payload carried by a QR code: the relay, the Aegis number and the
 * full public key set, so a scan pins the identity with no trust in the relay.
 */
data class PairingCode(
    val relayUrl: String,
    val number: String,
    val ed25519: String,
    val curve25519: String,
    val sealing: String,
    val signature: String,
    val name: String
) {
    fun encode(): String {
        val q = listOf(
            "r" to relayUrl, "n" to number, "k" to ed25519, "c" to curve25519,
            "s" to sealing, "g" to signature, "d" to name
        ).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return "aegis:v1?$q"
    }

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
                name = params["d"] ?: ""
            )
        }
    }
}
