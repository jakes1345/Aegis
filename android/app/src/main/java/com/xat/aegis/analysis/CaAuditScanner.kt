package com.xat.aegis.analysis

import android.content.Context
import com.xat.aegis.PhoneHealthFinding
import com.xat.aegis.Severity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * A certificate authority this phone trusts that it should not, or that nobody
 * can vouch for. [severity] is HIGH for a CA someone installed by hand (the
 * standard way to intercept TLS on a phone one has physical access to) and
 * MEDIUM for a root in the system store that is not part of the AOSP bundle,
 * which a carrier or vendor image can add.
 */
data class SuspiciousCa(
    val issuerDn: String,
    val subjectDn: String,
    /** SHA-256 of the DER certificate, colon-separated hex. */
    val fingerprint: String,
    val validFrom: Long,
    val validTo: Long,
    val severity: Severity = Severity.HIGH,
    /** "user" for a hand-installed CA, "system" for one in the OS store. */
    val source: String = "user",
    val reason: String = ""
) {
    /** The CN of the subject, or the whole DN when it has none. */
    val subjectCn: String get() = CaAuditScanner.cn(subjectDn) ?: subjectDn
}

/**
 * Audits the trust store: which root certificates this phone will accept a TLS
 * server certificate from.
 *
 * Two sources are read. The platform's default X.509 trust manager hands over
 * every accepted issuer at once (system plus user roots). The AndroidCAStore
 * keystore lists the same certificates by alias, and its aliases say where
 * each one came from: "system:<hash>" or "user:<hash>". A root under a "user:"
 * alias was installed through Settings → Security → Install a certificate,
 * which is exactly what a stalker with the PIN or an MDM would do to read the
 * phone's HTTPS traffic through a proxy.
 *
 * Anything not under a user alias is compared against the AOSP bundle below.
 * The bundle changes a little with every Android release, so a system root not
 * in the list is reported as MEDIUM, worth a look, not an alarm.
 */
object CaAuditScanner {

    /**
     * Subject CNs of roots shipped in AOSP's system CA bundle (system/ca-certificates).
     * Representative rather than exhaustive; a root whose CN is not here is also
     * accepted when its organisation is in [KNOWN_SYSTEM_ORGS].
     */
    val KNOWN_SYSTEM_CA_CN: Set<String> = setOf(
        "GTS Root R1", "GTS Root R2", "GTS Root R3", "GTS Root R4",
        "GlobalSign Root CA", "GlobalSign", "GlobalSign Root R46", "GlobalSign Root E46",
        "DigiCert Global Root CA", "DigiCert Global Root G2", "DigiCert Global Root G3",
        "DigiCert High Assurance EV Root CA", "DigiCert Assured ID Root CA", "DigiCert Trusted Root G4",
        "DigiCert TLS RSA4096 Root G5", "DigiCert TLS ECC P384 Root G5",
        "Baltimore CyberTrust Root",
        "ISRG Root X1", "ISRG Root X2",
        "COMODO RSA Certification Authority", "COMODO ECC Certification Authority", "COMODO Certification Authority",
        "USERTrust RSA Certification Authority", "USERTrust ECC Certification Authority",
        "AAA Certificate Services",
        "Sectigo Public Server Authentication Root R46", "Sectigo Public Server Authentication Root E46",
        "Entrust Root Certification Authority", "Entrust Root Certification Authority - G2",
        "Entrust Root Certification Authority - EC1", "Entrust Root Certification Authority - G4",
        "Go Daddy Root Certificate Authority - G2", "Go Daddy Class 2 Certification Authority",
        "Starfield Root Certificate Authority - G2", "Starfield Class 2 Certification Authority",
        "Starfield Services Root Certificate Authority - G2",
        "Amazon Root CA 1", "Amazon Root CA 2", "Amazon Root CA 3", "Amazon Root CA 4",
        "Microsoft RSA Root Certificate Authority 2017", "Microsoft ECC Root Certificate Authority 2017",
        "QuoVadis Root CA 1 G3", "QuoVadis Root CA 2", "QuoVadis Root CA 2 G3", "QuoVadis Root CA 3", "QuoVadis Root CA 3 G3",
        "SwissSign Gold CA - G2", "SwissSign Silver CA - G2",
        "T-TeleSec GlobalRoot Class 2", "T-TeleSec GlobalRoot Class 3",
        "SecureTrust CA", "Secure Global CA",
        "Certum Trusted Network CA", "Certum Trusted Network CA 2", "Certum EC-384 CA", "Certum Trusted Root CA",
        "Buypass Class 2 Root CA", "Buypass Class 3 Root CA",
        "Actalis Authentication Root CA",
        "D-TRUST Root Class 3 CA 2 2009", "D-TRUST BR Root CA 1 2020", "D-TRUST EV Root CA 1 2020",
        "Hongkong Post Root CA 1", "Hongkong Post Root CA 3",
        "SSL.com Root Certification Authority RSA", "SSL.com Root Certification Authority ECC",
        "SSL.com EV Root Certification Authority RSA R2", "SSL.com EV Root Certification Authority ECC",
        "IdenTrust Commercial Root CA 1", "IdenTrust Public Sector Root CA 1",
        "Security Communication RootCA2", "Security Communication RootCA3", "Security Communication ECC RootCA1",
        "SecureSign RootCA11",
        "XRamp Global Certification Authority",
        "TWCA Root Certification Authority", "TWCA Global Root CA",
        "Certigna", "Certigna Root CA",
        "Atos TrustedRoot 2011", "Atos TrustedRoot Root CA RSA TLS 2021", "Atos TrustedRoot Root CA ECC TLS 2021",
        "AffirmTrust Commercial", "AffirmTrust Networking", "AffirmTrust Premium", "AffirmTrust Premium ECC",
        "emSign Root CA - G1", "emSign ECC Root CA - G3", "emSign Root CA - C1", "emSign ECC Root CA - C3",
        "Hellenic Academic and Research Institutions RootCA 2015",
        "Hellenic Academic and Research Institutions ECC RootCA 2015",
        "HARICA TLS RSA Root CA 2021", "HARICA TLS ECC Root CA 2021",
        "OISTE WISeKey Global Root GB CA", "OISTE WISeKey Global Root GC CA",
        "NetLock Arany (Class Gold) Főtanúsítvány",
        "Chambers of Commerce Root - 2008", "Global Chambersign Root - 2008",
        "Izenpe.com",
        "Staat der Nederlanden EV Root CA",
        "e-Szigno Root CA 2017", "Microsec e-Szigno Root CA 2009",
        "GDCA TrustAUTH R5 ROOT",
        "UCA Global G2 Root", "UCA Extended Validation Root",
        "vTrus Root CA", "vTrus ECC Root CA",
        "Telia Root CA v2", "TeliaSonera Root CA v1",
        "ANF Secure Server Root CA",
        "Certainly Root R1", "Certainly Root E1",
        "TunTrust Root CA",
        "Autoridad de Certificacion Firmaprofesional CIF A62634068",
        "Trustwave Global Certification Authority",
        "Trustwave Global ECC P256 Certification Authority", "Trustwave Global ECC P384 Certification Authority",
        "E-Tugra Global Root CA RSA v3", "E-Tugra Global Root CA ECC v3",
        "Cybertrust Global Root",
        "Network Solutions Certificate Authority",
        "ePKI Root Certification Authority",
        "SZAFIR ROOT CA2",
        "BJCA Global Root CA1", "BJCA Global Root CA2",
        "CommScope Public Trust RSA Root-01", "CommScope Public Trust RSA Root-02",
        "CommScope Public Trust ECC Root-01", "CommScope Public Trust ECC Root-02",
        "TrustAsia Global Root CA G3", "TrustAsia Global Root CA G4",
        "Sectigo Public Server Authentication Root R46",
        "AC RAIZ FNMT-RCM", "AC RAIZ FNMT-RCM SERVIDORES SEGUROS",
        "CA Disig Root R2",
        "Certum Trusted Network CA",
        "Hongkong Post Root CA 3",
        "NAVER Global Root Certification Authority",
        "Security Communication RootCA2",
        "Telekom Security TLS ECC Root 2020", "Telekom Security TLS RSA Root 2023",
        "D-Trust SBR Root CA 1 2022", "D-Trust SBR Root CA 2 2022",
        "FIRMAPROFESIONAL CA ROOT-A WEB",
        "SwissSign RSA TLS Root CA 2022 - 1",
        "TWCA CYBER Root CA",
        "TWCA Global Root CA"
    )

    /** Organisations (the O= attribute) of the AOSP bundle, matched as substrings. */
    val KNOWN_SYSTEM_ORGS: Set<String> = setOf(
        "Google Trust Services", "GlobalSign", "DigiCert", "Comodo", "COMODO", "Sectigo", "The USERTRUST Network",
        "Internet Security Research Group", "Entrust", "GoDaddy.com", "Starfield Technologies", "Amazon",
        "Microsoft Corporation", "QuoVadis", "SwissSign", "T-Systems", "Deutsche Telekom", "SecureTrust",
        "Unizeto", "Asseco Data Systems", "Buypass", "Actalis", "D-Trust", "Hongkong Post", "SSL Corporation",
        "IdenTrust", "SECOM Trust", "Japan Certification Services", "XRamp", "TAIWAN-CA", "Dhimyotis", "Atos",
        "AffirmTrust", "eMudhra", "Hellenic Academic", "WISeKey", "NetLock", "AC Camerfirma", "IZENPE",
        "Staat der Nederlanden", "Microsec", "GUANG DONG CERTIFICATE AUTHORITY", "UniTrust", "iTrusChina",
        "Telia", "TeliaSonera", "ANF Autoridad de Certificacion", "Certainly", "Agence Nationale de Certification Electronique",
        "Firmaprofesional", "Trustwave", "E-Tuğra", "E-Tugra", "Cybertrust", "Baltimore", "Network Solutions",
        "Chunghwa Telecom", "Krajowa Izba Rozliczeniowa", "BEIJING CERTIFICATE AUTHORITY", "CommScope",
        "TrustAsia", "FNMT-RCM", "Disig", "NAVER", "Telekom Security", "Let's Encrypt"
    )

    private val hex = "0123456789ABCDEF".toCharArray()

    /**
     * The audit. Reads the keystore and parses every root; a few milliseconds
     * on a modern phone but file I/O, so call it off the main thread.
     */
    fun scan(context: Context): List<SuspiciousCa> {
        val out = LinkedHashMap<String, SuspiciousCa>()

        // Source 1: the AndroidCAStore, which tells user roots from system roots.
        val userFingerprints = HashSet<String>()
        val systemFingerprints = HashSet<String>()
        runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").also { it.load(null, null) }
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = runCatching { ks.getCertificate(alias) as? X509Certificate }.getOrNull() ?: continue
                val fp = fingerprint(cert)
                when {
                    alias.startsWith("user:") -> {
                        userFingerprints += fp
                        out[fp] = describe(
                            cert, fp, Severity.HIGH, "user",
                            "Installed by hand on this phone. A user-added root lets whoever added it decrypt " +
                                "HTTPS traffic of browsers and apps that trust user CAs, by routing the phone through a proxy. " +
                                "Remove it under Settings → Security → Encryption & credentials → User credentials unless you put it there yourself."
                        )
                    }
                    alias.startsWith("system:") -> systemFingerprints += fp
                }
            }
        }

        // Source 2: what the default trust manager actually accepts. This is the
        // operative list; a root here that the keystore did not label is treated as
        // system-added by the OS image (or injected some other way).
        val accepted: Array<X509Certificate> = runCatching {
            val tmf = TrustManagerFactory.getInstance("X509").apply { init(null as KeyStore?) }
            tmf.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }.getOrDefault(emptyArray())

        for (cert in accepted) {
            val fp = fingerprint(cert)
            if (fp in out) continue
            if (fp in userFingerprints) continue
            val subject = cert.subjectX500Principal.getName(X500Principal.RFC2253)
            if (isKnownSystemRoot(subject)) continue
            val source = if (fp in systemFingerprints) "system" else "unknown"
            out[fp] = describe(
                cert, fp, if (source == "system") Severity.MEDIUM else Severity.HIGH, source,
                if (source == "system")
                    "A root in the system store that is not part of the standard Android bundle. Carrier and " +
                        "manufacturer images sometimes add roots; a modified system image can too. Confirm it is expected."
                else
                    "Trusted by this phone but not listed in the system or user store. Only a modified trust " +
                        "manager or OS component can do that; treat the phone's TLS as compromised until explained."
            )
        }

        return out.values.sortedWith(compareByDescending<SuspiciousCa> { it.severity.ordinal }.thenBy { it.subjectCn })
    }

    /** The audit's findings in the Device tab's own type, one row per root. */
    fun scanAsFindings(context: Context): List<PhoneHealthFinding> = scan(context).map { ca ->
        PhoneHealthFinding(
            id = "ca_${ca.fingerprint.take(23)}",
            severity = ca.severity,
            category = "Trust store",
            title = (if (ca.source == "user") "User-installed CA: " else "Unlisted root CA: ") + ca.subjectCn,
            detail = ca.reason + " Issuer: ${cn(ca.issuerDn) ?: ca.issuerDn}. Valid ${date(ca.validFrom)} – ${date(ca.validTo)}. SHA-256 ${ca.fingerprint}."
        )
    }

    /** True when the subject names a root in the AOSP bundle by CN or by organisation. */
    fun isKnownSystemRoot(subjectDn: String): Boolean {
        val cn = cn(subjectDn)
        if (cn != null && cn in KNOWN_SYSTEM_CA_CN) return true
        val org = attribute(subjectDn, "O") ?: return false
        return KNOWN_SYSTEM_ORGS.any { org.contains(it, ignoreCase = true) }
    }

    private fun describe(cert: X509Certificate, fp: String, severity: Severity, source: String, reason: String) = SuspiciousCa(
        issuerDn = cert.issuerX500Principal.getName(X500Principal.RFC2253),
        subjectDn = cert.subjectX500Principal.getName(X500Principal.RFC2253),
        fingerprint = fp,
        validFrom = cert.notBefore.time,
        validTo = cert.notAfter.time,
        severity = severity,
        source = source,
        reason = reason
    )

    /** SHA-256 of the DER encoding, "AB:CD:…". */
    fun fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val sb = StringBuilder(digest.size * 3)
        for ((i, b) in digest.withIndex()) {
            if (i > 0) sb.append(':')
            sb.append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** The CN attribute of an RFC 2253 DN, or null. */
    fun cn(dn: String): String? = attribute(dn, "CN")

    /**
     * One attribute of an RFC 2253 DN. Values with escaped commas ("\,") are kept
     * whole; a quoted value has its quotes removed.
     */
    fun attribute(dn: String, type: String): String? {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var escaped = false
        var quoted = false
        for (ch in dn) {
            when {
                escaped -> { sb.append(ch); escaped = false }
                ch == '\\' -> { sb.append(ch); escaped = true }
                ch == '"' -> { quoted = !quoted; sb.append(ch) }
                (ch == ',' || ch == '+') && !quoted -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        val prefix = "$type="
        val raw = parts.map { it.trim() }.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return null
        return raw.substring(prefix.length).trim().removeSurrounding("\"").replace("\\,", ",").replace("\\+", "+")
            .takeIf { it.isNotEmpty() }
    }

    private fun date(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
}
