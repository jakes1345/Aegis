package com.xat.aegis.comms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Encrypted voice calls between Aegis numbers.
 *
 * Signalling (offer, answer, ICE candidates, end) travels inside the same
 * Olm-encrypted envelopes as messages, so the DTLS fingerprints in the SDP,
 * which key the media, are authenticated by the contact's pinned identity.
 * Neither the relay nor a TURN server can read or replace them. The audio
 * itself is WebRTC DTLS-SRTP between the two phones: direct when the networks
 * allow it, otherwise forwarded by a TURN relay that carries packets it cannot
 * decrypt.
 *
 * Wire format (inside an envelope, `t` = "call"):
 *   {"ck":"offer","cid":uuid,"ts":ms,"sdp":text}
 *   {"ck":"ringing","cid":uuid}   (the callee's phone is ringing)
 *   {"ck":"answer","cid":uuid,"sdp":text}
 *   {"ck":"ice","cid":uuid,"cands":[{"m":sdpMid,"i":mLineIndex,"c":candidate}]}
 *   {"ck":"end","cid":uuid,"reason":"hangup"|"cancel"|"reject"|"busy"}
 *
 * All state changes run on one serial dispatcher; WebRTC callbacks and
 * repository calls hop onto it.
 */
object CallManager {

    private const val TAG = "CallManager"
    private const val RING_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L
    /**
     * An offer that waited longer than this on the relay is a missed call, not a
     * ring: the caller gives up after [RING_TIMEOUT_MS], so anything older would
     * ring for a call nobody is on any more.
     */
    private const val OFFER_MAX_AGE_MS = RING_TIMEOUT_MS - 5_000L
    /** A late offer still rings at least this long, so there is time to answer. */
    private const val MIN_RING_MS = 10_000L
    private const val CANDIDATE_BATCH_MS = 300L
    /** How long a dropped connection may try to come back on its own before the caller restarts ICE. */
    private const val RECONNECT_GRACE_MS = 3_000L
    /** How long a dropped call may stay down before it is ended. */
    private const val RECONNECT_TIMEOUT_MS = 20_000L
    /** Consecutive failed ICE-candidate sends before giving up on the batch. */
    private const val CANDIDATE_SEND_ATTEMPTS = 15
    private const val ENDED_LINGER_MS = 2_500L
    /** Calls whose ICE candidates arrived before their offer, kept until the offer does. */
    private const val MAX_EARLY_CALLS = 4
    /** Call ids already ended (or cancelled before their offer arrived); a late offer for one never rings. */
    private const val MAX_ENDED_IDS = 64

    private lateinit var appContext: Context

    /**
     * A failure inside call handling used to kill the coroutine without a trace,
     * leaving a call that never rang or a screen stuck on "Calling". It is now
     * recorded and the call is ended cleanly.
     */
    private val crashGuard = CoroutineExceptionHandler { _, e -> onCrash(e) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + crashGuard)

    private fun onCrash(e: Throwable) {
        Log.e(TAG, "call handling failed", e)
        CommsLog.add("Call error: ${e.javaClass.simpleName}: ${e.message}")
        serial.launch { endLocked("error: ${e.message ?: e.javaClass.simpleName}", signal = CommsWire.END_HANGUP) }
    }

    private val _call = MutableStateFlow<ActiveCall?>(null)
    val call: StateFlow<ActiveCall?> = _call.asStateFlow()

    /**
     * The WebRTC objects of one call. They belong to that call's id: a setup
     * that resumes after its call was hung up disposes only what it created,
     * never the media of a newer call.
     */
    private class Media(val callId: String, val pc: PeerConnection, val source: AudioSource, val track: AudioTrack) {
        fun dispose() {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
            runCatching { track.dispose() }
            runCatching { source.dispose() }
        }
    }

    private var factory: PeerConnectionFactory? = null
    private var media: Media? = null
    private var pendingOffer: SessionDescription? = null
    /** Candidates for the current call that arrived before its remote description was set. */
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    /** Candidates for calls whose offer has not arrived yet (envelopes can overtake each other). */
    private val earlyCandidates = LinkedHashMap<String, MutableList<IceCandidate>>()
    private val endedCallIds = LinkedHashSet<String>()
    private val outgoingCandidates = ArrayList<IceCandidate>()
    private var candidateFlush: Job? = null
    private var timeout: Job? = null
    private var restartJob: Job? = null
    private var ringer: Ringer? = null
    private var ringWake: PowerManager.WakeLock? = null
    private var ringback: Ringback? = null
    private var audio: CallAudio? = null
    private var audioCallId: String? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    // ── Public entry points ───────────────────────────────────────────────

    /** Places a call to [contact]. Needs the microphone permission already granted. */
    fun place(contact: Contact) { serial.launch { placeLocked(contact) } }

    /**
     * Answers the ringing call, or only the call [callId] when given (a screen
     * opened for one call must not answer another that replaced it). Needs the
     * microphone permission already granted.
     */
    fun accept(callId: String? = null) { serial.launch { acceptLocked(callId) } }

    fun reject() { serial.launch { endLocked("declined", signal = CommsWire.END_REJECT) } }

    fun hangUp() { serial.launch { hangUpLocked(null) } }

    private suspend fun hangUpLocked(callId: String?) {
        val c = _call.value ?: return
        if (c.phase == CallPhase.ENDED || (callId != null && c.id != callId)) return
        if (c.phase == CallPhase.DIALING) endLocked("cancelled", signal = CommsWire.END_CANCEL)
        else endLocked("ended", signal = CommsWire.END_HANGUP)
    }

    /**
     * Decline from the ringing notification. The receiver waits for this, so
     * the reject is on its way before Android may freeze the process. If the
     * process that rang has been killed since, there is no call in memory any
     * more; the caller is still told, from the call id and number the
     * notification carries, instead of ringing out.
     */
    suspend fun declineFromNotification(callId: String?, peerNumber: String?) {
        serial.async {
            val c = _call.value
            if (c != null && c.phase == CallPhase.INCOMING && (callId == null || c.id == callId)) {
                endLocked("declined", signal = CommsWire.END_REJECT)
                return@async
            }
            if (callId == null || peerNumber == null || callId in endedCallIds || c?.id == callId) return@async
            rememberEnded(callId)
            val contact = withContext(Dispatchers.IO) { CommsRepository.contact(peerNumber) } ?: return@async
            CommsLog.add("Declined ${label(contact)}'s call from its notification")
            CommsRepository.sendCallSignal(contact, CommsWire.callEnd(callId, CommsWire.END_REJECT))
            CommsRepository.logCall(contact, Direction.IN, "Call · declined", unread = false)
        }.await()
    }

    /** Hang up from the ongoing-call notification; waits until the end signal has gone. */
    suspend fun hangUpFromNotification(callId: String?) {
        serial.async { hangUpLocked(callId) }.await()
    }

    fun toggleMute() {
        serial.launch {
            val c = _call.value ?: return@launch
            val muted = !c.muted
            media?.takeIf { it.callId == c.id }?.track?.setEnabled(!muted)
            _call.value = c.copy(muted = muted)
        }
    }

    fun toggleSpeaker() {
        serial.launch {
            val c = _call.value ?: return@launch
            val speaker = !c.speaker
            audio?.setSpeaker(speaker)
            _call.value = c.copy(speaker = speaker)
        }
    }

    /**
     * [contact] could not read something from this phone and started a new
     * session. If a call with them is being set up, what they may have missed
     * (the offer, the answer, the ringing notice) goes again on the new session;
     * each is ignored by the other phone if it did arrive after all.
     */
    fun onPeerResync(contact: Contact) {
        serial.launch {
            val c = _call.value ?: return@launch
            if (c.peer.number != contact.number || c.phase == CallPhase.ENDED) return@launch
            val local = media?.takeIf { it.callId == c.id }?.pc?.localDescription
            val payload = when {
                c.direction == Direction.OUT && c.phase == CallPhase.DIALING && local != null ->
                    CommsWire.callOffer(c.id, CommsRepository.relayNow(), local.description)
                c.direction == Direction.IN && c.phase == CallPhase.CONNECTING && local != null ->
                    CommsWire.callAnswer(c.id, local.description)
                c.direction == Direction.IN && c.phase == CallPhase.INCOMING -> CommsWire.callRinging(c.id)
                else -> null
            } ?: return@launch
            CommsLog.add("Sending the call setup to ${label(contact)} again on the new session")
            CommsRepository.sendCallSignal(contact, payload)
        }
    }

    /** A call payload decrypted from [contact]; called by the repository. */
    fun onSignal(contact: Contact, json: JSONObject, envelopeTs: Long) {
        serial.launch { signalLocked(contact, json, envelopeTs) }
    }

    // ── Call setup ────────────────────────────────────────────────────────

    /**
     * True while the call [id] is the current, unended call. Setup and signal
     * handlers suspend (relay round trips, SDP work), and a hang-up can run in
     * between; every step after a suspension checks this before going on.
     */
    private fun stillLive(id: String): Boolean = _call.value?.let { it.id == id && it.phase != CallPhase.ENDED } == true

    private fun rememberEnded(id: String) {
        endedCallIds.remove(id)
        endedCallIds += id
        while (endedCallIds.size > MAX_ENDED_IDS) endedCallIds.remove(endedCallIds.first())
        earlyCandidates.remove(id)
    }

    private suspend fun placeLocked(contact: Contact) {
        if (_call.value?.let { it.phase != CallPhase.ENDED } == true) return
        val id = UUID.randomUUID().toString()
        _call.value = ActiveCall(id, contact, Direction.OUT, CallPhase.DIALING, System.currentTimeMillis())
        CommsLog.add("Calling ${label(contact)}")
        CommsRepository.holdLiveLink()
        CallService.start(appContext)
        startAudio(id)
        try {
            val m = createPeerConnection(id)
            if (!stillLive(id)) { m.dispose(); return }
            media = m
            val offer = m.pc.createSdp(offer = true)
            if (!stillLive(id)) return
            m.pc.setLocal(offer)
            if (!stillLive(id)) return
            val failure = CommsRepository.sendCallSignal(contact, CommsWire.callOffer(id, CommsRepository.relayNow(), offer.description))
            if (!stillLive(id)) return
            // A failed send may still have reached the relay (the connection broke
            // after the request went out), so the cancel is sent regardless.
            if (failure != null) { endLocked(failure, signal = CommsWire.END_CANCEL); return }
            armTimeout(RING_TIMEOUT_MS) { if (it.id == id && it.phase == CallPhase.DIALING) endLocked("no answer", signal = CommsWire.END_CANCEL) }
        } catch (e: Exception) {
            Log.w(TAG, "placing call failed", e)
            if (stillLive(id)) endLocked(e.message ?: "call setup failed", signal = CommsWire.END_CANCEL)
        }
    }

    private suspend fun acceptLocked(callId: String? = null) {
        val c = _call.value ?: return
        if (c.phase != CallPhase.INCOMING || (callId != null && c.id != callId)) return
        val offer = pendingOffer ?: run { endLocked("offer lost", signal = CommsWire.END_REJECT); return }
        stopRinging()
        CommsNotifications.cancelIncomingCall(appContext)
        _call.value = c.copy(phase = CallPhase.CONNECTING)
        CommsLog.add("Answering ${label(c.peer)}")
        CommsRepository.holdLiveLink()
        CallService.start(appContext)
        startAudio(c.id)
        try {
            val m = createPeerConnection(c.id)
            if (!stillLive(c.id)) { m.dispose(); return }
            media = m
            m.pc.setRemote(offer)
            if (!stillLive(c.id)) return
            flushPendingRemoteCandidates(m)
            val answer = m.pc.createSdp(offer = false)
            if (!stillLive(c.id)) return
            m.pc.setLocal(answer)
            if (!stillLive(c.id)) return
            val failure = CommsRepository.sendCallSignal(c.peer, CommsWire.callAnswer(c.id, answer.description))
            if (!stillLive(c.id)) return
            if (failure != null) { endLocked(failure, signal = CommsWire.END_HANGUP); return }
            armTimeout(CONNECT_TIMEOUT_MS) { if (it.id == c.id && it.phase == CallPhase.CONNECTING) endLocked("could not connect", signal = CommsWire.END_HANGUP) }
        } catch (e: Exception) {
            Log.w(TAG, "accepting call failed", e)
            if (stillLive(c.id)) endLocked(e.message ?: "call setup failed", signal = CommsWire.END_HANGUP)
        }
    }

    // ── Signals from the peer ─────────────────────────────────────────────

    private suspend fun signalLocked(contact: Contact, json: JSONObject, envelopeTs: Long) {
        val kind = CommsWire.callKind(json)
        val cid = json.optString(CommsWire.F_CALL_ID).takeIf { it.isNotBlank() } ?: run {
            CommsLog.add("Call signal from ${label(contact)} without a call id; ignored")
            return
        }
        when (kind) {
            CommsWire.CALL_OFFER -> onOffer(contact, cid, json, envelopeTs)
            CommsWire.CALL_RINGING -> {
                val current = _call.value
                if (current == null || current.id != cid || current.direction != Direction.OUT) return
                if (current.phase != CallPhase.DIALING || current.ringing) return
                CommsLog.add("${label(contact)}'s phone is ringing")
                _call.value = current.copy(ringing = true)
                startRingback()
            }
            CommsWire.CALL_ANSWER -> {
                val current = _call.value
                if (current == null || current.id != cid || current.phase == CallPhase.ENDED || current.phase == CallPhase.INCOMING) return
                val sdp = json.optString(CommsWire.F_SDP).takeIf { it.isNotBlank() } ?: return
                val m = media?.takeIf { it.callId == cid } ?: return
                // Only an offer of ours still waiting for its answer takes one; a
                // repeated or stale answer would be rejected by WebRTC anyway.
                if (m.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                val first = current.phase == CallPhase.DIALING
                if (first) stopRingback()
                try {
                    m.pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    if (!stillLive(cid)) return
                    flushPendingRemoteCandidates(m)
                    if (first) {
                        CommsLog.add("${label(contact)} answered")
                        _call.value = _call.value?.copy(phase = CallPhase.CONNECTING)
                        armTimeout(CONNECT_TIMEOUT_MS) { if (it.id == cid && it.phase == CallPhase.CONNECTING) endLocked("could not connect", signal = CommsWire.END_HANGUP) }
                    } else {
                        CommsLog.add("${label(contact)} accepted the reconnection")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad answer", e)
                    if (stillLive(cid)) endLocked("bad answer from the other phone", signal = CommsWire.END_HANGUP)
                }
            }
            CommsWire.CALL_REOFFER -> {
                // The caller restarted ICE after a network change: answer on the same call.
                val current = _call.value
                if (current == null || current.id != cid || current.direction != Direction.IN) return
                if (current.phase == CallPhase.ENDED || current.phase == CallPhase.INCOMING) return
                val sdp = json.optString(CommsWire.F_SDP).takeIf { it.isNotBlank() } ?: return
                val m = media?.takeIf { it.callId == cid } ?: return
                try {
                    m.pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                    if (!stillLive(cid)) return
                    val answer = m.pc.createSdp(offer = false)
                    if (!stillLive(cid)) return
                    m.pc.setLocal(answer)
                    if (!stillLive(cid)) return
                    CommsLog.add("${label(contact)} is reconnecting the call")
                    CommsRepository.sendCallSignal(contact, CommsWire.callAnswer(cid, answer.description))
                        ?.let { CommsLog.add("Reconnection answer not sent: $it") }
                } catch (e: Exception) {
                    CommsLog.add("Could not take the reconnection: ${e.message}")
                }
            }
            CommsWire.CALL_ICE -> {
                val candidates = CommsWire.candidates(json).map { IceCandidate(it.mid, it.index, it.sdp) }
                val current = _call.value
                if (current != null && current.id == cid) {
                    if (current.phase == CallPhase.ENDED) return
                    val m = media?.takeIf { it.callId == cid }
                    if (m != null && m.pc.remoteDescription != null) candidates.forEach { m.pc.addIceCandidate(it) }
                    else pendingRemoteCandidates += candidates
                } else if (cid !in endedCallIds) {
                    // Overtook its offer: keep it for when the offer arrives.
                    earlyCandidates.getOrPut(cid) { ArrayList() } += candidates
                    while (earlyCandidates.size > MAX_EARLY_CALLS) earlyCandidates.remove(earlyCandidates.keys.first())
                }
            }
            CommsWire.CALL_END -> {
                val current = _call.value
                if (current == null || current.id != cid || current.phase == CallPhase.ENDED) {
                    // An end for a call not ringing here (yet): remember it, so its offer never rings.
                    rememberEnded(cid)
                    return
                }
                val reason = json.optString(CommsWire.F_REASON)
                CommsLog.add("${label(contact)} ended the call (${reason.ifBlank { CommsWire.END_HANGUP }})")
                when (reason) {
                    CommsWire.END_REJECT -> endLocked("declined", signal = null)
                    CommsWire.END_BUSY -> endLocked("busy", signal = null)
                    CommsWire.END_CANCEL -> endLocked("missed", signal = null, missed = current.phase == CallPhase.INCOMING)
                    else -> endLocked("ended", signal = null)
                }
            }
            else -> CommsLog.add("Call signal \"$kind\" from ${label(contact)} not understood; ignored")
        }
    }

    private suspend fun onOffer(contact: Contact, cid: String, json: JSONObject, envelopeTs: Long) {
        val sdp = json.optString(CommsWire.F_SDP).takeIf { it.isNotBlank() } ?: run {
            CommsLog.add("Call offer from ${label(contact)} had no session description; ignored")
            return
        }
        if (cid in endedCallIds) {
            // Its cancel (or our own hang-up) got here first: the caller has already given up.
            CommsLog.add("Call from ${label(contact)} was over before it reached this phone; logged as missed")
            CommsRepository.logCall(contact, Direction.IN, "Missed call", unread = true)
            CommsNotifications.missedCall(appContext, contact)
            return
        }
        if (contact.keyChanged) {
            // Every reply to this contact is refused until their new keys are checked,
            // so ringing would offer an answer that can never be sent.
            CommsLog.add("Call from ${label(contact)} not rung: their keys changed; verify them first")
            CommsRepository.logCall(contact, Direction.IN, "Missed call · keys changed, verify to take calls", unread = true)
            CommsNotifications.missedCall(appContext, contact)
            return
        }
        var autoAnswer = false
        val current = _call.value
        if (current != null && current.phase != CallPhase.ENDED) {
            if (current.id == cid) return
            val samePeer = current.peer.number == contact.number
            when {
                samePeer && current.direction == Direction.OUT && current.phase == CallPhase.DIALING -> {
                    // Both phones called each other at once. The higher call id wins on
                    // both phones, so exactly one call survives and nobody hears "busy".
                    if (current.id > cid) {
                        CommsLog.add("Crossed calls with ${label(contact)}; keeping this phone's call")
                        rememberEnded(cid)
                        return
                    }
                    CommsLog.add("Crossed calls with ${label(contact)}; taking theirs")
                    endLocked("crossed", signal = null, quiet = true)
                    // The owner was calling this very person seconds ago: pick up.
                    autoAnswer = hasMicrophone()
                }
                samePeer && current.direction == Direction.IN && current.phase == CallPhase.INCOMING -> {
                    // They called again before the first attempt's end reached us.
                    endLocked("replaced", signal = null, quiet = true)
                }
                else -> {
                    CommsLog.add("Call from ${label(contact)} while already on a call; answered busy")
                    CommsRepository.sendCallSignal(contact, CommsWire.callEnd(cid, CommsWire.END_BUSY))
                    CommsRepository.logCall(contact, Direction.IN, "Missed call (busy)", unread = true)
                    CommsNotifications.missedCall(appContext, contact)
                    return
                }
            }
        }
        // Both timestamps are the relay's, so a phone whose own clock is off
        // does not mistake a live call for an old one.
        val age = (CommsRepository.relayNow() - envelopeTs).coerceAtLeast(0L)
        if (age > OFFER_MAX_AGE_MS) {
            // Sat on the relay too long (phone was offline): the caller has given up.
            CommsLog.add("Call from ${label(contact)} reached this phone ${age / 1000}s late; logged as missed")
            rememberEnded(cid)
            CommsRepository.logCall(contact, Direction.IN, "Missed call", unread = true)
            CommsNotifications.missedCall(appContext, contact)
            return
        }
        CommsLog.add("Incoming call from ${label(contact)}")
        pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pendingRemoteCandidates.clear()
        earlyCandidates.remove(cid)?.let { pendingRemoteCandidates += it }
        val incoming = ActiveCall(cid, contact, Direction.IN, CallPhase.INCOMING, System.currentTimeMillis())
        _call.value = incoming
        if (autoAnswer) {
            acceptLocked()
            return
        }
        // The in-app call screen is already up; a refused notification or a
        // silent ringtone must not stop the call from being answerable.
        runCatching { CommsNotifications.incomingCall(appContext, incoming) }
            .onFailure { CommsLog.add("Incoming-call notification refused: ${it.message}") }
        if (!CommsNotifications.canPost(appContext)) {
            CommsLog.add("Notifications are off, so the incoming call shows only inside Aegis")
        } else if (!CommsNotifications.canUseFullScreen(appContext)) {
            CommsLog.add("Full-screen calls are not allowed for Aegis, so a locked phone shows only a notification")
        }
        runCatching { startRinging() }
            .onFailure { CommsLog.add("Ringtone could not play: ${it.message}") }
        // Keep the relay socket open while ringing: a phone woken by a push
        // would otherwise see the caller's cancel only on the next wake-up,
        // and keep ringing for a call nobody is on.
        CommsRepository.holdLiveLink()
        // Ring only for as long as the caller is still waiting.
        armTimeout((RING_TIMEOUT_MS - age).coerceAtLeast(MIN_RING_MS)) {
            if (it.id == cid && it.phase == CallPhase.INCOMING) endLocked("missed", signal = null, missed = true)
        }
        // Tell the caller it rings here, so they hear ringback rather than silence.
        serial.launch {
            if (_call.value?.let { it.id == cid && it.phase == CallPhase.INCOMING } == true) {
                CommsRepository.sendCallSignal(contact, CommsWire.callRinging(cid))
            }
        }
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ── WebRTC plumbing ───────────────────────────────────────────────────

    private fun label(contact: Contact) = contact.name.ifBlank { formatAegisNumber(contact.number) }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) { CommsLog.add("Microphone could not start: $errorMessage") }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) { CommsLog.add("Microphone could not start: $errorMessage") }
                override fun onWebRtcAudioRecordError(errorMessage: String) { CommsLog.add("Microphone error: $errorMessage") }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) { CommsLog.add("Call audio could not start: $errorMessage") }
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String) { CommsLog.add("Call audio could not start: $errorMessage") }
                override fun onWebRtcAudioTrackError(errorMessage: String) { CommsLog.add("Call audio error: $errorMessage") }
            })
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        // The factory keeps its own reference; ours is not needed any more.
        adm.release()
        factory = created
        return created
    }

    private suspend fun createPeerConnection(callId: String): Media {
        val servers = withContext(Dispatchers.IO) {
            runCatching { CommsRepository.relayClient().turn() }
                .onFailure { CommsLog.add("Could not fetch call servers from the relay (${it.message}); using STUN only") }
                .getOrDefault(listOf(RelayClient.IceServer(listOf("stun:stun.cloudflare.com:3478"), null, null)))
        }
        if (servers.none { s -> s.urls.any { it.startsWith("turn") } }) {
            CommsLog.add("No TURN relay configured: calls connect only when both networks allow a direct path")
        }
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val f = ensureFactory()
        val peer = f.createPeerConnection(config, observer(callId)) ?: throw IllegalStateException("WebRTC refused to create a connection")
        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("aegis-audio-$callId", source)
        track.setEnabled(_call.value?.takeIf { it.id == callId }?.muted != true)
        peer.addTrack(track, listOf("aegis-stream"))
        return Media(callId, peer, source, track)
    }

    private fun observer(callId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            serial.launch {
                if (!stillLive(callId)) return@launch
                outgoingCandidates += candidate
                if (candidateFlush?.isActive != true) {
                    candidateFlush = serial.launch {
                        delay(CANDIDATE_BATCH_MS)
                        // Candidates gathered while a batch was on its way go too:
                        // later ones (server-reflexive, relayed) are the ones that
                        // connect phones on different networks.
                        var failures = 0
                        while (outgoingCandidates.isNotEmpty() && stillLive(callId) && failures < CANDIDATE_SEND_ATTEMPTS) {
                            if (flushOutgoingCandidates(callId)) failures = 0
                            else { failures++; delay(1_000L) }
                        }
                    }
                }
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            serial.launch {
                val c = _call.value ?: return@launch
                if (c.id != callId || c.phase == CallPhase.ENDED) return@launch
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        timeout?.cancel()
                        restartJob?.cancel()
                        when {
                            c.connectedAt == 0L -> CommsLog.add("Call with ${label(c.peer)} connected")
                            c.phase == CallPhase.RECONNECTING -> CommsLog.add("Call with ${label(c.peer)} reconnected")
                        }
                        _call.value = c.copy(phase = CallPhase.CONNECTED, connectedAt = if (c.connectedAt > 0L) c.connectedAt else System.currentTimeMillis())
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                        if (c.connectedAt == 0L) {
                            if (state == PeerConnection.PeerConnectionState.FAILED) {
                                endLocked("could not connect (no network path between the phones; a TURN relay may be needed)", signal = CommsWire.END_HANGUP)
                            }
                            return@launch
                        }
                        // A connected call dropped (a network change, a tunnel): try to
                        // bring it back before giving up. The caller restarts ICE, so the
                        // two phones never restart at once.
                        if (c.phase != CallPhase.RECONNECTING) {
                            CommsLog.add("Call with ${label(c.peer)} dropped; reconnecting")
                            _call.value = c.copy(phase = CallPhase.RECONNECTING)
                            armTimeout(RECONNECT_TIMEOUT_MS) { if (it.id == callId && it.phase == CallPhase.RECONNECTING) endLocked("connection lost", signal = CommsWire.END_HANGUP) }
                        }
                        if (c.direction == Direction.OUT && restartJob?.isActive != true) {
                            val wait = if (state == PeerConnection.PeerConnectionState.FAILED) 0L else RECONNECT_GRACE_MS
                            restartJob = serial.launch {
                                delay(wait)
                                restartIceLocked(callId)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            serial.launch {
                val c = _call.value ?: return@launch
                if (c.id != callId) return@launch
                val relayed = event.local.sdp.contains(" typ relay") || event.remote.sdp.contains(" typ relay")
                if (relayed != c.relayed) _call.value = c.copy(relayed = relayed)
            }
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            // A STUN/TURN server that could not be reached or refused the credentials.
            CommsLog.add("Call server ${event.url} error ${event.errorCode}: ${event.errorText}")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.CHECKING || state == PeerConnection.IceConnectionState.FAILED) {
                CommsLog.add("Call network check: ${state.name.lowercase()}")
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /**
     * Sends the gathered candidates. A batch that fails (the network is switching,
     * say) goes back on the queue to be retried; returns false in that case.
     */
    private suspend fun flushOutgoingCandidates(callId: String): Boolean {
        val c = _call.value ?: return true
        if (c.id != callId || c.phase == CallPhase.ENDED || outgoingCandidates.isEmpty()) return true
        val batch = ArrayList(outgoingCandidates)
        outgoingCandidates.clear()
        val cands = batch.map { CommsWire.Candidate(it.sdpMid ?: "", it.sdpMLineIndex, it.sdp) }
        val failure = CommsRepository.sendCallSignal(c.peer, CommsWire.callIce(c.id, cands)) ?: return true
        if (stillLive(callId)) outgoingCandidates.addAll(0, batch)
        Log.w(TAG, "ICE batch not sent: $failure")
        return false
    }

    /** The caller's side of a reconnection: new ICE credentials and a fresh offer on the same call. */
    private suspend fun restartIceLocked(callId: String) {
        val c = _call.value ?: return
        if (c.id != callId || c.phase != CallPhase.RECONNECTING || c.direction != Direction.OUT) return
        val m = media?.takeIf { it.callId == callId } ?: return
        try {
            m.pc.restartIce()
            val offer = m.pc.createSdp(offer = true)
            if (!stillLive(callId)) return
            m.pc.setLocal(offer)
            if (!stillLive(callId)) return
            CommsLog.add("Restarting the connection to ${label(c.peer)}")
            CommsRepository.sendCallSignal(c.peer, CommsWire.callReoffer(callId, offer.description))
                ?.let { CommsLog.add("Reconnection offer not sent: $it") }
        } catch (e: Exception) {
            CommsLog.add("Could not restart the connection: ${e.message}")
        }
    }

    private fun flushPendingRemoteCandidates(m: Media) {
        for (cand in pendingRemoteCandidates) m.pc.addIceCandidate(cand)
        pendingRemoteCandidates.clear()
    }

    private fun armTimeout(ms: Long, block: suspend (ActiveCall) -> Unit) {
        timeout?.cancel()
        timeout = serial.launch {
            delay(ms)
            // Detach before acting: endLocked() cancels `timeout`, which must not be this job.
            timeout = null
            _call.value?.let { block(it) }
        }
    }

    // ── Teardown ──────────────────────────────────────────────────────────

    /**
     * Ends the call for [reason]. [signal], when given, is the "end" reason
     * sent to the other phone; [missed] records an unanswered incoming call;
     * [quiet] leaves no entry in the conversation (a call replaced by another).
     */
    private suspend fun endLocked(reason: String, signal: String?, missed: Boolean = false, quiet: Boolean = false) {
        val c = _call.value ?: return
        if (c.phase == CallPhase.ENDED) return
        // Claim the teardown and release the microphone before the first
        // suspension: a hang-up and the peer's "end" can arrive together, and
        // the relay round trip below may take the full network timeout.
        _call.value = c.copy(phase = CallPhase.ENDED, endReason = reason)
        rememberEnded(c.id)
        CommsLog.add("Call ${if (c.direction == Direction.OUT) "to" else "from"} ${label(c.peer)} ended: $reason")
        timeout?.cancel()
        restartJob?.cancel()
        candidateFlush?.cancel()
        outgoingCandidates.clear()
        pendingRemoteCandidates.clear()
        pendingOffer = null
        stopRinging()
        stopRingback()
        CommsNotifications.cancelIncomingCall(appContext)
        media?.takeIf { it.callId == c.id }?.let { it.dispose(); media = null }
        stopAudio(c.id)
        // CallService stops itself when it sees the call end; stopping it from
        // here could beat its startForeground() and crash the app.
        if (signal != null) {
            CommsRepository.sendCallSignal(c.peer, CommsWire.callEnd(c.id, signal))
        }

        if (!quiet) {
            val now = System.currentTimeMillis()
            val body = when {
                missed -> "Missed call"
                c.connectedAt > 0L -> "Call · ${durationText(now - c.connectedAt)}"
                c.direction == Direction.OUT -> when (reason) {
                    "declined" -> "Call declined"
                    "busy" -> "Call · busy"
                    "no answer", "cancelled" -> "Call not answered"
                    else -> "Call failed · $reason"
                }
                else -> "Call · $reason"
            }
            CommsRepository.logCall(c.peer, c.direction, body, unread = missed)
            if (missed) CommsNotifications.missedCall(appContext, c.peer)
        }
        CommsRepository.releaseLiveLink()
        serial.launch {
            delay(ENDED_LINGER_MS)
            val still = _call.value
            if (still != null && still.id == c.id && still.phase == CallPhase.ENDED) _call.value = null
        }
    }

    // ── Audio and ringing ─────────────────────────────────────────────────

    private fun startAudio(callId: String) {
        if (audio != null && audioCallId == callId) return
        audio?.stop()
        audio = CallAudio(appContext).also { it.start() }
        audioCallId = callId
    }

    /** Leaves call audio mode, but only for the call that entered it. */
    private fun stopAudio(callId: String) {
        if (audioCallId != callId) return
        audio?.stop()
        audio = null
        audioCallId = null
    }

    private fun startRinging() {
        // A phone woken by a push for this call would otherwise go back to
        // sleep in the middle of ringing.
        if (ringWake == null) {
            ringWake = appContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:ringing")
                ?.apply { setReferenceCounted(false); acquire(RING_TIMEOUT_MS + 5_000L) }
        }
        if (ringer == null) ringer = Ringer(appContext).also { it.start() }
    }

    private fun stopRinging() {
        ringer?.stop()
        ringer = null
        ringWake?.let { runCatching { if (it.isHeld) it.release() } }
        ringWake = null
    }

    private fun startRingback() {
        if (ringback == null) ringback = Ringback().also { it.start() }
    }

    private fun stopRingback() {
        ringback?.stop()
        ringback = null
    }

    fun durationText(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ── SDP helpers ───────────────────────────────────────────────────────

    private suspend fun PeerConnection.createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP creation failed")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) createOffer(obs, constraints) else createAnswer(obs, constraints)
    }

    private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = setDescription(local = true, sdp = sdp)
    private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = setDescription(local = false, sdp = sdp)

    private suspend fun PeerConnection.setDescription(local: Boolean, sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP rejected")) }
        }
        if (local) setLocalDescription(obs, sdp) else setRemoteDescription(obs, sdp)
    }
}

/**
 * Puts the phone in call mode: voice-communication audio focus, and the call
 * on a headset when one is connected (Bluetooth, wired, USB), else the
 * earpiece, or the loudspeaker on request. It follows headsets that are
 * plugged in or connected during the call.
 */
private class CallAudio(context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val power = context.getSystemService(PowerManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var speaker = false
    /** Turns the screen off at the ear, so a cheek cannot tap HANG UP or MUTE. */
    private var proximity: PowerManager.WakeLock? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = route()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = route()
    }

    fun start() {
        previousMode = manager.mode
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        manager.requestAudioFocus(request)
        focus = request
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(devices, null)
        route()
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        route()
    }

    private fun route() {
        val wanted = if (speaker) listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) else listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
        val available = manager.availableCommunicationDevices
        val device = wanted.firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type } }
            ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        runCatching { if (device != null) manager.setCommunicationDevice(device) else manager.clearCommunicationDevice() }
        holdProximity(device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    private fun holdProximity(atEar: Boolean) {
        if (atEar && power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            if (proximity == null) {
                proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aegis:call-proximity").apply {
                    setReferenceCounted(false)
                    acquire(4 * 3_600_000L)
                }
            }
        } else {
            proximity?.let { runCatching { it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } }
            proximity = null
        }
    }

    fun stop() {
        holdProximity(false)
        runCatching { manager.unregisterAudioDeviceCallback(devices) }
        runCatching { manager.clearCommunicationDevice() }
        manager.mode = previousMode
        focus?.let { manager.abandonAudioFocusRequest(it) }
        focus = null
    }
}

/** The default ringtone plus vibration, honouring the phone's ringer mode. */
private class Ringer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vm.defaultVibrator.also {
                if (!it.hasVibrator()) return@also
                val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0)
                // Tagged as a ringtone: Android drops untagged vibration from an app
                // that is not in the foreground, which is how most calls arrive.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                }
            }
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}

/**
 * The ringback the caller hears in the earpiece once the other phone reports
 * ringing: the standard ringing cadence on the voice-call stream, so it
 * follows the call's routing (earpiece, speaker or headset).
 */
private class Ringback {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()

    fun start() {
        runCatching { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
    }

    fun stop() {
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
    }
}
