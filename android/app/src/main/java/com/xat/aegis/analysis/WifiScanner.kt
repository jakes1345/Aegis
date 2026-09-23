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

    // Declared before CARRIER_TOKENS: object properties initialise in order, and
    // tokens() needs this to exist when that list is built.
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Each carrier name as a sequence of whole tokens, split the same way as the SSID.
     *
     * Matching used to be a plain substring test, so "ee" hit "Free WiFi" and
     * "Coffee", "att" hit "Hyatt Guest" and "Seattle Public Library", "o2" hit any
     * SSID with those two characters in it — and every one of them was flagged HIGH
     * as carrier bait. A brand now has to appear as consecutive whole words:
     * "AT&T WiFi" is ["at", "t", "wifi"] and matches "at&t" = ["at", "t"];
     * "Hyatt Guest" is ["hyatt", "guest"] and matches nothing.
     */
    private val CARRIER_TOKENS: List<List<String>> =
        CARRIER_NAMES.map { tokens(it) }.filter { it.isNotEmpty() }.distinct()

    fun scan(context: Context): List<WifiAnomaly> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val results = try { wifi.scanResults } catch (_: SecurityException) { return emptyList() }
        if (results.isNullOrEmpty()) return emptyList()

        val anomalies = ArrayList<WifiAnomaly>()
        val now = System.currentTimeMillis()

        // Whether any access point on each SSID is encrypted — needed for the
        // evil-twin rule.
        val securedSsids = HashSet<String>()

        for (r in results) {
            val ssid = normalise(r.SSID) ?: continue
            if (r.BSSID == null) continue
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
            if (open && containsCarrier(tokens(ssid))) {
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

        // There is deliberately no rule for several access points sharing one SSID on
        // one channel. Enterprise, campus, hotel and ISP-mesh Wi-Fi does exactly that —
        // 2.4 GHz only has channels 1, 6 and 11 to reuse — so it flagged ordinary
        // infrastructure everywhere and said nothing about bait.

        return anomalies.distinctBy { "${it.bssid}|${it.reason}" }
    }

    /**
     * Splits lower-cased text into alphanumeric tokens. Everything else — spaces,
     * "&", "-", "_", punctuation — is a separator, applied identically to SSIDs and
     * to [CARRIER_NAMES], so "T-Mobile" and "t-mobile" both become ["t", "mobile"].
     */
    private fun tokens(text: String): List<String> =
        text.lowercase().split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

    /** True when some carrier's full token sequence appears as consecutive tokens. */
    private fun containsCarrier(ssidTokens: List<String>): Boolean =
        CARRIER_TOKENS.any { carrier ->
            ssidTokens.size >= carrier.size &&
                (0..ssidTokens.size - carrier.size).any { start ->
                    carrier.indices.all { i -> ssidTokens[start + i] == carrier[i] }
                }
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
