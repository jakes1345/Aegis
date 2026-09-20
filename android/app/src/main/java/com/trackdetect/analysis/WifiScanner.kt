package com.trackdetect.analysis

import android.content.Context
import android.net.wifi.WifiManager
import com.trackdetect.Threat
import com.trackdetect.WifiAnomaly

object WifiScanner {

    // SSIDs used by known IMSI catcher bait configurations and DHS/law enforcement tools.
    // Sources: Stingrays, Harris Corporation documents, Hailstorm/Kingfish/Harpoon configs,
    // academic papers on IMSI catchers, and public security research.
    private val KNOWN_CATCHER_SSIDS = setOf(
        // Generic network bait SSIDs used by Stingray/Hailstorm
        "starbucks", "starbucks wifi", "xfinitywifi", "xfinity", "attwifi", "att wifi",
        "tmobile", "t-mobile", "verizon", "verizonwifi", "sprint", "sprintwifi",
        "optimumwifi", "optimum wifi", "cablewifi",
        // Known IMSI catcher defaults (Harris, L3Harris, DRT)
        "drt", "hailstorm", "kingfish", "stingray", "dirtbox", "triggerfish",
        // Generic law-enforcement bait patterns
        "police_wifi", "cop_wifi", "feds_wifi", "gov_wifi", "federal_wifi",
        // Commonly deployed carrier bait APs
        "free_public_wifi", "free wifi", "free_wifi", "publicwifi", "public wifi",
        "hotel_wifi", "hotel wifi", "airport wifi", "airportwifi",
        // Android IMSI test SSIDs and known research tools
        "androidwifi", "android_wifi", "test_ssid", "testssid", "wifi_test",
        // EvilTwin / known attack vectors
        "default", "linksys", "netgear", "dlink", "asus", "belkin"
    )

    // Legitimate carrier names in SSIDs are suspicious when the network is open/unencrypted
    private val CARRIER_NAMES = setOf(
        "at&t", "verizon", "t-mobile", "tmobile", "sprint", "boost", "cricket",
        "metro", "metropcs", "straight talk", "tracfone", "consumer cellular",
        "us cellular", "uscellular", "c spire", "spectrum", "charter", "comcast",
        "xfinity", "cox", "optimum", "altice", "frontier", "centurylink", "lumen"
    )

    fun scan(context: Context): List<WifiAnomaly> {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val results = try { wifi.scanResults } catch (_: SecurityException) { return emptyList() }
        if (results.isNullOrEmpty()) return emptyList()

        val anomalies = ArrayList<WifiAnomaly>()
        val now = System.currentTimeMillis()

        // Track BSSIDs per SSID to detect evil-twin / BSSID spoofing
        val ssidToBssids = HashMap<String, MutableList<String>>()
        val ssidToChannels = HashMap<String, MutableSet<Int>>()

        for (r in results) {
            val ssid = r.SSID?.trim()?.lowercase() ?: continue
            if (ssid.isEmpty()) continue
            val bssid = r.BSSID ?: continue

            ssidToBssids.getOrPut(ssid) { mutableListOf() }.add(bssid)
            ssidToChannels.getOrPut(ssid) { mutableSetOf() }.add(r.frequency)

            // 1. Known IMSI-catcher bait SSID
            if (KNOWN_CATCHER_SSIDS.contains(ssid)) {
                anomalies.add(WifiAnomaly(
                    ssid = r.SSID ?: ssid, bssid = bssid, rssi = r.level,
                    reason = "known_catcher_ssid",
                    threat = Threat.HIGH, ts = now
                ))
                continue
            }

            // 2. Open network advertising a carrier name — classic IMSI catcher bait
            val isOpen = r.capabilities?.contains("[ESS]") == true &&
                !r.capabilities.contains("WPA") && !r.capabilities.contains("WEP")
            if (isOpen && CARRIER_NAMES.any { ssid.contains(it) }) {
                anomalies.add(WifiAnomaly(
                    ssid = r.SSID ?: ssid, bssid = bssid, rssi = r.level,
                    reason = "carrier_open_network",
                    threat = Threat.HIGH, ts = now
                ))
                continue
            }

            // 3. Extremely strong signal — could indicate a nearby fake AP
            if (r.level > -40) {
                anomalies.add(WifiAnomaly(
                    ssid = r.SSID ?: ssid, bssid = bssid, rssi = r.level,
                    reason = "signal_anomaly",
                    threat = Threat.MEDIUM, ts = now
                ))
            }
        }

        // 4. Same SSID on multiple channels (evil-twin indicator)
        for ((ssid, channels) in ssidToChannels) {
            if (channels.size >= 3) {
                val bssids = ssidToBssids[ssid] ?: continue
                anomalies.add(WifiAnomaly(
                    ssid = ssid, bssid = bssids.first(), rssi = -60,
                    reason = "duplicate_ssid",
                    threat = Threat.MEDIUM, ts = now
                ))
            }
        }

        // Deduplicate by SSID+reason
        return anomalies.distinctBy { "${it.ssid}|${it.reason}" }
    }
}
