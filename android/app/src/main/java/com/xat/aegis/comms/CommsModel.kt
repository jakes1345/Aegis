package com.xat.aegis.comms

enum class Direction { IN, OUT }

data class MediaItem(val url: String, val contentType: String)

/** One SMS or MMS on the owner's number, as cached on the phone. */
data class SmsMessage(
    /** Twilio message SID. */
    val id: String,
    /** Relay sequence number; the sync cursor. */
    val seq: Long,
    val direction: Direction,
    /** The other party, E.164. */
    val peer: String,
    val body: String,
    val media: List<MediaItem>,
    /** received | queued | sending | sent | delivered | undelivered | failed */
    val status: String,
    val error: String?,
    val ts: Long,
    val updated: Long,
    val read: Boolean
) {
    val failed: Boolean get() = status == "failed" || status == "undelivered"
}

/** A conversation with one number, summarised for the thread list. */
data class Thread(
    val peer: String,
    val lastMessage: SmsMessage,
    val unread: Int,
    val count: Int
)

/** Pairing and sync state shown at the top of the COMMS tab. */
data class CommsState(
    val paired: Boolean = false,
    val workerUrl: String? = null,
    val number: String? = null,
    val pushRegistered: Boolean = false,
    /** True while a relay call is in flight. */
    val busy: Boolean = false,
    /** Last relay error worth showing, or null. */
    val error: String? = null,
    /** Epoch millis of the last successful sync, 0 if never. */
    val lastSync: Long = 0L
)
