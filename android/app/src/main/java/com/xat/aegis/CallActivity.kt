package com.xat.aegis

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.comms.ActiveCall
import com.xat.aegis.comms.CallManager
import com.xat.aegis.comms.CallPhase
import com.xat.aegis.comms.CommsNotifications
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.Contact
import com.xat.aegis.comms.Direction
import com.xat.aegis.comms.VoicemailRecorder
import com.xat.aegis.comms.durationLabel
import com.xat.aegis.comms.formatAegisNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The call screen that incoming-call and ongoing-call notifications open.
 *
 * Most calls arrive to a locked phone. The main screen stays behind the lock
 * like any app, so the full-screen ring used to land there and show nothing
 * until the phone was unlocked, by which time the call had often rung out.
 * This screen alone shows over the lock screen and turns the display on; it
 * holds nothing but the call, and closes when the call is over.
 *
 * One thing outlives the call here: when a call this phone placed ended
 * without ever connecting (no answer, declined, busy, the other phone
 * unreachable), the screen offers to leave a voicemail, and stays until that
 * is recorded and sent or declined.
 *
 * It is not exported: only Aegis's own notifications can open it, and an
 * answer request is honoured only for the call id the notification carries.
 */
class CallActivity : ComponentActivity() {

    private var acceptWhenAllowed: String? = null

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = acceptWhenAllowed
        acceptWhenAllowed = null
        if (granted) {
            if (id != null) CallManager.accept(id)
        } else {
            val blocked = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            Toast.makeText(
                this,
                if (blocked) "The microphone is blocked for Aegis. Allow it under Permissions to take calls." else "Answering needs the microphone.",
                Toast.LENGTH_LONG
            ).show()
            if (blocked) openAppDetails(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        CommsRepository.init(applicationContext)

        val live = CallManager.call.value?.takeIf { it.phase != CallPhase.ENDED }
        if (live == null) {
            // The process that rang was killed, or the call ended while the
            // notification was being tapped: there is nothing to show.
            CommsNotifications.cancelIncomingCall(this)
            Toast.makeText(this, "That call has ended", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        handle(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = CallGround, surface = CallPanel)) {
                Surface(color = CallGround, modifier = Modifier.fillMaxSize()) {
                    val call by CallManager.call.collectAsStateWithLifecycle()
                    val c = call
                    // The call the voicemail is for, kept once the call itself is gone
                    // from CallManager (it drops an ended call after a moment).
                    val offer = rememberVoicemailOffer(c)
                    LaunchedEffect(c == null, offer == null) { if (c == null && offer == null) finish() }
                    when {
                        offer != null && (c == null || c.phase == CallPhase.ENDED) ->
                            VoicemailScreen(offer.contact, c, Modifier.systemBarsPadding(), onDone = { offer.dismiss(); finish() })
                        c != null -> InCallScreen(c, Modifier.systemBarsPadding())
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Answers when the notification's Answer button opened this screen for the call that is ringing now. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(CommsNotifications.EXTRA_ACCEPT_CALL, false) != true) return
        intent.removeExtra(CommsNotifications.EXTRA_ACCEPT_CALL)
        val id = intent.getStringExtra(CommsNotifications.EXTRA_CALL_ID) ?: return
        val ringing = CallManager.call.value
        if (ringing == null || ringing.id != id || ringing.phase != CallPhase.INCOMING) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CallManager.accept(id)
            return
        }
        acceptWhenAllowed = id
        // The permission dialog cannot appear over the lock screen: unlock first.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                override fun onDismissCancelled() { acceptWhenAllowed = null }
                override fun onDismissError() { acceptWhenAllowed = null }
            })
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private companion object {
        val CallGround = Color(0xFF0E1116)
        val CallPanel = Color(0xFF161B23)
    }
}

// ── Voicemail after a call that did not connect ──────────────────────────────

/**
 * True for a call this phone placed that is over without the audio ever having
 * connected: the other side did not answer, declined, was busy, or could not be
 * reached. That is when a voicemail makes sense.
 */
internal fun ActiveCall.wantsVoicemail(): Boolean =
    phase == CallPhase.ENDED && direction == Direction.OUT && connectedAt == 0L

/** A voicemail offer on screen: who it is for, and how to take it down. */
internal class VoicemailOffer(val contact: Contact, val dismiss: () -> Unit)

/**
 * Tracks whether a voicemail should be offered for the call on screen. When
 * [call] ends unconnected, its peer is remembered and an offer returned, and
 * kept returned after CallManager lets go of the call, until the offer is
 * dismissed (declined, or the voicemail sent). Each call is offered once across
 * every screen watching it: the call screen and the COMMS tab can both be up
 * for one call, and one offer is enough.
 */
@Composable
internal fun rememberVoicemailOffer(call: ActiveCall?): VoicemailOffer? {
    var offer by rememberSaveable { mutableStateOf<String?>(null) }
    var peer by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(call?.id, call?.phase) {
        val c = call ?: return@LaunchedEffect
        if (c.wantsVoicemail() && offer == null && VoicemailOffers.claim(c.id, c.peer.number)) {
            offer = c.id
            peer = c.peer
        }
    }
    val id = offer ?: return null
    // Restored after a configuration change: the call is gone, the contact is looked up again.
    if (peer == null) {
        LaunchedEffect(id) {
            val number = VoicemailOffers.peerOf(id)
            val found = if (number == null) null else withContext(Dispatchers.IO) { CommsRepository.contact(number) }
            if (found != null) peer = found else offer = null
        }
    }
    val contact = peer ?: return null
    return VoicemailOffer(contact) { offer = null; peer = null }
}

/** Which ended calls have had their voicemail offered, so two screens do not both offer. */
internal object VoicemailOffers {
    private val claimed = LinkedHashMap<String, String>()

    @Synchronized
    fun claim(callId: String, peer: String): Boolean {
        if (claimed.containsKey(callId)) return false
        claimed[callId] = peer
        while (claimed.size > 8) claimed.remove(claimed.keys.first())
        return true
    }

    @Synchronized
    fun peerOf(callId: String): String? = claimed[callId]
}

/**
 * The screen after an unanswered outgoing call: what happened, and the offer to
 * leave a voicemail. [call] is the ended call while CallManager still has it.
 */
@Composable
internal fun VoicemailScreen(contact: Contact, call: ActiveCall?, modifier: Modifier = Modifier, onDone: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("CALL ENDED", color = VmMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(contact.name.ifBlank { formatAegisNumber(contact.number) }, color = VmInk, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            call?.endReason?.replaceFirstChar { it.uppercase() } ?: "Not answered",
            color = VmCaution, fontSize = 14.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(40.dp))
        VoicemailSection(contact, onDone = onDone)
    }
}

private sealed class VoicemailStep {
    object Idle : VoicemailStep()
    object Recording : VoicemailStep()
    class Recorded(val audioB64: String, val durationMs: Long) : VoicemailStep()
    class Sending(val audioB64: String, val durationMs: Long) : VoicemailStep()
}

/**
 * Records and sends a voicemail to [contact]: one round button to record (red
 * and pulsing while it records, with the time so far), then SEND VOICEMAIL or
 * DISCARD. The recording is AMR-NB, at most thirty seconds, and travels to the
 * contact inside an encrypted envelope like a message. [onDone] is called when
 * the voicemail has been sent, or the offer declined.
 */
@Composable
internal fun VoicemailSection(contact: Contact, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoicemailRecorder(context) }
    var step by remember { mutableStateOf<VoicemailStep>(VoicemailStep.Idle) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val label = contact.name.ifBlank { formatAegisNumber(contact.number) }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    fun finishRecording() {
        val result = recorder.stop()
        step = if (result == null) {
            error = "Nothing was recorded; hold the button a little longer."
            VoicemailStep.Idle
        } else {
            error = null
            VoicemailStep.Recorded(result.first, result.second)
        }
    }

    fun beginRecording() {
        error = null
        try {
            recorder.start()
            elapsed = 0L
            step = VoicemailStep.Recording
        } catch (e: IllegalStateException) {
            error = "Could not start recording: ${e.message}"
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
        else error = "Recording a voicemail needs the microphone."
    }

    // The counter while recording; the recording stops itself at the limit.
    if (step is VoicemailStep.Recording) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(100L)
                elapsed = recorder.elapsedMs
                if (elapsed >= VoicemailRecorder.MAX_DURATION_MS) {
                    finishRecording()
                    break
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = step) {
            VoicemailStep.Idle -> {
                Text("Leave a voicemail for $label?", color = VmInk, fontSize = 16.sp, textAlign = TextAlign.Center)
                RecordButton(recording = false) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                }
                Text("Tap to record · up to ${durationLabel(VoicemailRecorder.MAX_DURATION_MS)}", color = VmMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = onDone) { Text("NOT NOW", color = VmMuted, fontSize = 12.sp, letterSpacing = 1.sp) }
            }
            VoicemailStep.Recording -> {
                Text("Recording…", color = VmCritical, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                RecordButton(recording = true) { finishRecording() }
                Text(
                    "${durationLabel(elapsed)} / ${durationLabel(VoicemailRecorder.MAX_DURATION_MS)}",
                    color = VmInk, fontSize = 20.sp, fontFamily = FontFamily.Monospace
                )
                Text("Tap to stop", color = VmMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            is VoicemailStep.Recorded -> {
                Text("Voicemail recorded · ${durationLabel(s.durationMs)}", color = VmInk, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoicemailButton("DISCARD", VmCritical) {
                        step = VoicemailStep.Idle
                        error = null
                    }
                    VoicemailButton("SEND VOICEMAIL", VmClear) {
                        step = VoicemailStep.Sending(s.audioB64, s.durationMs)
                        scope.launch {
                            CommsRepository.sendVoicemail(contact, s.audioB64, s.durationMs)
                                .onSuccess {
                                    Toast.makeText(context, "Voicemail sent", Toast.LENGTH_SHORT).show()
                                    onDone()
                                }
                                .onFailure {
                                    error = "Not sent: ${it.message ?: "the relay could not be reached"}"
                                    step = VoicemailStep.Recorded(s.audioB64, s.durationMs)
                                }
                        }
                    }
                }
            }
            is VoicemailStep.Sending -> {
                CircularProgressIndicator(color = VmClear, modifier = Modifier.size(36.dp))
                Text("Sending encrypted voicemail…", color = VmMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        error?.let { Text(it, color = VmCritical, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp)) }
        Text(
            "The recording is encrypted to $label's keys like a message; the relay only carries it.",
            color = VmMuted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp)
        )
    }
}

/** The big round record button: a microphone on a disc, red and pulsing while [recording]. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val halo by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "halo"
    )
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(Modifier.fillMaxSize().scale(1f + (1f - halo) * 0.25f).background(VmCritical.copy(alpha = halo), CircleShape))
        }
        Box(
            Modifier
                .size(96.dp)
                .scale(if (recording) scale else 1f)
                .background(if (recording) VmCritical else VmPanelHi, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                Box(Modifier.size(30.dp).background(Color(0xFF12161D), RoundedCornerShape(6.dp)))
            } else {
                MicrophoneGlyph(VmInk, Modifier.size(40.dp))
            }
        }
    }
}

/** A microphone drawn in place: a capsule, its cradle and a stand. */
@Composable
private fun MicrophoneGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val capsuleW = w * 0.34f
        val capsuleH = h * 0.52f
        val capsuleLeft = (w - capsuleW) / 2f
        drawRoundRect(colour, topLeft = Offset(capsuleLeft, h * 0.04f), size = Size(capsuleW, capsuleH), cornerRadius = CornerRadius(capsuleW / 2f))
        val cradle = Rect(Offset(w * 0.2f, h * 0.18f), Size(w * 0.6f, h * 0.56f))
        drawArc(colour, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = cradle.topLeft, size = cradle.size, style = Stroke(stroke))
        drawLine(colour, Offset(w / 2f, h * 0.74f), Offset(w / 2f, h * 0.9f), strokeWidth = stroke)
        drawLine(colour, Offset(w * 0.32f, h * 0.92f), Offset(w * 0.68f, h * 0.92f), strokeWidth = stroke)
    }
}

@Composable
private fun VoicemailButton(label: String, colour: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Color(0xFF12161D)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.height(56.dp).widthIn(min = 130.dp)
    ) { Text(label, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 13.sp) }
}

// Colour tokens, the same as the COMMS tab's.
private val VmInk = Color(0xFFE6EAF1)
private val VmMuted = Color(0xFF6F7A8B)
private val VmPanelHi = Color(0xFF1D2430)
private val VmCritical = Color(0xFFF2545B)
private val VmCaution = Color(0xFFE8B33D)
private val VmClear = Color(0xFF3DB88A)
