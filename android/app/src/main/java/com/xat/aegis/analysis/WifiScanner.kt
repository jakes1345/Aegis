package com.xat.aegis.analysis

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.xat.aegis.Threat
import com.xat.aegis.WifiAnomaly

/**
 * Looks for access points that behave like bait rather than like infrastructure.
 *
 * The hard part here is not finding suspicious networks, it is not crying wolf. An
 * earlier version flagged `xfinitywifi`, `attwifi`, `netgear` and every network
 * broadcasting on more than two frequencies — which is to say every carrier hotspot
 * and every dual-band router in the country. A scanner that lights up on a walk down
 * the street teaches you to ignore it, so each rule below has to describe something
 * a normal network does not do.
 */
object WifiScanner {

    /**
     * Default SSIDs of actual interception and research equipment. Deliberately short:
     * a network being *popular* is not evidence, and carrier hotspot names belong to
     * the open-network rule below, where being unencrypted is what carries the signal.
     */
    private val KNOWN_TOOL_SSIDS = setOf(
        "drt", "hailstorm", "kingfish", "stingray", "dirtbox", "triggerfish",
        "gossamer", "harpoon", "crossbow", "porpoise",
        "androidwifi", "test_ssid", "testssid", "wifi_test",
        "pineapple", "wifipineapple", "hak5", "evil_twin", "eviltwin"
    )

    /** Operator brands. An open network wearing one of these is the classic bait. */
    private val CARRIER_NAMES = setOf(
        "at&t", "att", "verizon", "t-mobile", "tmobile", "sprint", "boost", "cricket",
        "metro", "metropcs", "straight talk", "tracfone", "consumer cellular",
        "us cellular", "uscellular", "c spire", "spectrum", "charter", "comcast",
        "xfinity", "cox", "optimum", "altice", "frontier", "centurylink", "lumen",
        "vodafone", "orange", "telefonica", "movistar", "o2", "ee", "three"
    )

    fun scan(context: Context): List<WifiAnomaly> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val results = try { wifi.scanResults } catch (_: SecurityException) { return emptyList() }
        if (results.isNullOrEmpty()) return emptyList()

        val anomalies = ArrayList<WifiAnomaly>()
        val now = System.currentTimeMillis()

        // Same SSID seen on the same frequency from several radios, and whether any
        // access point on that SSID is encrypted — both needed for the twin rules.
        val bssidsPerSsidFreq = HashMap<String, MutableSet<String>>()
        val securedSsids = HashSet<String>()

        for (r in results) {
            val ssid = normalise(r.SSID) ?: continue
            val bssid = r.BSSID ?: continue
            bssidsPerSsidFreq.getOrPut("$ssid@${r.frequency}") { mutableSetOf() }.add(bssid)
            if (!isOpen(r)) securedSsids.add(ssid)
        }

        for (r in results) {
            val ssid = normalise(r.SSID) ?: continue
            val bssid = r.BSSID ?: continue
            val label = r.SSID?.trim()?.trim('"').orEmpty().ifBlank { ssid }
            val open = isOpen(r)

            // 1 — Default SSID of known interception or pentest equipment.
            if (KNOWN_TOOL_SSIDS.contains(ssid)) {
                anomalies.add(WifiAnomaly(label, bssid, r.level, "known_catcher_ssid", Threat.HIGH, now))
                continue
            }

            // 2 — An operator's brand on an unencrypted network. Real carrier hotspots
            // use Passpoint or WPA2-Enterprise; an open one wearing the name is bait.
            if (open && CARRIER_NAMES.any { ssid.contains(it) }) {
                anomalies.add(WifiAnomaly(label, bssid, r.level, "carrier_open_network", Threat.HIGH, now))
                continue
            }

            // 3 — An open network using the name of a network that is encrypted
            // elsewhere in range. That is an evil twin of a real access point, and
            // unlike a strong signal it has no innocent explanation.
            if (open && securedSsids.contains(ssid)) {
                anomalies.add(WifiAnomaly(label, bssid, r.level, "open_twin_of_secured", Threat.HIGH, now))
            }
        }

        // 4 — Three or more radios broadcasting one SSID on the *same* frequency.
        // A mesh kit or a dual-band router spreads itself across bands and channels
        // precisely to avoid this; stacking up on one channel does not happen by design.
        for ((key, bssids) in bssidsPerSsidFreq) {
            if (bssids.size >= 3) {
                val ssid = key.substringBeforeLast('@')
                anomalies.add(WifiAnomaly(ssid, bssids.first(), -60, "duplicate_ssid", Threat.MEDIUM, now))
            }
        }

        return anomalies.distinctBy { "${it.bssid}|${it.reason}" }
    }

    /** Lower-cased SSID with the quoting some Android versions add stripped off. */
    private fun normalise(raw: String?): String? =
        raw?.trim()?.trim('"')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * True only when the network carries no encryption at all.
     *
     * The old check asked whether the capability string mentioned "WPA" or "WEP",
     * which quietly classified every WPA3 network — advertised as `[RSN-SAE-CCMP]` —
     * as wide open, and then flagged it.
     */
    private fun isOpen(r: ScanResult): Boolean {
        val caps = r.capabilities ?: return false
        val secured = listOf("WPA", "WEP", "RSN", "SAE", "PSK", "EAP", "OWE", "WAPI")
            .any { caps.contains(it, ignoreCase = true) }
        return !secured
    }
}
