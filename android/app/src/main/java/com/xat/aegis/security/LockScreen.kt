package com.xat.aegis.security

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

private val LkGround  = Color(0xFF0E1116)
private val LkPanel   = Color(0xFF161B23)
private val LkInk     = Color(0xFFE6EAF1)
private val LkInkDim  = Color(0xFFA8B2C1)
private val LkMuted   = Color(0xFF6F7A8B)
private val LkRule    = Color(0xFF262E3A)
private val LkAccent  = Color(0xFFFF7A3D)
private val LkCrit    = Color(0xFFF2545B)

/**
 * Full-screen PIN entry that gates the rest of the app.
 * Appears when [AppLock.locked] is true. Never tells the user how
 * many attempts remain, or that a wipe limit exists.
 */
@Composable
fun LockScreen() {
    val context  = LocalContext.current
    val activity = context as? FragmentActivity
    val scope    = rememberCoroutineScope()

    var code        by remember { mutableStateOf("") }
    var checking    by remember { mutableStateOf(false) }
    var shake       by remember { mutableStateOf(false) }
    var errorText   by remember { mutableStateOf("") }
    var lockoutMs   by remember { mutableLongStateOf(0L) }
    var countdown   by remember { mutableLongStateOf(0L) }
    val maxLen = AppLock.MAX_LENGTH
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()

    // Countdown ticker for the lockout display, on the same monotonic clock as the lock itself.
    LaunchedEffect(lockoutMs) {
        if (lockoutMs <= 0L) return@LaunchedEffect
        val deadline = SystemClock.elapsedRealtime() + lockoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) { countdown = 0L; break }
            countdown = remaining
            delay(500L)
        }
    }

    // The prompt only opens with a cipher the Keystore will complete for an
    // authenticated strong biometric; the key behind it dies with a new enrolment.
    fun promptBiometric() {
        val act = activity ?: return
        scope.launch {
            when (val gate = withContext(Dispatchers.IO) { AppLock.biometricGate(context) }) {
                is AppLock.BiometricGate.Ready -> showBiometricPrompt(act, gate.cipher) { msg -> errorText = msg }
                AppLock.BiometricGate.Invalidated ->
                    errorText = "Biometrics on this phone changed. Enter your passcode; biometric unlock can be switched on again in Settings."
                AppLock.BiometricGate.Unavailable -> Unit
            }
        }
    }

    // Auto-show the biometric prompt when the lock screen first appears.
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && activity != null) promptBiometric()
    }

    // Auto-submit once the code reaches the set length, OR on an explicit
    // check for shorter codes (≥ MIN_LENGTH) by tapping the last key.
    fun submit() {
        if (code.length < AppLock.MIN_LENGTH || checking) return
        checking = true
        errorText = ""
        scope.launch {
            val verdict = withContext(Dispatchers.IO) {
                AppLock.attempt(context, code, unlockOnSuccess = true)
            }
            code = ""
            checking = false
            when (verdict) {
                AppLock.Verdict.Accepted -> { /* AppLock.locked will flip to false */ }
                // A wipe, whether from the duress passcode or the failure limit,
                // must look exactly like one more wrong passcode until the
                // process goes: the same shake, the same words, no blank pause.
                AppLock.Verdict.Wiping -> {
                    shake = true
                    errorText = "Wrong passcode"
                    delay(400L)
                    shake = false
                }
                is AppLock.Verdict.Rejected -> {
                    shake = true
                    lockoutMs = verdict.lockedOutForMs
                    errorText = if (verdict.lockedOutForMs > 0L) "" else "Wrong passcode"
                    delay(400L)
                    shake = false
                }
                is AppLock.Verdict.LockedOut -> {
                    lockoutMs = verdict.remainingMs
                    errorText = ""
                }
                is AppLock.Verdict.Unavailable -> {
                    errorText = "Passcode check unavailable — ${verdict.message}"
                }
            }
        }
    }

    fun tap(digit: Char) {
        if (checking || countdown > 0L) return
        if (code.length < maxLen) {
            code += digit
            if (code.length == maxLen) submit()
        }
    }

    fun backspace() {
        if (checking || countdown > 0L) return
        if (code.isNotEmpty()) code = code.dropLast(1)
    }

    // Horizontal shake offset on wrong passcode.
    val shakeAnim = rememberInfiniteTransition(label = "shake")
    val shakeX by shakeAnim.animateFloat(
        initialValue = 0f,
        targetValue = if (shake) 10f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeX"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(LkGround)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // ── Wordmark ───────────────────────────────────────────────
            Text(
                "AEGIS",
                color = LkInk,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Text(
                "Enter passcode to continue",
                color = LkMuted,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
            )

            // ── Dots ───────────────────────────────────────────────────
            val dotSpacing = if (maxLen > 6) 10.dp else 14.dp
            val dotSize = if (maxLen > 6) 11.dp else 13.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(dotSpacing),
                modifier = Modifier.offset(x = if (shake) shakeX.dp else 0.dp)
            ) {
                repeat(maxLen) { i ->
                    val filled = i < code.length
                    Box(
                        Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(if (filled) LkAccent else LkRule)
                            .then(
                                if (!filled) Modifier.border(1.dp, LkMuted, CircleShape)
                                else Modifier
                            )
                    )
                }
            }

            // ── Error / lockout text ───────────────────────────────────
            val showLockout = countdown > 0L
            AnimatedVisibility(visible = showLockout || errorText.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                val msg = when {
                    showLockout -> {
                        val s = (countdown / 1000L).coerceAtLeast(1L)
                        if (s >= 3600L) "Try again in ${s / 3600}h ${(s % 3600) / 60}m"
                        else if (s >= 60L) "Try again in ${s / 60}m ${s % 60}s"
                        else "Try again in ${s}s"
                    }
                    else -> errorText
                }
                Text(msg, color = LkCrit, fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            // ── Numpad ─────────────────────────────────────────────────
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9'),
            )
            val active = !checking && countdown == 0L
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (d in row) {
                            NumKey(label = d.toString(), active = active) { tap(d) }
                        }
                    }
                }
                // Bottom row: empty · 0 · ⌫
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(68.dp))
                    NumKey(label = "0", active = active) { tap('0') }
                    NumKey(label = "⌫", active = active && code.isNotEmpty(), accent = false) { backspace() }
                }
            }

            // ── Submit (for codes shorter than MAX_LENGTH) ─────────────
            if (code.length in AppLock.MIN_LENGTH until maxLen) {
                Text(
                    "UNLOCK",
                    color = if (active) LkAccent else LkMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LkAccent.copy(alpha = 0.12f))
                        .clickable(enabled = active) { submit() }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }

            // ── Biometric unlock button ────────────────────────────────
            if (biometricEnabled && activity != null && code.isEmpty()) {
                Text(
                    "USE BIOMETRICS",
                    color = if (active) LkInkDim else LkMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = active) { promptBiometric() }
                        .padding(horizontal = 20.dp, vertical = 9.dp)
                )
            }
        }
    }
}

/**
 * Shows the system prompt over [cipher]. Only a strong (class 3) biometric may
 * satisfy it, because that is all the key behind the cipher accepts; the
 * success callback then proves itself by finishing the operation, and a
 * callback whose cipher the Keystore still refuses unlocks nothing.
 */
private fun showBiometricPrompt(activity: FragmentActivity, cipher: Cipher, onError: (String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            AppLock.unlockWithBiometric(result.cryptoObject?.cipher)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // A cancel or a tap on "Use passcode" is not something to report; anything
            // else (lockout, hardware failure) leaves the user staring at nothing.
            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                onError(errString.toString())
            }
        }
        override fun onAuthenticationFailed() {}
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Aegis")
        .setSubtitle("Confirm your identity to continue")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use passcode")
        .build()
    runCatching { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
}

@Composable
private fun NumKey(label: String, active: Boolean, accent: Boolean = true, onClick: () -> Unit) {
    val bg    = if (active) LkPanel else LkGround
    val color = if (active) (if (accent) LkInk else LkInkDim) else LkMuted
    Box(
        Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, LkRule, CircleShape)
            .then(if (active) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = color,
            fontSize = if (label.length == 1) 22.sp else 18.sp,
            fontFamily = if (label == "⌫") FontFamily.Default else FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
