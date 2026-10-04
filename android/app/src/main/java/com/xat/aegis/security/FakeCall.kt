package com.xat.aegis.security

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.xat.aegis.CallActivity
import com.xat.aegis.comms.CallManager
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.formatAegisNumber
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * A fake incoming call: a way out of a conversation or a situation that is
 * going wrong. The owner schedules it for 30 s, 2 min or 5 min ahead; the phone
 * then rings with its own ringtone and vibration and shows an incoming-call
 * screen for a trusted contact of their choosing. Answering either holds a
 * pretend call (a timer and an end button, so the owner can talk to nobody)
 * or, when a contact's Aegis number was given, places a real encrypted call
 * to them through [CallManager].
 */
object FakeCall {

    const val DELAY_30S = 30_000L
    const val DELAY_2MIN = 2 * 60_000L
    const val DELAY_5MIN = 5 * 60_000L

    private const val PREFS = "fake_call"
    private const val KEY_CALLER_NAME = "caller_name"
    private const val KEY_CALLER_NUMBER = "caller_number"
    private const val KEY_PENDING_CONTACT = "pending_contact"
    private const val KEY_PENDING_AT = "pending_at"

    const val CHANNEL = "fake_call"
    const val NOTIFICATION_ID = 0x5D00_0001
    private const val ALARM_REQUEST = 0x5D00_0002

    const val ACTION_RING = "com.xat.aegis.FAKE_CALL_RING"
    const val ACTION_SHOW = "com.xat.aegis.FAKE_CALL_SHOW"
    const val ACTION_ANSWER = "com.xat.aegis.FAKE_CALL_ANSWER"
    const val ACTION_DECLINE = "com.xat.aegis.FAKE_CALL_DECLINE"
    const val EXTRA_CONTACT = "com.xat.aegis.FAKE_CALL_CONTACT"

    /** Who the screen says is calling. */
    data class Caller(val name: String, val number: String?)

    /** The contact the fake call appears to come from. Defaults to a plausible one. */
    fun caller(context: Context): Caller {
        val p = prefs(context)
        return Caller(p.getString(KEY_CALLER_NAME, null)?.takeIf { it.isNotBlank() } ?: "Mum", p.getString(KEY_CALLER_NUMBER, null))
    }

    fun setCaller(context: Context, name: String, number: String?) {
        prefs(context).edit().putString(KEY_CALLER_NAME, name.trim().take(40)).putString(KEY_CALLER_NUMBER, number?.trim()).apply()
    }

    /**
     * Rings in [delayMs]. [contactNumber], when given, is the Aegis number of the
     * contact to really call if the owner answers. Uses an exact, idle-proof alarm
     * when the owner has allowed exact alarms; otherwise an inexact one, which for
     * these short delays with the screen on fires on time in practice.
     */
    fun schedule(context: Context, delayMs: Long, contactNumber: String?) {
        val app = context.applicationContext
        ensureChannel(app)
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        prefs(app).edit().putString(KEY_PENDING_CONTACT, contactNumber).putLong(KEY_PENDING_AT, at).apply()
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(app, contactNumber)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Cancels a scheduled call and dismisses one that is ringing. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app, null))
        prefs(app).edit().remove(KEY_PENDING_CONTACT).remove(KEY_PENDING_AT).apply()
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** When the pending fake call will ring, or null when none is scheduled. */
    fun pendingAt(context: Context): Long? =
        prefs(context).getLong(KEY_PENDING_AT, 0L).takeIf { it > System.currentTimeMillis() }

    /** Whether exact alarms are allowed; [requestExactAlarms] opens the setting when not. */
    fun canScheduleExactly(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun requestExactAlarms(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Rings now: posts the full-screen call notification (which opens
     * [FakeCallActivity] over a locked or dark screen) and, when Aegis is allowed
     * to, opens the screen directly as well.
     */
    fun ring(context: Context, contactNumber: String?) {
        val app = context.applicationContext
        ensureChannel(app)
        prefs(app).edit().remove(KEY_PENDING_AT).apply()
        val who = caller(app)
        val show = screenIntent(app, ACTION_SHOW, contactNumber, 1)
        val answer = screenIntent(app, ACTION_ANSWER, contactNumber, 2)
        val decline = PendingIntent.getBroadcast(
            app, NOTIFICATION_ID + 3,
            Intent(app, FakeCallReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val person = Person.Builder().setName(who.name).setImportant(true).build()
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming call")
            .setContentText(who.name)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(RING_MS + 5_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
        }
        // Allowed only while Aegis is in the foreground (or just left it); the
        // full-screen intent above covers the rest.
        runCatching {
            app.startActivity(
                Intent(app, FakeCallActivity::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_CONTACT, contactNumber)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    internal const val RING_MS = 45_000L

    private fun screenIntent(app: Context, action: String, contact: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(
            app, NOTIFICATION_ID + code,
            Intent(app, FakeCallActivity::class.java).setAction(action).putExtra(EXTRA_CONTACT, contact)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmIntent(app: Context, contact: String?): PendingIntent =
        PendingIntent.getBroadcast(
            app, ALARM_REQUEST,
            Intent(app, FakeCallReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_CONTACT, contact),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ensureChannel(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming calls (safety)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings for a call you scheduled as a way out of a situation"
                // The screen plays the phone's own ringtone; the channel adds vibration
                // that survives Android muting the app's own.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 1200, 800, 1200)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The alarm lands here; also the Decline button of the notification. */
class FakeCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FakeCall.ACTION_RING -> FakeCall.ring(context, intent.getStringExtra(FakeCall.EXTRA_CONTACT))
            FakeCall.ACTION_DECLINE -> FakeCall.cancel(context)
        }
    }
}

private val FcGround = Color(0xFF0B0F14)
private val FcInk = Color(0xFFF2F4F7)
private val FcDim = Color(0xFF9AA4B2)
private val FcAnswer = Color(0xFF2EBD59)
private val FcDecline = Color(0xFFE5484D)
private val FcAvatar = Color(0xFF3A4A5E)

/**
 * The incoming-call screen of a [FakeCall]. Shows over the lock screen and turns
 * the display on; excluded from recents so nothing is left behind. Plays the
 * phone's actual default ringtone and vibrates in the standard cadence, both
 * honouring the ringer mode, until answered, declined or [FakeCall.RING_MS] pass.
 */
class FakeCallActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var contactNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        contactNumber = intent?.getStringExtra(FakeCall.EXTRA_CONTACT)
        val who = FakeCall.caller(this)
        val answerNow = intent?.action == FakeCall.ACTION_ANSWER
        if (!answerNow) startRinging()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = FcGround, surface = FcGround)) {
                Surface(color = FcGround, modifier = Modifier.fillMaxSize()) {
                    var answered by remember { mutableStateOf(answerNow) }
                    LaunchedEffect(answered) { if (answered) onAnswer() }
                    if (!answered) {
                        LaunchedEffect(Unit) { delay(FakeCall.RING_MS); dismiss() }
                        Ringing(who, onDecline = { dismiss() }, onAnswer = { answered = true })
                    } else {
                        InCall(who, onEnd = { dismiss() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == FakeCall.ACTION_ANSWER) { stopRinging(); onAnswer() }
    }

    /**
     * Answer: the ringing stops and the notification goes. With a contact's Aegis
     * number and the microphone permission, a real encrypted call is placed and
     * the real call screen takes over; otherwise this screen stays as a pretend
     * call in progress.
     */
    private fun onAnswer() {
        stopRinging()
        getSystemService(NotificationManager::class.java)?.cancel(FakeCall.NOTIFICATION_ID)
        val number = contactNumber ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching { CommsRepository.init(applicationContext) }
        val contact = runCatching { CommsRepository.contact(number) }.getOrNull() ?: return
        CallManager.place(contact)
        startActivity(
            Intent(this, CallActivity::class.java).setAction("com.xat.aegis.OPEN_CALL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun dismiss() {
        stopRinging()
        getSystemService(NotificationManager::class.java)?.cancel(FakeCall.NOTIFICATION_ID)
        finish()
    }

    private fun startRinging() {
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:fake-call")
                ?.apply { setReferenceCounted(false); acquire(FakeCall.RING_MS + 5_000L) }
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL && ringtone == null) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = runCatching {
                RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && vibrator == null) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vm.defaultVibrator.also {
                if (!it.hasVibrator()) return@also
                val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                }
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}

@Composable
private fun Avatar(name: String) {
    val initials = name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    Box(
        Modifier.size(112.dp).background(FcAvatar, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = FcInk, fontSize = 40.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Ringing(who: FakeCall.Caller, onDecline: () -> Unit, onAnswer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Incoming call", color = FcDim, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Avatar(who.name)
        Spacer(Modifier.height(20.dp))
        Text(who.name, color = FcInk, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(who.number?.let { formatAegisNumber(it) } ?: "Mobile", color = FcDim, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onDecline, colors = ButtonDefaults.buttonColors(containerColor = FcDecline), shape = CircleShape, modifier = Modifier.size(80.dp)) {
                Text("End", color = FcInk, fontSize = 14.sp)
            }
            Button(onClick = onAnswer, colors = ButtonDefaults.buttonColors(containerColor = FcAnswer), shape = CircleShape, modifier = Modifier.size(80.dp)) {
                Text("Answer", color = FcInk, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InCall(who: FakeCall.Caller, onEnd: () -> Unit) {
    var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        started = System.currentTimeMillis()
        while (true) { elapsed = System.currentTimeMillis() - started; delay(1_000L) }
    }
    val s = elapsed / 1000
    val clock = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(clock, color = FcDim, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Avatar(who.name)
        Spacer(Modifier.height(20.dp))
        Text(who.name, color = FcInk, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(who.number?.let { formatAegisNumber(it) } ?: "Mobile", color = FcDim, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = FcDecline), shape = CircleShape, modifier = Modifier.size(80.dp)) {
            Text("End", color = FcInk, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
