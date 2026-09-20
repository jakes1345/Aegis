package com.trackdetect.analysis

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import com.trackdetect.NfcTag

object NfcScanner {

    fun parse(tag: Tag): NfcTag {
        val uid = tag.id.hex()
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val type = classifyType(techs)
        val payload = readNdef(tag)
        val isPayment = isPaymentCard(tag, techs)
        val suspicious = !isPayment && (techs.contains("MifareClassic") || type == "ISO 14443-4")
        val note = when {
            isPayment -> "Payment or transit card"
            techs.contains("MifareClassic") ->
                "MIFARE Classic — commonly embedded in covert tracking hardware and access cards"
            techs.contains("IsoDep") ->
                "ISO 14443-4 smart card — verify intended purpose"
            techs.contains("MifareUltralight") -> "MIFARE Ultralight — disposable NFC sticker"
            payload != null && payload.startsWith("https://") -> "NFC URL tag"
            else -> "Generic NFC tag"
        }
        return NfcTag(
            uid = uid,
            techs = techs,
            type = type,
            payload = payload,
            suspicious = suspicious,
            note = note,
            ts = System.currentTimeMillis()
        )
    }

    private fun classifyType(techs: List<String>) = when {
        techs.contains("IsoDep") -> "ISO 14443-4"
        techs.contains("MifareClassic") -> "MIFARE Classic"
        techs.contains("MifareUltralight") -> "MIFARE Ultralight"
        techs.contains("Ndef") -> "NDEF"
        techs.contains("NfcV") -> "ISO 15693"
        else -> "Unknown"
    }

    private fun isPaymentCard(tag: Tag, techs: List<String>): Boolean {
        if (!techs.contains("IsoDep")) return false
        return try {
            val iso = IsoDep.get(tag) ?: return false
            iso.connect()
            // SELECT PPSE — standard first command a POS terminal sends
            val ppse = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E,
                0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E, 0x44,
                0x44, 0x46, 0x30, 0x31, 0x00
            )
            val resp = iso.transceive(ppse)
            iso.close()
            resp.size >= 2 && resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()
        } catch (_: Exception) { false }
    }

    private fun readNdef(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val msg: NdefMessage = ndef.cachedNdefMessage ?: run { ndef.close(); return null }
            ndef.close()
            msg.records.mapNotNull { decodeRecord(it) }.joinToString(" | ").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

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
            NdefRecord.TNF_MIME_MEDIA ->
                "MIME:${String(r.type)} ${p.hex().take(32)}"
            NdefRecord.TNF_ABSOLUTE_URI ->
                String(p, Charsets.UTF_8)
            else -> p.hex().take(32)
        }
    }

    private fun uriPrefix(code: Int) = when (code) {
        0x01 -> "http://www."; 0x02 -> "https://www."
        0x03 -> "http://"; 0x04 -> "https://"
        0x05 -> "tel:"; 0x06 -> "mailto:"
        0x07 -> "ftp://anonymous:anonymous@"
        else -> ""
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
