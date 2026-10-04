package com.xat.aegis.analysis

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.ProxyInfo
import android.provider.Settings
import android.util.Base64
import com.xat.aegis.Alert
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import com.xat.aegis.comms.CommsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * What one canary run saw. [intercepted] is true when the relay presented a
 * different key than the one pinned on first contact, or when any proxy is
 * configured on the phone; [method] says which, and [proxyHost] names the proxy
 * when there is one.
 */
data class TlsCanaryResult(
    val intercepted: Boolean,
    val method: String?,
    val proxyHost: String?,
    val relayHost: String? = null,
    /** "sha256/<base64>" of the leaf certificate's SubjectPublicKeyInfo, as pinned. */
    val pinnedSpki: String? = null,
    /** The SPKI hash seen on this connection, or null when the connection failed. */
    val observedSpki: String? = null,
    /** CN of the leaf's issuer as presented, for the alert text. */
    val observedIssuer: String? = null,
    /** Whether the presented chain validates against the phone's default trust store. */
    val chainTrusted: Boolean? = null,
    /** True when this run pinned the key (first successful connection). */
    val pinnedNow: Boolean = false,
    val error: String? = null,
    val ts: Long = System.currentTimeMillis()
)

/**
 * A TLS interception canary.
 *
 * Every four hours, and whenever the default network changes, it opens a raw
 * TLS connection to the relay the comms module is registered with and looks at
 * the leaf certificate the server presents, without validating it: an
 * intercepting proxy presents its own certificate, signed by a root it had
 * installed on the phone, and the platform's trust manager would accept it
 * without a word. The SubjectPublicKeyInfo hash of the first leaf ever seen is
 * stored as the pin; any later connection whose leaf key differs is reported
 * as CRITICAL. (A relay that legitimately rotates its key trips it once; the
 * owner re-pins from the Device tab.)
 *
 * The same run also checks for proxies, which are how interception is wired in
 * on a phone without root: the default proxy, every network's own HTTP proxy,
 * and the global `http_proxy` setting.
 */
object TlsCanary {

    private const val PREFS = "tls_canary"
    private const val KEY_HOST = "host"
    private const val KEY_PIN = "spki"
    private const val KEY_PINNED_AT = "pinned_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TS = "last_ts"

    private const val INTERVAL_MS = 4 * 3600_000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** A network change fires several callbacks in a row; one run per burst. */
    private const val NETWORK_DEBOUNCE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodic: Job? = null
    private var networkRun: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * One check, as a cold flow that emits a single [TlsCanaryResult] and
     * completes. Runs on the IO dispatcher. No alert is raised here; see [start]
     * for the scheduled run that does.
     */
    fun check(context: Context): Flow<TlsCanaryResult> = flow {
        emit(runOnce(context.applicationContext))
    }.flowOn(Dispatchers.IO)

    /**
     * Starts the four-hourly run plus a run on every default-network change.
     * Idempotent. Each run that finds interception goes through
     * [Registry.publishAlert] as CRITICAL (deduplicated for an hour).
     */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (periodic?.isActive != true) {
            periodic = scope.launch {
                while (isActive) {
                    runAndAlert(app)
                    delay(INTERVAL_MS)
                }
            }
        }
        if (callback == null) {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(this@TlsCanary) {
                        networkRun?.cancel()
                        networkRun = scope.launch {
                            delay(NETWORK_DEBOUNCE_MS)
                            runAndAlert(app)
                        }
                    }
                }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb); callback = cb }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        periodic?.cancel(); periodic = null
        networkRun?.cancel(); networkRun = null
        callback?.let { cb ->
            runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        callback = null
    }

    /** Forgets the pinned key; the next connection pins afresh. For a relay that rotated its certificate. */
    fun resetPin(context: Context) {
        prefs(context).edit().remove(KEY_HOST).remove(KEY_PIN).remove(KEY_PINNED_AT).apply()
    }

    /** The pinned SPKI hash and the host it belongs to, or null when nothing is pinned yet. */
    fun pinned(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return null
        val pin = p.getString(KEY_PIN, null) ?: return null
        return host to pin
    }

    /** The outcome of the last run this install made, or null. */
    fun lastResult(context: Context): Pair<Long, String>? {
        val p = prefs(context)
        val ts = p.getLong(KEY_LAST_TS, 0L)
        val text = p.getString(KEY_LAST_RESULT, null) ?: return null
        return ts to text
    }

    private suspend fun runAndAlert(app: Context) {
        check(app).collect { r ->
            if (!r.intercepted) return@collect
            val detail = buildString {
                append(r.method)
                r.proxyHost?.let { append(". Proxy: ").append(it) }
                r.relayHost?.let { append(". Relay: ").append(it) }
                r.observedIssuer?.let { append(". Certificate issued by: ").append(it) }
                if (r.chainTrusted == true && r.observedSpki != r.pinnedSpki && r.pinnedSpki != null) {
                    append(". The substitute certificate is trusted by this phone, which means a root was installed to make it so.")
                }
                append(" Treat every connection from this phone as read by a third party until the cause is found.")
            }
            Registry.publishAlert(
                Alert(
                    id = "tls_canary@${r.ts}",
                    ts = r.ts,
                    severity = Severity.CRITICAL,
                    title = "TLS interception indicator",
                    detail = detail,
                    kind = EventKind.PLATFORM,
                    dedupeKey = "tls_canary"
                ),
                dedupeWindowMs = 3600_000L
            )
        }
    }

    // ── One run ────────────────────────────────────────────────────────────

    private fun runOnce(app: Context): TlsCanaryResult {
        val proxies = proxyFindings(app)
        val proxyMethod = proxies.firstOrNull()?.first
        val proxyHost = proxies.firstOrNull()?.second

        val relayUrl = CommsConfig(app).relayUrl
        val uri = relayUrl?.let { runCatching { URI(it) }.getOrNull() }
        val host = uri?.host
        if (host.isNullOrBlank()) {
            // No relay to probe: the proxy checks are still worth reporting.
            return remember(app, TlsCanaryResult(
                intercepted = proxyMethod != null,
                method = proxyMethod,
                proxyHost = proxyHost,
                error = "No relay configured; certificate pin not checked"
            ))
        }
        val port = uri.port.takeIf { it > 0 } ?: 443

        val observed = try {
            probe(host, port)
        } catch (e: Exception) {
            return remember(app, TlsCanaryResult(
                intercepted = proxyMethod != null,
                method = proxyMethod,
                proxyHost = proxyHost,
                relayHost = host,
                pinnedSpki = pinned(app)?.takeIf { it.first == host }?.second,
                error = "Connection failed: ${e.javaClass.simpleName}: ${e.message}"
            ))
        }

        val p = prefs(app)
        val pinnedHost = p.getString(KEY_HOST, null)
        val pinnedSpki = p.getString(KEY_PIN, null)
        val issuer = observed.leaf.issuerX500Principal.getName(X500Principal.RFC2253).let { CaAuditScanner.cn(it) ?: it }

        if (pinnedSpki == null || pinnedHost != host) {
            // First contact with this relay: pin it. A relay URL change moves the pin.
            p.edit().putString(KEY_HOST, host).putString(KEY_PIN, observed.spki).putLong(KEY_PINNED_AT, System.currentTimeMillis()).apply()
            return remember(app, TlsCanaryResult(
                intercepted = proxyMethod != null,
                method = proxyMethod,
                proxyHost = proxyHost,
                relayHost = host,
                pinnedSpki = observed.spki,
                observedSpki = observed.spki,
                observedIssuer = issuer,
                chainTrusted = observed.trusted,
                pinnedNow = true
            ))
        }

        val mismatch = observed.spki != pinnedSpki
        val method = when {
            mismatch && proxyMethod != null -> "Relay certificate key changed (pin mismatch) and a proxy is configured"
            mismatch && observed.trusted -> "Relay certificate key changed (pin mismatch); the new chain is trusted by this phone"
            mismatch -> "Relay certificate key changed (pin mismatch); the new chain is not trusted by the system store"
            else -> proxyMethod
        }
        return remember(app, TlsCanaryResult(
            intercepted = mismatch || proxyMethod != null,
            method = method,
            proxyHost = proxyHost,
            relayHost = host,
            pinnedSpki = pinnedSpki,
            observedSpki = observed.spki,
            observedIssuer = issuer,
            chainTrusted = observed.trusted
        ))
    }

    private fun remember(app: Context, r: TlsCanaryResult): TlsCanaryResult {
        val text = when {
            r.error != null && r.observedSpki == null -> r.error
            r.intercepted -> "INTERCEPTED: ${r.method}"
            r.pinnedNow -> "Pinned ${r.relayHost}"
            else -> "Clear (${r.relayHost})"
        }
        prefs(app).edit().putString(KEY_LAST_RESULT, text).putLong(KEY_LAST_TS, r.ts).apply()
        return r
    }

    private class Observed(val leaf: X509Certificate, val chain: List<X509Certificate>, val spki: String, val trusted: Boolean)

    /**
     * Opens TLS to [host]:[port] with a trust manager that records the server's
     * chain and accepts anything, then asks the platform's own trust manager
     * whether it would have accepted that chain. Nothing is sent after the
     * handshake; the socket is closed at once.
     */
    private fun probe(host: String, port: Int): Observed {
        var seen: Array<X509Certificate>? = null
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { seen = chain }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<X509TrustManager>(recorder), null) }
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            socket.sslParameters = params
            socket.startHandshake()
            val chain = seen?.toList()?.takeIf { it.isNotEmpty() }
                ?: socket.session.peerCertificates.filterIsInstance<X509Certificate>().takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("server presented no certificate")
            val leaf = chain.first()
            val trusted = runCatching {
                val tmf = TrustManagerFactory.getInstance("X509").apply { init(null as KeyStore?) }
                val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
                tm.checkServerTrusted(chain.toTypedArray(), "RSA")
                true
            }.getOrDefault(false)
            return Observed(leaf, chain, spkiHash(leaf), trusted)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** "sha256/<base64>" of the certificate's SubjectPublicKeyInfo, the form HPKP used. */
    fun spkiHash(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * Every proxy configured on the phone, as (description, "host:port"). A phone
     * that is not being intercepted has none: Android does not set one on its own.
     */
    fun proxyFindings(context: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(3)
        val cm = context.getSystemService(ConnectivityManager::class.java)

        fun describe(p: ProxyInfo?): String? {
            if (p == null) return null
            val pac = p.pacFileUrl?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            if (pac != null) return "PAC $pac"
            val host = p.host?.takeIf { it.isNotBlank() } ?: return null
            return if (p.port > 0) "$host:${p.port}" else host
        }

        runCatching { describe(cm?.defaultProxy) }.getOrNull()?.let { out += "Default HTTP proxy set on the active network" to it }

        runCatching {
            @Suppress("DEPRECATION")
            val networks = cm?.allNetworks ?: emptyArray()
            for (n in networks) {
                val proxy = describe(cm?.getLinkProperties(n)?.httpProxy) ?: continue
                val caps = cm?.getNetworkCapabilities(n)
                val kind = when {
                    caps == null -> "network"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> "network"
                }
                if (out.none { it.second == proxy }) out += "HTTP proxy on the $kind network's link" to proxy
            }
        }

        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it != ":0" && it != "null" }
            ?.let { g -> if (out.none { it.second == g }) out += "Global http_proxy setting (set by ADB or a device owner)" to g }

        return out
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
