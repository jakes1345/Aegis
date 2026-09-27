package com.xat.aegis.analysis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.xat.aegis.Threat
import com.xat.aegis.WifiAnomaly
import com.xat.aegis.WifiConnection
import com.xat.aegis.WifiNetwork
import com.xat.aegis.WifiSecurity
import com.xat.aegis.WifiStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address

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

    /**
     * Operator brands distinctive enough to match as whole tokens anywhere in an
     * SSID: none of them is an ordinary word, so an open "Verizon Guest" is bait and
     * nothing innocent looks like it. Brands that *are* ordinary words — Orange,
     * Metro, Boost, Cox, Charter, Frontier, Spectrum, Sprint, Cricket, Optimum,
     * Lumen, EE, O2, Three — are deliberately absent: as tokens they flagged
     * "Orange County Library" and "Metro Transit WiFi" as HIGH. They appear only in
     * [KNOWN_HOTSPOT_SSIDS], as the exact names their hotspots broadcast.
     */
    private val CARRIER_BRANDS = setOf(
        "at&t", "xfinity", "verizon", "t-mobile", "tmobile", "comcast", "vodafone",
        "telefonica", "movistar", "centurylink", "metropcs", "tracfone",
        "straighttalk", "straight talk", "uscellular", "us cellular", "cspire", "c spire",
        "altice", "boingo"
    )

    /**
     * The exact SSIDs carrier and cable-operator hotspots broadcast, matched against
     * the whole SSID. This is where the single-token names live — "xfinitywifi" and
     * "attwifi" are one token each and could never match a brand-plus-word rule —
     * and where the common-word brands are allowed, because "Cox WiFi" as the entire
     * name is a hotspot's name and "Cox" inside a longer name is somebody's surname.
     */
    private val KNOWN_HOTSPOT_SSIDS = setOf(
        "xfinitywifi", "xfinity wifi", "xfinity", "xfinity mobile",
        "attwifi", "att wifi", "at&t wifi", "att-wifi", "at&t",
        "verizon wifi", "verizonwifi", "verizon", "verizon hotspot",
        "tmobile wifi", "t-mobile wifi", "tmobilewifi", "t-mobile", "t-mobile hotspot",
        "spectrum wifi", "spectrumwifi", "spectrum mobile", "spectrum",
        "cox wifi", "coxwifi", "cox hotspot",
        "optimum wifi", "optimumwifi", "optimum",
        "boingo hotspot", "boingo wifi", "boingo",
        "cablewifi", "cable wifi", "twcwifi", "twc wifi", "twcwifi-passpoint",
        "boost wifi", "boost mobile", "boost hotspot",
        "cricket wifi", "cricket wireless",
        "metro wifi", "metropcs wifi", "metro by t-mobile",
        "sprint wifi", "sprint hotspot",
        "charter wifi", "charter spectrum",
        "frontier wifi", "frontier hotspot",
        "lumen wifi", "centurylink wifi",
        "orange wifi", "orange hotspot",
        "ee wifi", "eewifi", "ee hotspot",
        "o2 wifi", "o2wifi", "o2 hotspot",
        "three wifi", "3 wifi", "three hotspot",
        "vodafone wifi", "vodafonewifi", "vodafone hotspot",
        "btwifi", "bt wifi", "btwifi-with-fon", "bt openzone", "btopenzone",
        "consumer cellular", "us cellular wifi", "uscellular wifi",
        "altice wifi", "alticewifi", "c spire wifi", "cspire wifi",
        "tracfone wifi", "straight talk wifi", "telefonica wifi", "movistar wifi"
    )

    // Declared before the derived lists: object properties initialise in order, and
    // tokens() needs this to exist when they are built.
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Each brand as a sequence of whole tokens, split the same way as the SSID.
     *
     * Matching used to be a plain substring test, so "ee" hit "Free WiFi" and
     * "Coffee", "att" hit "Hyatt Guest" and "Seattle Public Library", "o2" hit any
     * SSID with those two characters in it — and every one of them was flagged HIGH
     * as carrier bait. A brand now has to appear as consecutive whole words:
     * "AT&T WiFi" is ["at", "t", "wifi"] and matches "at&t" = ["at", "t"];
     * "Hyatt Guest" is ["hyatt", "guest"] and matches nothing.
     */
    private val CARRIER_TOKENS: List<List<String>> =
        CARRIER_BRANDS.map { tokens(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * The hotspot names in canonical token form, so "Xfinity-WiFi", "xfinity_wifi"
     * and "XFINITY WIFI" all compare equal to "xfinity wifi" while "xfinitywifi"
     * stays the single token it is.
     */
    private val KNOWN_HOTSPOT_KEYS: Set<String> =
        KNOWN_HOTSPOT_SSIDS.map { canonical(it) }.filter { it.isNotEmpty() }.toSet()

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
            // The exact hotspot names are tried first, then the distinctive brands.
            if (open && (isKnownHotspotName(ssid) || containsCarrier(tokens(ssid)))) {
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

    // ── Live picture: connected network, IP stack, everything in range ───────

    /**
     * Asks the platform for a fresh scan and waits for it to land, up to
     * [SCAN_TIMEOUT_MS]. `startScan` only *starts* a scan — the results arrive two to
     * five seconds later with [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] — so a caller
     * that read the cache straight after it always saw the previous refresh. The
     * broadcast is what says the cache is new.
     *
     * Returns true when the cache now holds fresh results. Android throttles scans to
     * four requests per two minutes in the foreground; a refused request is not an
     * error, the cached results are simply what [snapshot] reads.
     */
    suspend fun requestScan(context: Context): Boolean {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        if (!wifi.isWifiEnabled) return false
        val fresh = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                fresh.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        // A protected system broadcast: only the platform can send it, so the
        // receiver need not be exported. Same flag the service uses for SCREEN_ON.
        val registered = runCatching {
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.isSuccess
        if (!registered) return false
        try {
            @Suppress("DEPRECATION")
            val started = runCatching { wifi.startScan() }.getOrDefault(false)
            if (!started) return false
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { fresh.await() } ?: false
        } finally {
            // One unregister on every path — success, timeout or cancellation. A
            // second call on the same receiver throws, hence not in onReceive too.
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /** Longest wait for scan results before falling back to the cache. */
    private const val SCAN_TIMEOUT_MS = 5_000L

    /**
     * What the WIFI tab shows: the network the phone is on, with its addressing, and
     * every access point in range sorted strongest first. A fresh scan is requested
     * and awaited first, so the list is this refresh, not the previous one. Each
     * nearby network carries the flags that make it worth a look — open, WEP,
     * hidden, a twin of the connected network — so the list is a judgement, not a
     * dump.
     */
    suspend fun snapshot(context: Context): WifiStatus {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiStatus(available = false, reason = "No Wi-Fi service on this device")
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return WifiStatus(available = false, reason = "Location permission needed to read network names", wifiEnabled = wifi.isWifiEnabled)
        if (!wifi.isWifiEnabled) {
            return WifiStatus(available = false, reason = "Wi-Fi is turned off", wifiEnabled = false)
        }

        // Waits for the results broadcast (or the timeout) before reading the cache;
        // when the platform refuses the request, the cache is what there is.
        requestScan(app)

        val now = System.currentTimeMillis()
        val connection = connection(app, wifi)
        val results = try { wifi.scanResults } catch (_: SecurityException) { null } ?: emptyList()

        // Whether any access point on each SSID is encrypted, for the twin rule.
        val securedSsids = HashSet<String>()
        for (r in results) {
            val ssid = normalise(r.SSID) ?: continue
            if (!isOpen(r)) securedSsids.add(ssid)
        }
        val connectedSsid = connection?.ssid?.let { normalise(it) }
        val connectedBssid = connection?.bssid?.lowercase()

        val nearby = results.mapNotNull { r ->
            val bssid = r.BSSID?.lowercase() ?: return@mapNotNull null
            val rawSsid = r.SSID?.trim()?.trim('"').orEmpty()
            val ssidKey = normalise(r.SSID)
            val security = securityOf(r.capabilities)
            val flags = ArrayList<String>(3)
            when {
                ssidKey != null && KNOWN_TOOL_SSIDS.contains(ssidKey) -> flags += "Default name of interception/pentest equipment"
                security == WifiSecurity.OPEN && ssidKey != null && securedSsids.contains(ssidKey) ->
                    flags += "Open copy of an encrypted network — evil twin"
                security == WifiSecurity.OPEN && ssidKey != null &&
                    (isKnownHotspotName(ssidKey) || containsCarrier(tokens(ssidKey))) ->
                    flags += "Carrier name on an open network — bait"
                security == WifiSecurity.OPEN -> flags += "Open — traffic readable by anyone in range"
                security == WifiSecurity.WEP -> flags += "WEP — broken encryption, crackable in minutes"
                security == WifiSecurity.WPA -> flags += "WPA (TKIP) — obsolete encryption"
            }
            if (rawSsid.isEmpty()) flags += "Hidden network"
            if (connectedSsid != null && ssidKey == connectedSsid && bssid != connectedBssid &&
                connection != null && security != connection.security
            ) flags += "Same name as your network, different security"
            if (r.level >= -35) flags += "Very strong signal — transmitter within a few metres"
            val age = runCatching { SystemClock.elapsedRealtime() - r.timestamp / 1000L }.getOrNull()
            WifiNetwork(
                ssid = rawSsid, bssid = bssid, rssi = r.level,
                level = runCatching { wifi.calculateSignalLevel(r.level) }.getOrDefault(barsFor(r.level)),
                frequencyMhz = r.frequency, channel = channelFor(r.frequency), band = bandFor(r.frequency),
                security = security, standard = standardName(r.wifiStandard),
                hidden = rawSsid.isEmpty(), flags = flags,
                connected = bssid == connectedBssid,
                ageMs = age?.takeIf { it >= 0 }
            )
        }.distinctBy { it.bssid }.sortedWith(compareByDescending<WifiNetwork> { it.connected }.thenByDescending { it.rssi })

        return WifiStatus(
            available = true, wifiEnabled = true, connection = connection,
            nearby = nearby, scannedTs = now
        )
    }

    /**
     * The connected network. `WifiManager.getConnectionInfo` is deprecated, but the
     * replacement — a NetworkCallback registered with FLAG_INCLUDE_LOCATION_INFO — is
     * the only other way to see the SSID, and the snapshot is a one-shot read.
     * Addressing comes from ConnectivityManager, which knows the actual link.
     */
    private fun connection(app: Context, wifi: WifiManager): WifiConnection? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networks = cm?.let { m -> runCatching { m.allNetworks.toList() }.getOrDefault(emptyList()) } ?: emptyList()
        val wifiNet = networks.firstOrNull { n ->
            cm?.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        @Suppress("DEPRECATION")
        val info: WifiInfo? = runCatching { wifi.connectionInfo }.getOrNull()
        if (info == null || info.networkId == -1 && info.bssid == null) {
            if (wifiNet == null) return null
        }
        val caps = wifiNet?.let { cm?.getNetworkCapabilities(it) }
        val link: LinkProperties? = wifiNet?.let { cm?.getLinkProperties(it) }
        val activeCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val vpn = networks.any { n -> cm?.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } ||
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val ssid = info?.ssid?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        val bssid = info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        val rssi = info?.rssi ?: -127
        val freq = info?.frequency ?: 0
        val security = info?.let { securityOf(it) } ?: WifiSecurity.UNKNOWN

        val v4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { "${it.address.hostAddress}/${it.prefixLength}" }
        val v6 = link?.linkAddresses?.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }
            ?.let { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }
        val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: link?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        val dns = link?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList()
        val privateDns = link?.takeIf { it.isPrivateDnsActive }?.let { it.privateDnsServerName ?: "Automatic (opportunistic DoT)" }

        val flags = ArrayList<String>(3)
        when (security) {
            WifiSecurity.OPEN -> flags += "Unencrypted — anyone in range can read your traffic"
            WifiSecurity.WEP -> flags += "WEP is broken; this network offers no real protection"
            WifiSecurity.WPA -> flags += "WPA (TKIP) is obsolete and crackable"
            else -> {}
        }
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) flags += "Captive portal — a login page intercepts your traffic"
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == false) flags += "No confirmed internet access on this network"
        if (dns.isNotEmpty() && gateway != null && dns.all { it == gateway } && privateDns == null) flags += "DNS is answered by the router itself — it sees every site you look up"
        if (dns.any { it in SUSPICIOUS_DNS }) flags += "DNS points at an unusual resolver"

        return WifiConnection(
            ssid = ssid, bssid = bssid?.lowercase(), rssi = rssi,
            level = runCatching { wifi.calculateSignalLevel(rssi) }.getOrDefault(barsFor(rssi)),
            frequencyMhz = freq, channel = channelFor(freq), band = bandFor(freq),
            security = security,
            standard = info?.let { standardName(it.wifiStandard) },
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            txMbps = info?.txLinkSpeedMbps?.takeIf { it > 0 },
            rxMbps = info?.rxLinkSpeedMbps?.takeIf { it > 0 },
            ipv4 = v4, ipv6 = v6, gateway = gateway, dnsServers = dns,
            privateDns = privateDns, vpnActive = vpn,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            flags = flags
        )
    }

    /** Resolvers that are not a router, a carrier or one of the well-known public services. */
    private val SUSPICIOUS_DNS = setOf("0.0.0.0", "127.0.0.1")

    /** Security from a scan result's capability string, e.g. `[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]`. */
    fun securityOf(caps: String?): WifiSecurity {
        val c = caps?.uppercase() ?: return WifiSecurity.UNKNOWN
        val sae = c.contains("SAE")
        val psk = c.contains("PSK")
        val eap = c.contains("EAP")
        val suiteB = c.contains("SUITE_B_192") || c.contains("EAP_SUITE_B")
        return when {
            c.contains("PASSPOINT") -> WifiSecurity.PASSPOINT
            suiteB -> WifiSecurity.WPA3_ENTERPRISE
            eap -> WifiSecurity.WPA2_ENTERPRISE
            sae && psk -> WifiSecurity.WPA2_WPA3
            sae -> WifiSecurity.WPA3
            c.contains("WPA2") || (c.contains("RSN") && psk) -> WifiSecurity.WPA2
            c.contains("WPA") && psk -> WifiSecurity.WPA
            c.contains("WEP") -> WifiSecurity.WEP
            c.contains("OWE") -> WifiSecurity.OWE
            c.contains("WAPI") -> WifiSecurity.WPA2
            else -> WifiSecurity.OPEN
        }
    }

    /** `currentSecurityType` exists from API 31, which is minSdk. */
    private fun securityOf(info: WifiInfo): WifiSecurity {
        return when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
            WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OWE
            WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
            WifiInfo.SECURITY_TYPE_PSK -> WifiSecurity.WPA2
            WifiInfo.SECURITY_TYPE_SAE -> WifiSecurity.WPA3
            WifiInfo.SECURITY_TYPE_EAP -> WifiSecurity.WPA2_ENTERPRISE
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.WPA3_ENTERPRISE
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2, WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> WifiSecurity.PASSPOINT
            WifiInfo.SECURITY_TYPE_WAPI_PSK, WifiInfo.SECURITY_TYPE_WAPI_CERT -> WifiSecurity.WPA2
            else -> WifiSecurity.UNKNOWN
        }
    }

    fun bandFor(mhz: Int): String = when {
        mhz <= 0 -> "—"
        mhz < 3000 -> "2.4 GHz"
        mhz < 5925 -> "5 GHz"
        mhz < 7125 -> "6 GHz"
        else -> "60 GHz"
    }

    fun channelFor(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz in 5170..5895 -> (mhz - 5000) / 5
        mhz in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    private fun standardName(standard: Int): String? = when (standard) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
        ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
        ScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
        7 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }

    private fun barsFor(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    /**
     * Splits lower-cased text into alphanumeric tokens. Everything else — spaces,
     * "&", "-", "_", punctuation — is a separator, applied identically to SSIDs and
     * to [CARRIER_NAMES], so "T-Mobile" and "t-mobile" both become ["t", "mobile"].
     */
    private fun tokens(text: String): List<String> =
        text.lowercase().split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

    /** The token sequence joined back up, for whole-name comparison. */
    private fun canonical(text: String): String = tokens(text).joinToString(" ")

    /** True when the entire SSID is one of the names a carrier hotspot broadcasts. */
    private fun isKnownHotspotName(ssid: String): Boolean =
        KNOWN_HOTSPOT_KEYS.contains(canonical(ssid))

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
