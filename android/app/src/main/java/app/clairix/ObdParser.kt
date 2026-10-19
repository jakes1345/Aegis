package app.clairix

import java.util.Locale

/**
 * Parses ELM327 replies with headers on (ATH1). Pure, so the JVM tests can feed it
 * captured replies. [isCan] is the protocol class from ATDPN (null when unknown).
 */
internal object ObdParser {

    private class Frame(val header: String, val bytes: List<Int>)

    private val ERROR_MARKERS = listOf("NO DATA", "UNABLE TO CONNECT", "ERROR", "STOPPED")
    private val DTC_SYSTEMS = charArrayOf('P', 'C', 'B', 'U')
    private const val HEX_CHARS = "0123456789ABCDEF"

    /** Data bytes after the "41 <pid>" echo of the first ECU that answered, or null. */
    fun pidData(raw: String, cmd: String, pid: Int, isCan: Boolean?): List<Int>? {
        val lines = cleanLines(raw, cmd) ?: return null
        return payloads(lines, isCan)
            .firstOrNull { it.size >= 3 && it[0] == 0x41 && it[1] == pid }
            ?.drop(2)
    }

    /** Stored DTCs from a mode 03 reply, from every ECU that answered, de-duplicated. */
    fun dtcs(raw: String, isCan: Boolean?): List<String> {
        val lines = cleanLines(raw, "03") ?: return emptyList()
        val can = isCan == true
        val codes = mutableListOf<String>()
        for (payload in payloads(lines, isCan)) {
            if (payload.isEmpty() || payload[0] != 0x43) continue
            val pairs = if (can) {
                val count = payload.getOrNull(1) ?: continue
                payload.drop(2).take(count * 2)
            } else {
                payload.drop(1)
            }
            var i = 0
            while (i + 1 < pairs.size) {
                val a = pairs[i]
                val b = pairs[i + 1]
                if (a != 0 || b != 0) codes += decodeDtc(a, b)
                i += 2
            }
        }
        return codes.distinct()
    }

    fun decodeDtc(a: Int, b: Int): String {
        val system = DTC_SYSTEMS[(a shr 6) and 0x03]
        val second = (a shr 4) and 0x03
        val third = a and 0x0F
        return String.format(Locale.US, "%c%d%X%02X", system, second, third, b)
    }

    private fun cleanLines(raw: String, cmd: String): List<String>? {
        val lines = raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(cmd, ignoreCase = true) }
            .filterNot { it.uppercase(Locale.US).startsWith("SEARCHING") }
            .filterNot { it.uppercase(Locale.US).startsWith("BUS INIT") }
        if (lines.isEmpty()) return null
        for (line in lines) {
            val upper = line.uppercase(Locale.US)
            if (ERROR_MARKERS.any { upper.contains(it) } || upper == "?") return null
        }
        return lines
    }

    private fun tokenize(line: String, isCan: Boolean?): List<String>? {
        val upper = line.uppercase(Locale.US)
        val tokens = if (upper.contains(' ')) {
            upper.split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            val prefix = if (isCan == true && upper.length % 2 == 1) 3 else 0
            buildList {
                if (prefix > 0) add(upper.substring(0, prefix))
                var i = prefix
                while (i + 2 <= upper.length) {
                    add(upper.substring(i, i + 2))
                    i += 2
                }
            }
        }
        if (tokens.isEmpty() || tokens.any { t -> t.any { it !in HEX_CHARS } }) return null
        return tokens
    }

    private fun parseFrame(line: String, isCan: Boolean?): Frame? {
        val tokens = tokenize(line, isCan) ?: return null
        val headerLen = when {
            tokens[0].length == 3 -> 1
            isCan == true -> 4
            else -> 3
        }
        if (tokens.size <= headerLen) return null
        val header = tokens.take(headerLen).joinToString(" ")
        var body = tokens.drop(headerLen).map { it.toInt(16) }
        // Legacy (J1850/ISO 9141/KWP) frames end with a checksum byte that is not part of the payload.
        if (isCan != true && headerLen == 3 && body.size > 1) body = body.dropLast(1)
        return Frame(header, body)
    }

    private fun payloads(lines: List<String>, isCan: Boolean?): List<List<Int>> {
        val frames = lines.mapNotNull { parseFrame(it, isCan) }
        val isoTp = isCan == true || frames.any { it.header.length == 3 }
        val grouped = frames.groupBy { it.header }.toSortedMap()
        val out = mutableListOf<List<Int>>()
        for ((_, ecuFrames) in grouped) {
            if (!isoTp) {
                ecuFrames.forEach { out += it.bytes }
                continue
            }
            var buffer = mutableListOf<Int>()
            var expected = 0
            for (frame in ecuFrames) {
                val bytes = frame.bytes
                if (bytes.isEmpty()) continue
                val pci = bytes[0]
                when (pci shr 4) {
                    0 -> out += bytes.drop(1).take(pci and 0x0F)
                    1 -> {
                        if (bytes.size < 2) continue
                        expected = ((pci and 0x0F) shl 8) or bytes[1]
                        buffer = bytes.drop(2).toMutableList()
                    }
                    2 -> {
                        if (expected == 0) continue
                        buffer.addAll(bytes.drop(1))
                    }
                }
                if (expected > 0 && buffer.size >= expected) {
                    out += buffer.take(expected)
                    buffer = mutableListOf()
                    expected = 0
                }
            }
            if (expected > 0 && buffer.isNotEmpty()) out += buffer.toList()
        }
        return out
    }
}
