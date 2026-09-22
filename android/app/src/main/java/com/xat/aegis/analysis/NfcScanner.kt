package com.xat.aegis.analysis

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import com.xat.aegis.CardProfile
import com.xat.aegis.NfcTag

object NfcScanner {

    // Known EMV Application Identifier prefixes → network name
    private val PAYMENT_AIDS = listOf(
        "A0000000031010" to "Visa",
        "A0000000032010" to "Visa Electron",
        "A0000000033010" to "Visa V Pay",
        "A0000000034010" to "Visa",
        "A0000000041010" to "Mastercard",
        "A0000000043060" to "Mastercard Maestro",
        "A000000025010801" to "American Express",
        "A0000000250107" to "American Express",
        "A000000029DC01" to "Discover",
        "A0000001523010" to "Discover",
        "A0000002771010" to "Interac",
        "A0000000651010" to "JCB",
        "A0000003241010" to "UnionPay"
    )

    fun parse(tag: Tag): NfcTag {
        val uid = tag.id.hex()
        val techs = tag.techList.map { it.substringAfterLast('.') }

        // ATQA + SAK (NFC-A only)
        var atqa: String? = null
        var sak: String? = null
        runCatching {
            NfcA.get(tag)?.let { a ->
                atqa = a.atqa.hex()
                sak  = "%02X".format(a.sak.toInt() and 0xFF)
            }
        }

        val profile = resolveProfile(techs, atqa, sak)

        // ISO-DEP: try PPSE, capture APDU pairs, check for payment card
        var isPayment = false
        var paymentNetwork: String? = null
        var apduPairs = emptyList<Pair<String, String>>()
        if (techs.contains("IsoDep")) {
            val iso = scanIsoDep(tag)
            isPayment      = iso.isPayment
            paymentNetwork = iso.network
            apduPairs      = iso.pairs
        }

        val payload = readNdef(tag)

        // Skimmer heuristics
        val flags = mutableListOf<String>()
        if (atqa != null && sak != null) {
            val sakByte = sak!!.toInt(16)
            val atqaIsMifareClassicRange = atqa in setOf("4400", "0400", "6200")
            val sakHasIsoDepBit = (sakByte and 0x20) != 0
            // MIFARE Classic ATQA + ISO-DEP SAK without MifareClassic tech = likely emulator chip
            if (atqaIsMifareClassicRange && sakHasIsoDepBit && !techs.contains("MifareClassic")) {
                flags += "ATQA/SAK mismatch — possible card emulator (clone/skimmer hardware)"
            }
        }
        // Unidentified ISO-DEP with no NDEF, no payment signature, not a known access card type
        if (techs.contains("IsoDep") && !isPayment && payload == null &&
            profile == CardProfile.UNKNOWN) {
            flags += "Unidentified ISO 14443-4 smart card — verify origin"
        }
        // NFC-B card that isn't a passport or payment card (ISO 14443-B skimmers exist)
        if (techs.contains("NfcB") && !isPayment && !techs.contains("IsoDep")) {
            flags += "ISO 14443-B tag without standard app — uncommon; verify"
        }

        val suspicious = flags.isNotEmpty() && !isPayment

        val resolvedProfile = when {
            isPayment -> when (paymentNetwork) {
                "Visa", "Visa Electron", "Visa V Pay" -> CardProfile.EMV_VISA
                "Mastercard", "Mastercard Maestro"    -> CardProfile.EMV_MASTERCARD
                "American Express"                     -> CardProfile.EMV_AMEX
                else                                   -> CardProfile.EMV_OTHER
            }
            else -> profile
        }

        val type = if (resolvedProfile != CardProfile.UNKNOWN) resolvedProfile.label
                   else typeLabel(techs)

        val note = buildNote(resolvedProfile, isPayment, paymentNetwork, techs, payload)

        return NfcTag(
            uid           = uid,
            techs         = techs,
            type          = type,
            payload       = payload,
            suspicious    = suspicious,
            note          = note,
            ts            = System.currentTimeMillis(),
            atqa          = atqa,
            sak           = sak,
            profile       = resolvedProfile,
            paymentNetwork = paymentNetwork,
            skimmerFlags  = flags,
            apduPairs     = apduPairs
        )
    }

    // ── Profile resolution ──────────────────────────────────────────────────

    private fun resolveProfile(techs: List<String>, atqa: String?, sak: String?): CardProfile {
        if (techs.contains("NfcF")) return CardProfile.FELICA
        if (techs.contains("NfcV")) return CardProfile.ISO15693

        val sakByte = sak?.toIntOrNull(16) ?: return CardProfile.UNKNOWN

        return when {
            techs.contains("MifareClassic") -> when (sakByte) {
                0x09 -> CardProfile.MIFARE_CLASSIC_1K   // Mini
                0x08 -> CardProfile.MIFARE_CLASSIC_1K
                0x18 -> CardProfile.MIFARE_CLASSIC_4K
                0x10 -> CardProfile.MIFARE_PLUS         // Plus SL2 2K
                0x20 -> CardProfile.MIFARE_DESFIRE       // DESFire reported via MIFARE tech
                0x28 -> CardProfile.MIFARE_PLUS          // Plus SL3
                else -> CardProfile.MIFARE_CLASSIC_1K
            }
            techs.contains("MifareUltralight") -> CardProfile.MIFARE_ULTRALIGHT
            techs.contains("IsoDep") -> when {
                // DESFire has characteristic ATQA values
                atqa in setOf("0344", "4403", "0304", "0344") -> CardProfile.MIFARE_DESFIRE
                sakByte == 0x28 -> CardProfile.MIFARE_PLUS
                sakByte == 0x20 -> CardProfile.ISO14443_4
                else            -> CardProfile.ISO14443_4
            }
            else -> CardProfile.UNKNOWN
        }
    }

    // ── ISO-DEP / EMV scan ──────────────────────────────────────────────────

    private data class IsoResult(
        val isPayment: Boolean,
        val network: String?,
        val pairs: List<Pair<String, String>>
    )

    private fun scanIsoDep(tag: Tag): IsoResult {
        val iso = IsoDep.get(tag) ?: return IsoResult(false, null, emptyList())
        return try {
            iso.connect()
            iso.timeout = 3000
            val pairs = mutableListOf<Pair<String, String>>()

            // SELECT PPSE — standard first command any POS terminal sends
            val ppseCmd = "00A4040E" + "32504159" + "2E535953" + "2E444446" + "303100"
            val ppseResp = iso.transceive(ppseCmd.hexToBytes())
            pairs += ppseCmd to ppseResp.hex()

            val sw = ppseResp.takeLast(2)
            if (sw.size < 2 || sw[0] != 0x90.toByte() || sw[1] != 0x00.toByte()) {
                iso.close()
                return IsoResult(false, null, pairs)
            }

            // Parse PPSE FCI for AIDs, identify payment network
            val aids = parsePpseAids(ppseResp)
            val network = aids.firstNotNullOfOrNull { aid ->
                PAYMENT_AIDS.firstOrNull { (prefix, _) ->
                    aid.uppercase().startsWith(prefix.take(10))
                }?.second
            }

            // SELECT the first AID and record the response
            if (aids.isNotEmpty()) {
                val aidBytes = aids.first().hexToBytes()
                val selCmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00,
                    aidBytes.size.toByte()) + aidBytes + byteArrayOf(0x00)
                runCatching {
                    val selResp = iso.transceive(selCmd)
                    pairs += selCmd.hex() to selResp.hex()
                }
            }

            iso.close()
            IsoResult(true, network, pairs)
        } catch (_: Exception) {
            runCatching { iso.close() }
            IsoResult(false, null, emptyList())
        }
    }

    // TLV scan for tag 0x4F (AID) inside a PPSE FCI response
    private fun parsePpseAids(resp: ByteArray): List<String> {
        val aids = mutableListOf<String>()
        var i = 0
        val limit = resp.size - 2  // skip SW bytes
        while (i < limit - 1) {
            if ((resp[i].toInt() and 0xFF) == 0x4F) {
                val len = resp[i + 1].toInt() and 0xFF
                if (len in 5..16 && i + 2 + len <= limit) {
                    aids += resp.slice(i + 2 until i + 2 + len).toByteArray().hex()
                    i += 2 + len
                    continue
                }
            }
            i++
        }
        return aids.distinct()
    }

    // ── NDEF ────────────────────────────────────────────────────────────────

    private fun readNdef(tag: Tag): String? = try {
        val ndef = Ndef.get(tag) ?: return null
        ndef.connect()
        val msg: NdefMessage = ndef.cachedNdefMessage ?: run { ndef.close(); return null }
        ndef.close()
        msg.records.mapNotNull { decodeRecord(it) }.joinToString(" | ").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun decodeRecord(r: NdefRecord): String? {
        val p = r.payload
        if (p.isEmpty()) return null
        return when (r.tnf) {
            NdefRecord.TNF_WELL_KNOWN -> when {
                r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                    val langLen = p[0].toInt() and 0x3F
                    String(p, 1 + langLen, p.size - 1 - langLen, Charsets.UTF_8)
                }
                r.type.contentEquals(NdefRecord.RTD_URI) ->
                    uriPrefix(p[0].toInt() and 0xFF) + String(p, 1, p.size - 1, Charsets.UTF_8)
                else -> p.hex()
            }
            NdefRecord.TNF_MIME_MEDIA  -> "MIME:${String(r.type)} ${p.hex().take(32)}"
            NdefRecord.TNF_ABSOLUTE_URI -> String(p, Charsets.UTF_8)
            else -> p.hex().take(32)
        }
    }

    private fun uriPrefix(code: Int) = when (code) {
        0x01 -> "http://www."; 0x02 -> "https://www."
        0x03 -> "http://";     0x04 -> "https://"
        0x05 -> "tel:";        0x06 -> "mailto:"
        else -> ""
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun typeLabel(techs: List<String>) = when {
        techs.contains("IsoDep")         -> "ISO 14443-4"
        techs.contains("MifareClassic")  -> "MIFARE Classic"
        techs.contains("MifareUltralight") -> "MIFARE Ultralight"
        techs.contains("Ndef")           -> "NDEF"
        techs.contains("NfcV")           -> "ISO 15693"
        techs.contains("NfcF")           -> "FeliCa"
        else                             -> "Unknown"
    }

    private fun buildNote(
        profile: CardProfile, isPayment: Boolean, network: String?,
        techs: List<String>, payload: String?
    ) = when {
        isPayment && network != null -> "$network contactless payment card"
        isPayment                    -> "Payment / transit card"
        profile == CardProfile.MIFARE_CLASSIC_1K || profile == CardProfile.MIFARE_CLASSIC_4K ->
            "MIFARE Classic — office badge / parking / building access credential"
        profile == CardProfile.MIFARE_DESFIRE ->
            "MIFARE DESFire — high-security access card (challenge-response, Flipper-proof)"
        profile == CardProfile.MIFARE_PLUS ->
            "MIFARE Plus SL3 — secure access card (AES-128 encrypted, clone-resistant)"
        profile == CardProfile.MIFARE_ULTRALIGHT ->
            "MIFARE Ultralight — disposable NFC sticker or event ticket"
        profile == CardProfile.FELICA ->
            "FeliCa — Japanese transit or payment card"
        profile == CardProfile.ISO15693 ->
            "ISO 15693 HF RFID tag — typically inventory or asset label"
        techs.contains("IsoDep") && payload == null ->
            "ISO 14443-4 smart card — no readable payload (access credential)"
        payload?.startsWith("https://") == true -> "NFC URL tag"
        else -> "Generic NFC tag"
    }

    fun ByteArray.hex()   = joinToString("") { "%02X".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val s = replace(" ", "")
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
