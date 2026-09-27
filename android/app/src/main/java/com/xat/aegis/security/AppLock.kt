package com.xat.aegis.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.xat.aegis.Registry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The app passcode: what it takes to get past the lock screen, and what happens
 * when someone who does not know it keeps trying.
 *
 * The passcode itself is never stored. What is kept is a verifier,
 * HMAC-SHA256(key, domain ‖ salt ‖ passcode), where the key is an HMAC key in
 * the Android Keystore (StrongBox when the phone has one) that never leaves
 * secure hardware. A copy of the app's files is therefore no help in guessing
 * a six-digit code offline: every guess has to be put to this phone's Keystore,
 * through this code, which counts it.
 *
 * Every attempt is counted on disk *before* it is checked, with a synchronous
 * commit, so killing the app between a wrong guess and its bookkeeping cannot
 * give an attempt back; and a counter found already at the limit (an attempt
 * that was counted but whose verdict never landed) wipes before anything is
 * checked, so the kill cannot buy a further guess either. Reaching the owner's
 * limit, or entering the duress passcode, starts [Wiper]; nothing on screen
 * says which happened, or that a limit exists at all. With the wipe switched
 * off, repeated failures are slowed by an escalating lockout instead, timed on
 * the monotonic clock so that changing the date cannot shorten it.
 *
 * A verifier without its Keystore key cannot come from this app on this phone
 * (restored files, or someone deleting the key to get round the lock), so it
 * is treated as tampering and wipes as well.
 *
 * Biometric unlock is not a bare prompt: it decrypts with a Keystore key that
 * demands a strong biometric per use and that Android destroys when a
 * fingerprint or face is added ([KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment]).
 * A newly enrolled finger therefore cannot open Aegis; the passcode is needed,
 * and biometric unlock has to be switched on again from Settings.
 *
 * What none of this defends against is root or a debuggable build: whoever can
 * edit the app's private files can zero the counter or delete the verifier.
 */
object AppLock {

    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    /** Wipe after this many wrong passcodes in a row; 0 means never wipe (lock out instead). */
    const val DEFAULT_WIPE_AFTER = 3
    val WIPE_AFTER_CHOICES = listOf(1, 3, 5, 10, 0)

    /**
     * How long Aegis may be in the background before it asks again.
     * 30 s is the default so that system overlays — permission dialogs,
     * biometric prompts — don't lock the app mid-flow; "immediately" is
     * still a user choice via the Settings.
     */
    const val DEFAULT_RELOCK_MS = 30_000L
    val RELOCK_CHOICES = listOf(0L, 30_000L, 60_000L, 5 * 60_000L)

    internal const val KEY_ALIAS = "aegis_applock_v1"
    /** AES key that only a strong biometric can use, and that a new enrolment destroys. */
    internal const val BIOMETRIC_KEY_ALIAS = "aegis_applock_bio_v1"
    internal const val PREFS_NAME = "app_lock"

    private const val K_SALT = "salt"
    private const val K_VERIFIER = "verifier"
    private const val K_DURESS_SALT = "duress_salt"
    private const val K_DURESS_VERIFIER = "duress_verifier"
    private const val K_FAILED = "failed"
    /** [SystemClock.elapsedRealtime] of the last wrong guess: monotonic, so the date cannot be moved to get past it. */
    private const val K_LAST_FAIL = "last_fail_elapsed"
    private const val K_WIPE_AFTER = "wipe_after"
    private const val K_RELOCK_MS = "relock_ms"
    private const val K_BIOMETRIC = "biometric_enabled"

    private const val SALT_LEN = 16
    private val DOMAIN = "aegis-applock-v1".toByteArray(Charsets.UTF_8)
    /** Compared against when no duress verifier exists, so that check costs the same either way. */
    private val NO_VERIFIER = ByteArray(32)

    /** With the wipe off: wrong guesses allowed before the lockout starts, and how it grows. */
    private const val FREE_ATTEMPTS = 5
    private const val LOCKOUT_BASE_MS = 30_000L
    private const val LOCKOUT_MAX_MS = 60 * 60_000L

    /** The outcome of putting a passcode to the lock. */
    sealed interface Verdict {
        /** The right passcode. */
        data object Accepted : Verdict
        /** Wrong. [lockedOutForMs] is how long before the next attempt is taken (0: now). */
        data class Rejected(val lockedOutForMs: Long) : Verdict
        /** Refused unchecked: still locked out from earlier failures. */
        data class LockedOut(val remainingMs: Long) : Verdict
        /** The wipe has started; the process is on its way out. */
        data object Wiping : Verdict
        /** The Keystore failed; the attempt was not counted. */
        data class Unavailable(val message: String) : Verdict
    }

    private val random = SecureRandom()
    private val attemptLock = Any()

    private val _enabled = MutableStateFlow(false)
    /** Whether a passcode is set. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _locked = MutableStateFlow(false)
    /** Whether the app is behind the lock screen right now. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    /** Whether biometric unlock is enabled (requires a passcode to be set). */
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    @Volatile private var initialised = false

    /** When the app last left the screen ([SystemClock.elapsedRealtime]); 0 while it is on screen. */
    @Volatile private var backgroundedAt = 0L

    /** Reads whether a passcode is set. A new process always starts locked. Idempotent. */
    fun init(context: Context) {
        synchronized(this) {
            if (initialised) return
            val on = isEnabled(context)
            _enabled.value = on
            _locked.value = on
            _biometricEnabled.value = on && isBiometricEnabled(context)
            initialised = true
        }
    }

    /** Whether a passcode is set, read from disk (usable from services and receivers). */
    fun isEnabled(context: Context): Boolean =
        runCatching { prefs(context).contains(K_VERIFIER) }.getOrDefault(false)

    fun hasDuress(context: Context): Boolean =
        runCatching { prefs(context).contains(K_DURESS_VERIFIER) }.getOrDefault(false)

    fun isBiometricEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(K_BIOMETRIC, false) }.getOrDefault(false)

    /**
     * Switches biometric unlock on or off. Turning it on makes a fresh Keystore
     * key bound to the biometrics enrolled right now; it fails (false) when the
     * lock is off or the phone has no strong biometric enrolled. Blocking.
     */
    fun setBiometricEnabled(context: Context, enabled: Boolean): Boolean {
        synchronized(attemptLock) {
            val p = prefs(context)
            if (!enabled || !p.contains(K_VERIFIER)) {
                deleteBiometricKey()
                p.edit().putBoolean(K_BIOMETRIC, false).commit()
                _biometricEnabled.value = false
                return !enabled
            }
            val made = runCatching { createBiometricKey() }.isSuccess
            if (!made) {
                deleteBiometricKey()
                p.edit().putBoolean(K_BIOMETRIC, false).commit()
                _biometricEnabled.value = false
                return false
            }
            p.edit().putBoolean(K_BIOMETRIC, true).commit()
            _biometricEnabled.value = true
            return true
        }
    }

    /** What the lock screen gets when it asks to show the biometric prompt. */
    sealed interface BiometricGate {
        /** Hand [cipher] to the prompt as its CryptoObject; only an authenticated biometric can complete it. */
        class Ready(val cipher: Cipher) : BiometricGate
        /** A biometric was added or the key is gone: biometric unlock has just been switched off. */
        data object Invalidated : BiometricGate
        /** Biometric unlock is off, or the Keystore is not answering; use the passcode. */
        data object Unavailable : BiometricGate
    }

    /**
     * Prepares the biometric unlock: an encrypt operation under the enrolment-bound
     * key that the BiometricPrompt has to authorise. Blocking (Keystore work).
     */
    fun biometricGate(context: Context): BiometricGate {
        if (Wiper.isActive || !_biometricEnabled.value) return BiometricGate.Unavailable
        val key = try {
            (keyStore().getEntry(BIOMETRIC_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: UnrecoverableKeyException) {
            // On some OEM devices an invalidated biometric key throws here instead
            // of reading back null; treat it the same as a new enrolment.
            revokeBiometric(context)
            return BiometricGate.Invalidated
        } catch (e: KeyPermanentlyInvalidatedException) {
            revokeBiometric(context)
            return BiometricGate.Invalidated
        } catch (e: Exception) {
            return BiometricGate.Unavailable
        }
        if (key == null) {
            // The key does not survive a new enrolment on some devices; nor a Keystore reset.
            revokeBiometric(context)
            return BiometricGate.Invalidated
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            BiometricGate.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            revokeBiometric(context)
            BiometricGate.Invalidated
        } catch (e: Exception) {
            BiometricGate.Unavailable
        }
    }

    /**
     * Unlocks the app after the BiometricPrompt authorised [cipher]: the proof is
     * that the operation the Keystore refused before now completes. A success
     * callback without a usable cipher opens nothing. Does not touch the attempt
     * counter; the sensor's own lockout paces biometric tries.
     */
    fun unlockWithBiometric(cipher: Cipher?): Boolean {
        if (Wiper.isActive || !_biometricEnabled.value || cipher == null) return false
        val proven = runCatching { cipher.doFinal(ByteArray(16)); true }.getOrDefault(false)
        if (!proven) return false
        _locked.value = false
        return true
    }

    private fun revokeBiometric(context: Context) {
        synchronized(attemptLock) {
            deleteBiometricKey()
            prefs(context).edit().putBoolean(K_BIOMETRIC, false).commit()
            _biometricEnabled.value = false
        }
    }

    fun wipeAfter(context: Context): Int = prefs(context).getInt(K_WIPE_AFTER, DEFAULT_WIPE_AFTER)

    fun setWipeAfter(context: Context, attempts: Int) {
        require(attempts in WIPE_AFTER_CHOICES) { "unsupported wipe threshold $attempts" }
        synchronized(attemptLock) {
            // A lowered limit applies to the failures already on the counter; start
            // it again so changing the setting cannot itself set off the wipe.
            prefs(context).edit().putInt(K_WIPE_AFTER, attempts).putInt(K_FAILED, 0).remove(K_LAST_FAIL).commit()
        }
    }

    fun relockAfterMs(context: Context): Long = prefs(context).getLong(K_RELOCK_MS, DEFAULT_RELOCK_MS)

    fun setRelockAfterMs(context: Context, ms: Long) {
        require(ms in RELOCK_CHOICES) { "unsupported relock delay $ms" }
        prefs(context).edit().putLong(K_RELOCK_MS, ms).commit()
    }

    // ── Locking ─────────────────────────────────────────────────────────────

    /** Puts the app behind the lock screen, if a passcode is set, and closes the card vault. */
    fun lock() {
        if (!_enabled.value) return
        _locked.value = true
        Registry.lockVault()
    }

    /** The last Aegis screen has left the foreground. */
    fun onBackground(context: Context) {
        if (!_enabled.value) return
        if (relockAfterMs(context) <= 0L) {
            lock()
            backgroundedAt = 0L
        } else {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    /** An Aegis screen has come to the foreground. */
    fun onForeground(context: Context) {
        val since = backgroundedAt
        backgroundedAt = 0L
        if (!_enabled.value || since == 0L) return
        if (SystemClock.elapsedRealtime() - since >= relockAfterMs(context)) lock()
    }

    // ── Checking ────────────────────────────────────────────────────────────

    /**
     * Puts [code] to the lock. Blocking (Keystore work and a synchronous disk
     * write); call it off the main thread. With [unlockOnSuccess] false the
     * right passcode only confirms the owner (changing settings) and the lock
     * state is left alone; a wrong one counts the same either way.
     */
    fun attempt(context: Context, code: String, unlockOnSuccess: Boolean = true): Verdict =
        synchronized(attemptLock) { attemptLocked(context.applicationContext, code, unlockOnSuccess) }

    private fun attemptLocked(context: Context, code: String, unlockOnSuccess: Boolean): Verdict {
        if (Wiper.isActive) return Verdict.Wiping
        val p = prefs(context)
        val salt = p.bytes(K_SALT)
        val verifier = p.bytes(K_VERIFIER)
        if (salt == null || verifier == null) {
            // No passcode is set, so there is nothing to get past.
            if (unlockOnSuccess) _locked.value = false
            return Verdict.Accepted
        }
        // A too-short or empty code — tapping TURN OFF with an empty field, say — is
        // not a real guess. Reject it without spending an attempt, so it can never
        // count towards the wipe.
        if (code.length < MIN_LENGTH) return Verdict.Rejected(lockedOutForMs = 0L)
        val wipeAfter = p.getInt(K_WIPE_AFTER, DEFAULT_WIPE_AFTER)
        val failed = p.getInt(K_FAILED, 0)
        val lastFail = p.getLong(K_LAST_FAIL, 0L)

        if (wipeAfter > 0 && failed >= wipeAfter) {
            // The counter reached the limit on an attempt whose verdict never
            // landed (the process was killed mid-check). Killing the app is not a
            // way to keep guessing: the wipe the limit called for happens now.
            Wiper.start(context)
            return Verdict.Wiping
        }
        if (wipeAfter == 0) {
            val remaining = lockoutRemaining(p, failed)
            if (remaining > 0L) return Verdict.LockedOut(remaining)
        }

        // Counted before it is checked, and synchronously: a process killed
        // mid-check has still spent the attempt.
        val counted = p.edit()
            .putInt(K_FAILED, failed + 1)
            .putLong(K_LAST_FAIL, SystemClock.elapsedRealtime())
            .commit()
        if (!counted) return Verdict.Unavailable("Could not record the attempt")

        val key = try {
            existingKey()
        } catch (e: Exception) {
            giveBack(p, failed, lastFail)
            return Verdict.Unavailable("The Keystore is not responding")
        }
        if (key == null) {
            // A verifier whose key is gone was not left by this app on this phone.
            Wiper.start(context)
            return Verdict.Wiping
        }

        val input = code.toByteArray(Charsets.UTF_8)
        val (isMain, isDuress) = try {
            val main = MessageDigest.isEqual(mac(key, salt, input), verifier)
            val duressSalt = p.bytes(K_DURESS_SALT)
            val duressVerifier = p.bytes(K_DURESS_VERIFIER)
            // The same Keystore work and the same comparison whether or not a
            // duress code is set, so the time a check takes does not tell anyone
            // that one exists. MessageDigest.isEqual is constant-time for equal
            // lengths, and every verifier here is 32 bytes.
            val duressMac = mac(key, duressSalt ?: salt, input)
            val duressMatches = MessageDigest.isEqual(duressMac, duressVerifier ?: NO_VERIFIER)
            val duress = duressSalt != null && duressVerifier != null && duressMatches
            main to duress
        } catch (e: Exception) {
            // A Keystore failure is not a wrong guess; give the attempt back.
            giveBack(p, failed, lastFail)
            return Verdict.Unavailable("The Keystore could not check the passcode")
        } finally {
            input.fill(0)
        }

        return when {
            isMain -> {
                p.edit().putInt(K_FAILED, 0).remove(K_LAST_FAIL).commit()
                if (unlockOnSuccess) _locked.value = false
                Verdict.Accepted
            }
            isDuress -> {
                Wiper.start(context)
                Verdict.Wiping
            }
            wipeAfter > 0 && failed + 1 >= wipeAfter -> {
                Wiper.start(context)
                Verdict.Wiping
            }
            else -> Verdict.Rejected(if (wipeAfter == 0) lockoutRemaining(p, failed + 1) else 0L)
        }
    }

    /** The Keystore, not the guess, failed: the pre-counted attempt is returned. */
    private fun giveBack(p: SharedPreferences, failed: Int, lastFail: Long) {
        val edit = p.edit().putInt(K_FAILED, failed)
        if (lastFail == 0L) edit.remove(K_LAST_FAIL) else edit.putLong(K_LAST_FAIL, lastFail)
        edit.commit()
    }

    /**
     * With the wipe off: how long the lock refuses attempts after [failed] wrong
     * ones. Timed on [SystemClock.elapsedRealtime], which the owner cannot set:
     * moving the date forward does nothing. That clock restarts at boot, so a
     * timestamp from before a reboot reads as being in the future; the lockout
     * then starts again in full. A reboot therefore never shortens it, and
     * never lengthens it beyond one full period either.
     */
    private fun lockoutRemaining(p: SharedPreferences, failed: Int): Long {
        if (failed < FREE_ATTEMPTS) return 0L
        val steps = (failed - FREE_ATTEMPTS).coerceAtMost(20)
        val duration = (LOCKOUT_BASE_MS shl steps).coerceAtMost(LOCKOUT_MAX_MS)
        val last = p.getLong(K_LAST_FAIL, 0L)
        val now = SystemClock.elapsedRealtime()
        if (now < last) {
            p.edit().putLong(K_LAST_FAIL, now).commit()
            return duration
        }
        return (last + duration - now).coerceAtLeast(0L)
    }

    // ── Setting ─────────────────────────────────────────────────────────────

    /** Why [code] cannot be a passcode, or null when it can. */
    fun problem(code: String): String? {
        if (code.length !in MIN_LENGTH..MAX_LENGTH || code.any { it !in '0'..'9' }) {
            return "Use $MIN_LENGTH to $MAX_LENGTH digits"
        }
        if (code.all { it == code[0] }) return "Too easy to guess: every digit is the same"
        val steps = code.zipWithNext { a, b -> b - a }.toSet()
        if (steps == setOf(1) || steps == setOf(-1)) return "Too easy to guess: the digits run in order"
        return null
    }

    /**
     * Sets (or replaces) the passcode and turns the lock on. The caller has
     * already confirmed the current passcode when one was set. Blocking.
     */
    fun setPasscode(context: Context, code: String): Result<Unit> = runCatching {
        problem(code)?.let { throw IllegalArgumentException(it) }
        synchronized(attemptLock) {
            val p = prefs(context)
            val key = existingKey() ?: createKey()
            val input = code.toByteArray(Charsets.UTF_8)
            try {
                val duressSalt = p.bytes(K_DURESS_SALT)
                val duressVerifier = p.bytes(K_DURESS_VERIFIER)
                if (duressSalt != null && duressVerifier != null &&
                    MessageDigest.isEqual(mac(key, duressSalt, input), duressVerifier)
                ) throw IllegalArgumentException("That is the duress passcode; choose a different one")
                val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
                val verifier = mac(key, salt, input)
                val edit = p.edit()
                    .putString(K_SALT, encode(salt))
                    .putString(K_VERIFIER, encode(verifier))
                    .putInt(K_FAILED, 0)
                    .remove(K_LAST_FAIL)
                if (!p.contains(K_WIPE_AFTER)) edit.putInt(K_WIPE_AFTER, DEFAULT_WIPE_AFTER)
                if (!p.contains(K_RELOCK_MS)) edit.putLong(K_RELOCK_MS, DEFAULT_RELOCK_MS)
                if (!edit.commit()) throw IllegalStateException("Could not save the passcode")
            } finally {
                input.fill(0)
            }
            _enabled.value = true
            _locked.value = false
        }
    }

    /**
     * Sets the duress passcode: entered on the lock screen, it wipes Aegis
     * exactly as the failure limit would, and looks like any wrong passcode.
     * Null removes it. Needs the lock to be on. Blocking.
     */
    fun setDuress(context: Context, code: String?): Result<Unit> = runCatching {
        synchronized(attemptLock) {
            val p = prefs(context)
            if (code == null) {
                p.edit().remove(K_DURESS_SALT).remove(K_DURESS_VERIFIER).commit()
                return@runCatching
            }
            problem(code)?.let { throw IllegalArgumentException(it) }
            val mainSalt = p.bytes(K_SALT) ?: throw IllegalStateException("Set the app passcode first")
            val mainVerifier = p.bytes(K_VERIFIER) ?: throw IllegalStateException("Set the app passcode first")
            val key = existingKey() ?: throw IllegalStateException("The passcode key is missing")
            val input = code.toByteArray(Charsets.UTF_8)
            try {
                if (MessageDigest.isEqual(mac(key, mainSalt, input), mainVerifier)) {
                    throw IllegalArgumentException("The duress passcode must differ from the app passcode")
                }
                val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
                val committed = p.edit()
                    .putString(K_DURESS_SALT, encode(salt))
                    .putString(K_DURESS_VERIFIER, encode(mac(key, salt, input)))
                    .commit()
                if (!committed) throw IllegalStateException("Could not save the duress passcode")
            } finally {
                input.fill(0)
            }
        }
    }

    /** Turns the lock off: the verifiers, the settings and the Keystore key all go. */
    fun disable(context: Context) {
        synchronized(attemptLock) {
            prefs(context).edit().clear().commit()
            runCatching {
                val ks = keyStore()
                if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
            deleteBiometricKey()
            _enabled.value = false
            _locked.value = false
            _biometricEnabled.value = false
            backgroundedAt = 0L
        }
    }

    // ── Keystore ────────────────────────────────────────────────────────────

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun existingKey(): SecretKey? {
        val ks = keyStore()
        if (!ks.containsAlias(KEY_ALIAS)) return null
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createKey(): SecretKey {
        fun generate(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            )
            return generator.generateKey()
        }
        // StrongBox (a separate secure chip) where the phone has one; the TEE otherwise.
        return try {
            generate(strongBox = true)
        } catch (e: ProviderException) {
            generate(strongBox = false)
        }
    }

    /**
     * A fresh key for biometric unlock, bound to the biometrics enrolled at this
     * moment: usable once per strong-biometric authentication, and permanently
     * invalidated by Android as soon as another fingerprint or face is enrolled.
     * Throws when the phone has no strong biometric enrolled.
     */
    private fun createBiometricKey() {
        deleteBiometricKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(BIOMETRIC_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        generator.generateKey()
    }

    private fun deleteBiometricKey() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(BIOMETRIC_KEY_ALIAS)) ks.deleteEntry(BIOMETRIC_KEY_ALIAS)
        }
    }

    private fun mac(key: SecretKey, salt: ByteArray, code: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.update(DOMAIN)
        mac.update(salt)
        mac.update(code)
        return mac.doFinal()
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun SharedPreferences.bytes(key: String): ByteArray? =
        getString(key, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
