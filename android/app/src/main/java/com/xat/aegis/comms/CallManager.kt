package com.xat.aegis.comms

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
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
 *   {"k":"offer","cid":uuid,"ts":ms,"sdp":text}
 *   {"k":"answer","cid":uuid,"sdp":text}
 *   {"k":"ice","cid":uuid,"cands":[{"m":sdpMid,"i":mLineIndex,"c":candidate}]}
 *   {"k":"end","cid":uuid,"reason":"hangup"|"cancel"|"reject"|"busy"}
 *
 * All state changes run on one serial dispatcher; WebRTC callbacks and
 * repository calls hop onto it.
 */
object CallManager {

    private const val TAG = "CallManager"
    private const val RING_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L
    /** An offer that waited longer than this on the relay is a missed call, not a ring. */
    private const val OFFER_MAX_AGE_MS = 60_000L
    private const val CANDIDATE_BATCH_MS = 300L
    private const val ENDED_LINGER_MS = 2_500L

    private lateinit var appContext: Context

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _call = MutableStateFlow<ActiveCall?>(null)
    val call: StateFlow<ActiveCall?> = _call.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var pendingOffer: SessionDescription? = null
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    private val outgoingCandidates = ArrayList<IceCandidate>()
    private var candidateFlush: Job? = null
    private var timeout: Job? = null
    private var ringer: Ringer? = null
    private var audio: CallAudio? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    // ── Public entry points ───────────────────────────────────────────────

    /** Places a call to [contact]. Needs the microphone permission already granted. */
    fun place(contact: Contact) { serial.launch { placeLocked(contact) } }

    /** Answers the ringing call. Needs the microphone permission already granted. */
    fun accept() { serial.launch { acceptLocked() } }

    fun reject() { serial.launch { endLocked("declined", signal = "reject") } }

    fun hangUp() {
        serial.launch {
            val c = _call.value ?: return@launch
            if (c.phase == CallPhase.DIALING) endLocked("cancelled", signal = "cancel")
            else endLocked("ended", signal = "hangup")
        }
    }

    fun toggleMute() {
        serial.launch {
            val c = _call.value ?: return@launch
            val muted = !c.muted
            audioTrack?.setEnabled(!muted)
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

    /** A call payload decrypted from [contact]; called by the repository. */
    fun onSignal(contact: Contact, json: JSONObject, envelopeTs: Long) {
        serial.launch { signalLocked(contact, json, envelopeTs) }
    }

    // ── Call setup ────────────────────────────────────────────────────────

    /**
     * True while the call [id] is the current, unended call. Setup and signal
     * handlers suspend (relay round trips, SDP work), and a hang-up can run in
     * between; every step after a suspension checks this before touching the
     * peer connection, which the hang-up may already have disposed.
     */
    private fun stillLive(id: String): Boolean = _call.value?.let { it.id == id && it.phase != CallPhase.ENDED } == true

    /** Drops media objects a setup created after its call had already ended. */
    private fun abandonSetup() {
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        pc = null; audioTrack = null; audioSource = null
        stopAudio()
    }

    private suspend fun placeLocked(contact: Contact) {
        if (_call.value != null) return
        val id = UUID.randomUUID().toString()
        _call.value = ActiveCall(id, contact, Direction.OUT, CallPhase.DIALING, System.currentTimeMillis())
        CommsRepository.holdLiveLink()
        CallService.start(appContext)
        startAudio()
        try {
            createPeerConnection(id)
            if (!stillLive(id)) { abandonSetup(); return }
            val peer = pc ?: throw IllegalStateException("no connection")
            val offer = peer.createSdp(offer = true)
            if (!stillLive(id)) { abandonSetup(); return }
            peer.setLocal(offer)
            if (!stillLive(id)) { abandonSetup(); return }
            val sent = CommsRepository.sendCallSignal(
                contact,
                JSONObject().put("k", "offer").put("cid", id).put("ts", System.currentTimeMillis()).put("sdp", offer.description)
            )
            if (!stillLive(id)) return
            if (!sent) { endLocked("could not reach the relay", signal = null); return }
            armTimeout(RING_TIMEOUT_MS) { if (it.phase == CallPhase.DIALING) endLocked("no answer", signal = "cancel") }
        } catch (e: Exception) {
            Log.w(TAG, "placing call failed", e)
            if (stillLive(id)) endLocked(e.message ?: "call setup failed", signal = "cancel") else abandonSetup()
        }
    }

    private suspend fun acceptLocked() {
        val c = _call.value ?: return
        if (c.phase != CallPhase.INCOMING) return
        val offer = pendingOffer ?: run { endLocked("offer lost", signal = "reject"); return }
        stopRinging()
        CommsNotifications.cancelIncomingCall(appContext)
        _call.value = c.copy(phase = CallPhase.CONNECTING)
        CommsRepository.holdLiveLink()
        CallService.start(appContext)
        startAudio()
        try {
            createPeerConnection(c.id)
            if (!stillLive(c.id)) { abandonSetup(); return }
            val peer = pc ?: throw IllegalStateException("no connection")
            peer.setRemote(offer)
            if (!stillLive(c.id)) { abandonSetup(); return }
            flushPendingRemoteCandidates()
            val answer = peer.createSdp(offer = false)
            if (!stillLive(c.id)) { abandonSetup(); return }
            peer.setLocal(answer)
            if (!stillLive(c.id)) { abandonSetup(); return }
            val sent = CommsRepository.sendCallSignal(
                c.peer,
                JSONObject().put("k", "answer").put("cid", c.id).put("sdp", answer.description)
            )
            if (!stillLive(c.id)) return
            if (!sent) { endLocked("could not reach the relay", signal = null); return }
            armTimeout(CONNECT_TIMEOUT_MS) { if (it.phase == CallPhase.CONNECTING) endLocked("could not connect", signal = "hangup") }
        } catch (e: Exception) {
            Log.w(TAG, "accepting call failed", e)
            if (stillLive(c.id)) endLocked(e.message ?: "call setup failed", signal = "hangup") else abandonSetup()
        }
    }

    // ── Signals from the peer ─────────────────────────────────────────────

    private suspend fun signalLocked(contact: Contact, json: JSONObject, envelopeTs: Long) {
        val kind = json.optString("k")
        val cid = json.optString("cid").takeIf { it.isNotBlank() } ?: return
        val current = _call.value
        when (kind) {
            "offer" -> {
                val sdp = json.optString("sdp").takeIf { it.isNotBlank() } ?: return
                if (current != null && current.phase != CallPhase.ENDED) {
                    if (current.id != cid) {
                        // Already on a call: tell them, and note the attempt.
                        CommsRepository.sendCallSignal(contact, JSONObject().put("k", "end").put("cid", cid).put("reason", "busy"))
                        CommsRepository.logCall(contact, Direction.IN, "Missed call (busy)", unread = true)
                        CommsNotifications.missedCall(appContext, contact)
                    }
                    return
                }
                if (System.currentTimeMillis() - envelopeTs > OFFER_MAX_AGE_MS) {
                    // Sat on the relay too long (phone was offline): the caller has given up.
                    CommsRepository.logCall(contact, Direction.IN, "Missed call", unread = true)
                    CommsNotifications.missedCall(appContext, contact)
                    return
                }
                pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                pendingRemoteCandidates.clear()
                val incoming = ActiveCall(cid, contact, Direction.IN, CallPhase.INCOMING, System.currentTimeMillis())
                _call.value = incoming
                CommsNotifications.incomingCall(appContext, incoming)
                startRinging()
                armTimeout(RING_TIMEOUT_MS) { if (it.phase == CallPhase.INCOMING) endLocked("missed", signal = null, missed = true) }
            }
            "answer" -> {
                if (current == null || current.id != cid || current.phase != CallPhase.DIALING) return
                val sdp = json.optString("sdp").takeIf { it.isNotBlank() } ?: return
                val peer = pc ?: return
                try {
                    peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    if (!stillLive(cid)) return
                    flushPendingRemoteCandidates()
                    _call.value = _call.value?.copy(phase = CallPhase.CONNECTING)
                    armTimeout(CONNECT_TIMEOUT_MS) { if (it.phase == CallPhase.CONNECTING) endLocked("could not connect", signal = "hangup") }
                } catch (e: Exception) {
                    Log.w(TAG, "bad answer", e)
                    if (stillLive(cid)) endLocked("bad answer from the other phone", signal = "hangup")
                }
            }
            "ice" -> {
                if (current == null || current.id != cid) return
                val arr = json.optJSONArray("cands") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cand = IceCandidate(o.optString("m"), o.optInt("i", 0), o.optString("c"))
                    if (cand.sdp.isBlank()) continue
                    val peer = pc
                    if (peer != null && peer.remoteDescription != null) peer.addIceCandidate(cand) else pendingRemoteCandidates += cand
                }
            }
            "end" -> {
                if (current == null || current.id != cid) return
                when (json.optString("reason")) {
                    "reject" -> endLocked("declined", signal = null)
                    "busy" -> endLocked("busy", signal = null)
                    "cancel" -> endLocked("missed", signal = null, missed = current.phase == CallPhase.INCOMING)
                    else -> endLocked("ended", signal = null)
                }
            }
        }
    }

    // ── WebRTC plumbing ───────────────────────────────────────────────────

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
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        factory = created
        return created
    }

    private suspend fun createPeerConnection(callId: String) {
        val servers = withContext(Dispatchers.IO) {
            runCatching { CommsRepository.relayClient().turn() }
                .onFailure { Log.w(TAG, "no TURN credentials: ${it.message}") }
                .getOrDefault(listOf(RelayClient.IceServer(listOf("stun:stun.cloudflare.com:3478"), null, null)))
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
        val track = f.createAudioTrack("aegis-audio", source)
        track.setEnabled(_call.value?.muted != true)
        peer.addTrack(track, listOf("aegis-stream"))
        pc = peer
        audioSource = source
        audioTrack = track
    }

    private fun observer(callId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            serial.launch {
                if (_call.value?.id != callId) return@launch
                outgoingCandidates += candidate
                if (candidateFlush?.isActive != true) {
                    candidateFlush = serial.launch {
                        delay(CANDIDATE_BATCH_MS)
                        flushOutgoingCandidates()
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
                        _call.value = c.copy(phase = CallPhase.CONNECTED, connectedAt = if (c.connectedAt > 0L) c.connectedAt else System.currentTimeMillis())
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        if (c.phase == CallPhase.CONNECTED) _call.value = c.copy(phase = CallPhase.RECONNECTING)
                    PeerConnection.PeerConnectionState.FAILED -> endLocked("connection lost", signal = "hangup")
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

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private suspend fun flushOutgoingCandidates() {
        val c = _call.value ?: return
        if (outgoingCandidates.isEmpty() || c.phase == CallPhase.ENDED) return
        val batch = ArrayList(outgoingCandidates)
        outgoingCandidates.clear()
        val cands = JSONArray()
        for (cand in batch) cands.put(JSONObject().put("m", cand.sdpMid).put("i", cand.sdpMLineIndex).put("c", cand.sdp))
        CommsRepository.sendCallSignal(c.peer, JSONObject().put("k", "ice").put("cid", c.id).put("cands", cands))
    }

    private fun flushPendingRemoteCandidates() {
        val peer = pc ?: return
        for (cand in pendingRemoteCandidates) peer.addIceCandidate(cand)
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
     * sent to the other phone; [missed] records an unanswered incoming call.
     */
    private suspend fun endLocked(reason: String, signal: String?, missed: Boolean = false) {
        val c = _call.value ?: return
        if (c.phase == CallPhase.ENDED) return
        // Claim the teardown and release the microphone before the first
        // suspension: a hang-up and the peer's "end" can arrive together, and
        // the relay round trip below may take the full network timeout.
        _call.value = c.copy(phase = CallPhase.ENDED, endReason = reason)
        timeout?.cancel()
        candidateFlush?.cancel()
        outgoingCandidates.clear()
        pendingRemoteCandidates.clear()
        pendingOffer = null
        stopRinging()
        CommsNotifications.cancelIncomingCall(appContext)
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        pc = null; audioTrack = null; audioSource = null
        stopAudio()
        CallService.stop(appContext)
        if (signal != null) {
            CommsRepository.sendCallSignal(c.peer, JSONObject().put("k", "end").put("cid", c.id).put("reason", signal))
        }

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
        CommsRepository.releaseLiveLink()
        serial.launch {
            delay(ENDED_LINGER_MS)
            val still = _call.value
            if (still != null && still.id == c.id && still.phase == CallPhase.ENDED) _call.value = null
        }
    }

    // ── Audio and ringing ─────────────────────────────────────────────────

    private fun startAudio() {
        if (audio == null) audio = CallAudio(appContext).also { it.start() }
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
    }

    private fun startRinging() {
        if (ringer == null) ringer = Ringer(appContext).also { it.start() }
    }

    private fun stopRinging() {
        ringer?.stop()
        ringer = null
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

/** Puts the phone in call mode: voice-communication audio focus, earpiece by default, speaker on request. */
private class CallAudio(context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL

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
        setSpeaker(false)
    }

    fun setSpeaker(on: Boolean) {
        val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val device = manager.availableCommunicationDevices.firstOrNull { it.type == wanted }
            ?: manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (device != null) manager.setCommunicationDevice(device) else manager.clearCommunicationDevice()
    }

    fun stop() {
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
                if (it.hasVibrator()) it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
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
