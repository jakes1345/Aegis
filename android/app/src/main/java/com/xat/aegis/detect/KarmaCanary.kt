package com.xat.aegis.detect

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import com.xat.aegis.AppSettings
import com.xat.aegis.Threat
import com.xat.aegis.WifiAnomaly
import java.security.SecureRandom

/**
 * Catches Karma-style rogue access points — a Wi-Fi Pineapple, hcxdumptool in
 * "respond to everything" mode, a hotel-lobby interception box — by giving them a
 * question only a liar would answer.
 *
 * A Karma attack works by answering every probe request: a phone asks "is network X
 * here?" and the attacker says yes, whatever X is, so the phone connects to it. This
 * phone therefore asks for a network that does not exist. On first run a random
 * 32-hex-character SSID is generated and stored; it is registered as a *hidden*
 * network suggestion, which is what makes Android include it in directed probe
 * requests (hidden networks can only be found by asking for them by name). Nobody
 * else knows this name, there is no such access point anywhere on earth, and the
 * phone only ever says it in a probe. So any scan result carrying that SSID is an
 * access point that heard the probe and answered it. There is no benign
 * explanation, which is what "zero false positives by construction" means.
 */
object KarmaCanary {

    const val REASON = "karma_canary_answered"

    private const val KEY_SSID = "karma_canary_ssid"
    private const val KEY_SUGGESTED = "karma_canary_suggested"

    @Volatile
    private var cached: String? = null

    /** The canary SSID, generating and storing one on the first call. */
    fun ssid(context: Context): String {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SSID, null)?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        if (stored != null) {
            cached = stored
            return stored
        }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SSID, fresh).putBoolean(KEY_SUGGESTED, false).apply()
        cached = fresh
        return fresh
    }

    /**
     * Registers the canary as a hidden, open network suggestion so the platform
     * probes for it. Idempotent: a suggestion already in place is reported as
     * success, as is the platform's "duplicate" answer. Returns false when the
     * suggestion could not be added — Wi-Fi service missing, the app's suggestion
     * quota exhausted, or the user having disallowed suggestions from Aegis.
     *
     * The suggestion carries no credentials, so even if the phone did join the
     * answering access point it would be an open network the system treats as
     * untrusted; and because the SSID is unique to this phone, nothing else in
     * the world can ever match the suggestion.
     */
    fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val prefs = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val name = ssid(app)
        if (prefs.getBoolean(KEY_SUGGESTED, false)) return true
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(name)
            .setIsHiddenSsid(true)
            .setIsAppInteractionRequired(false)
            .build()
        val status = try { wifi.addNetworkSuggestions(listOf(suggestion)) }
        catch (_: SecurityException) { return false }
        catch (_: IllegalArgumentException) { return false }
        val ok = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        if (ok) prefs.edit().putBoolean(KEY_SUGGESTED, true).apply()
        return ok
    }

    /**
     * Pure check of a scan-result list against the canary. One anomaly per
     * answering BSSID; a Karma box with several radios is several anomalies.
     */
    fun check(canary: String, results: List<ScanResult>, now: Long): List<WifiAnomaly> {
        val hits = ArrayList<WifiAnomaly>(1)
        for (r in results) {
            val seen = r.SSID?.trim()?.trim('"') ?: continue
            if (seen != canary) continue
            val bssid = r.BSSID ?: continue
            hits.add(WifiAnomaly(seen, bssid, r.level, REASON, Threat.CRITICAL, now))
        }
        return hits.distinctBy { it.bssid }
    }

    /** Reads the platform's scan cache and checks it. Empty when nothing answered. */
    fun scan(context: Context): List<WifiAnomaly> {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        val results = try { wifi.scanResults } catch (_: SecurityException) { return emptyList() } ?: return emptyList()
        return check(ssid(app), results, System.currentTimeMillis())
    }

    /** Human explanation for the alert and the timeline. */
    fun describe(anomaly: WifiAnomaly): String =
        "An access point (${anomaly.bssid}, ${anomaly.rssi} dBm) answered a probe for a network name that " +
            "exists nowhere but inside this phone. Only a device that answers every probe request — " +
            "a Karma / Pineapple style interception box — does that. Do not join any Wi-Fi here; " +
            "turn Wi-Fi off and leave."
}
