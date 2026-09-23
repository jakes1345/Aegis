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

/** One call on the owner's number, from the relay's call log. */
data class CallRecord(
    val id: String,
    val direction: Direction,
    val peer: String,
    /** ringing | in-progress | completed | missed | busy | failed | no-answer | canceled */
    val status: String,
    /** Seconds, once completed. */
    val duration: Int,
    val ts: Long,
    val updated: Long
) {
    val missed: Boolean get() = status == "missed"
}

/** Where an active call is in its life. */
enum class CallPhase { INCOMING, CONNECTING, RINGING, CONNECTED, RECONNECTING, ENDED }

/** The call currently ringing or in progress on this phone, for the in-call UI. */
data class ActiveCall(
    val id: String,
    val peer: String,
    val direction: Direction,
    val phase: CallPhase,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Epoch millis the call connected, 0 until it does. */
    val connectedAt: Long = 0L,
    /** Why the call ended, when it ended abnormally. */
    val error: String? = null
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
    val lastSync: Long = 0L,
    /** Whether Twilio Voice knows this phone, so calls to the number ring it. */
    val voiceRegistered: Boolean = false
)
