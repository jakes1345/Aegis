package com.xat.aegis

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.comms.CallManager
import com.xat.aegis.comms.CallPhase
import com.xat.aegis.comms.CommsNotifications
import com.xat.aegis.comms.CommsRepository

/**
 * The call screen that incoming-call and ongoing-call notifications open.
 *
 * Most calls arrive to a locked phone. The main screen stays behind the lock
 * like any app, so the full-screen ring used to land there and show nothing
 * until the phone was unlocked, by which time the call had often rung out.
 * This screen alone shows over the lock screen and turns the display on; it
 * holds nothing but the call, and closes when the call is over.
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
                    LaunchedEffect(c == null) { if (c == null) finish() }
                    if (c != null) {
                        InCallScreen(c, Modifier.systemBarsPadding())
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
