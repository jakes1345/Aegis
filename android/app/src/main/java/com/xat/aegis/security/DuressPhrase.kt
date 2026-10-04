package com.xat.aegis.security

import android.content.Context
import android.util.Base64
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import com.xat.aegis.TimelineEvent
import com.xat.aegis.TimelineLog
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.KeystoreBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * A duress phrase: an ordinary-looking sentence the owner can type into any
 * conversation. The message goes out as written, so anyone watching the screen
 * sees a normal chat; underneath, the SOS fires with the last known position.
 *
 * The phrase is kept encrypted under the comms Keystore key ([KeystoreBox]) and
 * compared in memory only. It is never written to a log, a toast, a
 * notification or the timeline — the timeline entry says only that the SOS was
 * triggered this way.
 */
object DuressPhrase {

    private const val PREFS = "duress"
    private const val KEY_PHRASE = "phrase_enc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The decrypted, normalised phrase, held after the first check so the Keystore is not hit per message. */
    @Volatile
    private var cached: String? = null

    @Volatile
    private var installed = false

    /** Whitespace-collapsed, lower-cased: "case-insensitive exact" tolerant of double spaces. */
    private fun normalise(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    /** Saves [phrase] encrypted. A blank phrase clears it. */
    fun set(context: Context, phrase: String) {
        val norm = normalise(phrase)
        if (norm.isEmpty()) { clear(context); return }
        val blob = Base64.encodeToString(KeystoreBox.encryptString(norm), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_PHRASE, blob).apply()
        cached = norm
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PHRASE).apply()
        cached = null
    }

    fun isConfigured(context: Context): Boolean = prefs(context).contains(KEY_PHRASE)

    /**
     * True when [body] is the duress phrase. Decrypts on first use; a Keystore
     * that refuses (key gone after an unpair, say) reads as "not configured".
     */
    fun check(body: String, context: Context): Boolean {
        val phrase = cached ?: load(context) ?: return false
        return constantTimeEquals(normalise(body), phrase)
    }

    /**
     * Checks [body] and, on a match, raises the SOS: [Registry.setPanicActive]
     * with the last known location from [Registry.status], and a PANIC entry on
     * the timeline. Returns whether it fired.
     */
    fun checkAndTrigger(body: String, context: Context): Boolean {
        if (!check(body, context)) return false
        trigger(context.applicationContext)
        return true
    }

    /**
     * Hooks the comms send path once per process: every outgoing text is
     * checked before it is queued. Call from the activity and the service after
     * [CommsRepository.init]. The message itself still goes out unchanged.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        CommsRepository.addSendHook { body -> checkAndTrigger(body, app) }
        installed = true
    }

    private fun trigger(app: Context) {
        val s = Registry.status.value
        val lat = s.lat
        val lon = s.lon
        Registry.setPanicActive(true)
        scope.launch {
            val now = System.currentTimeMillis()
            TimelineLog.record(
                app,
                TimelineEvent(
                    id = "panic_duress@$now",
                    kind = EventKind.PANIC,
                    ts = now,
                    title = "SOS triggered by duress phrase",
                    detail = if (lat != null && lon != null) "Last known position attached" else "No GPS fix to attach",
                    severity = Severity.CRITICAL,
                    lat = lat, lon = lon
                ),
                dedupeKey = "panic_duress"
            )
        }
    }

    private fun load(context: Context): String? {
        val blob = prefs(context).getString(KEY_PHRASE, null) ?: return null
        val phrase = runCatching { KeystoreBox.decryptString(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull() ?: return null
        cached = phrase
        return phrase
    }

    /** Compares digests so timing does not leak how much of the phrase matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val x = md.digest(a.toByteArray(Charsets.UTF_8))
        val y = md.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(x, y)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
