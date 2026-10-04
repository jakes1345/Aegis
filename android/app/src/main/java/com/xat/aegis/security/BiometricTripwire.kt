package com.xat.aegis.security

import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.xat.aegis.Alert
import com.xat.aegis.BiometricTripwireEvent
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** What [BiometricTripwire.check] found. */
sealed interface BiometricTripwireResult {
    /** The enrolment-bound key is intact: no biometric has been added or removed since it was armed. */
    data object OK : BiometricTripwireResult
    /** The key was invalidated: the enrolled biometrics changed. [ts] is when this was noticed. */
    data class TRIGGERED(val ts: Long) : BiometricTripwireResult
    /** No key existed; one was armed now against the biometrics enrolled at this moment. */
    data object FIRST_INIT : BiometricTripwireResult
    /**
     * The tripwire cannot be armed on this phone right now — no strong biometric
     * is enrolled, or the Keystore is not answering. It will arm itself on the
     * first check after the owner enrols one.
     */
    data class UNAVAILABLE(val reason: String) : BiometricTripwireResult
}

/**
 * Detects a fingerprint or face being enrolled behind the owner's back.
 *
 * Android offers no broadcast for a new enrolment, but it does offer a key that
 * dies on one: an AES key made with
 * [KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment] is
 * permanently invalidated by the Keystore the moment the set of enrolled
 * biometrics changes. Aegis arms one such key on first run and, on every launch,
 * asks the Keystore to start a cipher with it. The key is never used for
 * anything; a [KeyPermanentlyInvalidatedException] from that one call is the
 * whole signal. The key is then re-armed against the new enrolment set, so the
 * next change is caught too.
 *
 * Someone with the owner's PIN can add their own finger in under a minute and
 * then unlock the phone, banking apps and the lock screen for as long as they
 * like without ever needing the PIN again. The removal of the last biometric
 * invalidates the key as well, which is also worth knowing about.
 *
 * The key is StrongBox-backed where the phone has the chip (the S26 Ultra does),
 * which keeps even the invalidation bookkeeping out of the main OS.
 *
 * Separate from [AppLock]'s biometric-unlock key on purpose: that one is only
 * made when biometric unlock is switched on and is deleted when it is switched
 * off, while this one exists for every user from the first launch.
 */
object BiometricTripwire {

    internal const val KEY_ALIAS = "aegis_bio_tripwire_v1"
    internal const val PREFS_NAME = "bio_tripwire"

    /** When the current key was generated, 0 when none has been. */
    private const val K_ARMED_TS = "armed_ts"
    /** When the key was last found intact. */
    private const val K_LAST_CHECK_TS = "last_check_ts"
    /** Whether the current key sits in StrongBox (for the Device tab). */
    private const val K_STRONGBOX = "strongbox"

    private val lock = Any()

    /**
     * Arms the key if there is none, otherwise tests it. Blocking Keystore work:
     * call it off the main thread, once per app launch. Never throws.
     */
    fun check(context: Context): BiometricTripwireResult = synchronized(lock) {
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val armedAt = p.getLong(K_ARMED_TS, 0L)
        val lastCheck = p.getLong(K_LAST_CHECK_TS, 0L)

        val key: SecretKey? = try {
            (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: UnrecoverableKeyException) {
            // Some OEM Keystores throw here for an invalidated key instead of
            // returning it and failing at init; same meaning.
            return if (armedAt > 0L) trip(app, p, now, lastCheck) else arm(app, p, now)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return if (armedAt > 0L) trip(app, p, now, lastCheck) else arm(app, p, now)
        } catch (e: Exception) {
            return BiometricTripwireResult.UNAVAILABLE("The Keystore is not responding (${e.javaClass.simpleName})")
        }

        if (key == null) {
            // Never armed: arm now. Armed and gone: Android deletes an
            // enrolment-invalidated key outright on some devices, and a Keystore
            // reset (a factory reset short of wiping the app is not one) would
            // also take every other Aegis key with it. Either way, report it.
            return if (armedAt == 0L) arm(app, p, now) else trip(app, p, now, lastCheck)
        }

        return try {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
            noteIntact(p, now)
            BiometricTripwireResult.OK
        } catch (e: KeyPermanentlyInvalidatedException) {
            trip(app, p, now, lastCheck)
        } catch (e: UserNotAuthenticatedException) {
            // The key is intact; it only wants a biometric before it will encrypt,
            // which is exactly what is expected of it and never asked for.
            noteIntact(p, now)
            BiometricTripwireResult.OK
        } catch (e: Exception) {
            BiometricTripwireResult.UNAVAILABLE("Could not test the key (${e.javaClass.simpleName})")
        }
    }

    /** When the key was armed, or 0 when it never has been. */
    fun armedAt(context: Context): Long = runCatching { prefs(context).getLong(K_ARMED_TS, 0L) }.getOrDefault(0L)

    /** Whether the armed key is StrongBox-backed; null when no key is armed. */
    fun strongBoxBacked(context: Context): Boolean? = runCatching {
        val p = prefs(context)
        if (p.getLong(K_ARMED_TS, 0L) == 0L) null else p.getBoolean(K_STRONGBOX, false)
    }.getOrNull()

    private fun noteIntact(p: SharedPreferences, now: Long) {
        p.edit().putLong(K_LAST_CHECK_TS, now).apply()
    }

    /** Publishes the event and re-arms against the biometrics enrolled now. */
    private fun trip(context: Context, p: SharedPreferences, now: Long, lastCheck: Long): BiometricTripwireResult {
        val event = BiometricTripwireEvent(timestamp = now, previousCheckTs = lastCheck)
        Registry.publishBiometricTripwire(event)
        Registry.publishAlert(
            Alert(
                id = "bio_tripwire@$now",
                ts = now,
                severity = Severity.HIGH,
                title = "A fingerprint or face was enrolled on this phone",
                detail = "The enrolled biometrics changed since Aegis last checked" +
                    (if (lastCheck > 0L) " (${describeAge(now - lastCheck)} ago)" else "") +
                    ". If you did not add or remove one yourself, someone with your PIN did: " +
                    "check Settings → Security → Biometrics and delete anything you do not recognise, then change your PIN.",
                kind = EventKind.BIOMETRIC_ENROLLED,
                dedupeKey = "bio_tripwire"
            ),
            // A trip is a discrete event, not a standing condition: never swallow one.
            dedupeWindowMs = 0L
        )
        // Re-arm so the next change is caught as well. Failing to (every biometric
        // was removed, say) leaves the key absent and armedAt set, so the next
        // check will report again until arming succeeds; the owner has been told once.
        if (arm(context, p, now) is BiometricTripwireResult.UNAVAILABLE) {
            deleteKey()
            p.edit().putLong(K_ARMED_TS, 0L).putLong(K_LAST_CHECK_TS, now).apply()
        }
        return BiometricTripwireResult.TRIGGERED(now)
    }

    /** Generates a fresh enrolment-bound key, StrongBox first. */
    private fun arm(context: Context, p: SharedPreferences, now: Long): BiometricTripwireResult {
        val bm = context.getSystemService(BiometricManager::class.java)
        val can = runCatching { bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) }.getOrNull()
        when (can) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                return BiometricTripwireResult.UNAVAILABLE("No fingerprint or face is enrolled, so there is nothing to watch yet")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                return BiometricTripwireResult.UNAVAILABLE("This phone has no strong biometric sensor")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                return BiometricTripwireResult.UNAVAILABLE("The biometric hardware is unavailable right now")
            else -> Unit
        }
        deleteKey()
        val strongBox = try {
            generate(strongBox = true)
            true
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException is a ProviderException: no chip, use the TEE.
            try {
                generate(strongBox = false)
                false
            } catch (e2: Exception) {
                return BiometricTripwireResult.UNAVAILABLE(reasonFor(e2))
            }
        } catch (e: Exception) {
            return BiometricTripwireResult.UNAVAILABLE(reasonFor(e))
        }
        p.edit()
            .putLong(K_ARMED_TS, now)
            .putLong(K_LAST_CHECK_TS, now)
            .putBoolean(K_STRONGBOX, strongBox)
            .apply()
        return BiometricTripwireResult.FIRST_INIT
    }

    private fun generate(strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // Per-use, strong biometric only: the kind of key the enrolment
                // invalidation applies to. Device-credential keys are not invalidated.
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        )
        generator.generateKey()
    }

    private fun reasonFor(e: Exception): String = when (e) {
        // "At least one biometric must be enrolled to create keys requiring user authentication for every use"
        is IllegalStateException -> "No fingerprint or face is enrolled, so there is nothing to watch yet"
        else -> "The Keystore refused to make the key (${e.javaClass.simpleName})"
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun deleteKey() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun describeAge(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} days"
        }
    }
}
