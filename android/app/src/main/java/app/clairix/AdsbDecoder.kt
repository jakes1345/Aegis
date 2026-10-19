package app.clairix

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Aircraft(
    val icao: String,
    var callsign: String? = null,
    var altitudeFt: Int? = null,
    var lat: Double? = null,
    var lon: Double? = null,
    var speedKts: Int? = null,
    var verticalRateFpm: Int? = null,
    var headingDeg: Int? = null,
    var lastSeenMs: Long = 0L,
    var isEmergency: Boolean = false,
    var squawk: String? = null
)

class AdsbDecoder {

    private val _aircraft = MutableStateFlow<Map<String, Aircraft>>(emptyMap())
    val aircraft: StateFlow<Map<String, Aircraft>> = _aircraft.asStateFlow()

    private class Track(val aircraft: Aircraft) {
        var evenLat = 0
        var evenLon = 0
        var evenMs = 0L
        var oddLat = 0
        var oddLon = 0
        var oddMs = 0L
        var esEmergency = false
    }

    private val lock = Any()
    private val tracks = HashMap<String, Track>()
    private var dirty = false
    private var lastPublishMs = 0L

    @Volatile private var runner: Job? = null

    private val carry = FloatArray(FRAME_SAMPLES)
    private var carryLen = 0
    private var work = FloatArray(0)
    private val msg = IntArray(LONG_BYTES)

    suspend fun start(driver: SdrUsbDriver) {
        stop()
        coroutineScope {
            val job = launch(Dispatchers.Default) {
                carryLen = 0
                val keeper = launch {
                    while (isActive) {
                        delay(HOUSEKEEPING_MS)
                        evictStale(System.currentTimeMillis())
                    }
                }
                try {
                    driver.sampleFlow().collect { processChunk(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    publish(System.currentTimeMillis())
                } finally {
                    keeper.cancel()
                }
            }
            runner = job
            job.join()
            if (runner === job) runner = null
        }
    }

    fun stop() {
        runner?.cancel()
        runner = null
    }

    private fun processChunk(bytes: ByteArray) {
        val n = bytes.size / 2
        val total = carryLen + n
        if (work.size < total) work = FloatArray(total)
        val m = work
        System.arraycopy(carry, 0, m, 0, carryLen)
        var k = 0
        while (k < n) {
            val i = bytes[2 * k].toInt() and 0xFF
            val q = bytes[2 * k + 1].toInt() and 0xFF
            m[carryLen + k] = MAG_LUT[(i shl 8) or q]
            k++
        }

        val now = System.currentTimeMillis()
        val next = demodulate(m, total, now)
        val keep = total - next
        System.arraycopy(m, next, carry, 0, keep)
        carryLen = keep

        if (dirty && now - lastPublishMs >= PUBLISH_INTERVAL_MS) publish(now)
    }

    private fun demodulate(m: FloatArray, total: Int, now: Long): Int {
        val limit = total - FRAME_SAMPLES
        var j = 0
        while (j < limit) {
            if (isPreamble(m, j)) {
                val consumed = tryDecode(m, j, now)
                if (consumed > 0) {
                    j += consumed
                    continue
                }
            }
            j++
        }
        return maxOf(j, limit, 0).coerceAtMost(total)
    }

    // At 2 Msps the 0.5us preamble pulses land on samples 0, 2, 7 and 9; samples 1/3/8/10 may hold
    // split pulse energy from sampling phase, so they are excluded from the quiet-zone denominator.
    private fun isPreamble(m: FloatArray, j: Int): Boolean {
        val m0 = m[j]; val m1 = m[j + 1]; val m2 = m[j + 2]; val m3 = m[j + 3]
        val m4 = m[j + 4]; val m5 = m[j + 5]; val m6 = m[j + 6]; val m7 = m[j + 7]
        val m8 = m[j + 8]; val m9 = m[j + 9]
        if (!(m0 > m1 && m1 < m2 && m2 > m3 && m3 < m0 && m4 < m0 && m5 < m0 && m6 < m0 &&
                m7 > m8 && m8 < m9 && m9 > m6)
        ) return false
        val high = m0 + m2 + m7 + m9
        val quiet = m4 + m5 + m6 + m[j + 11] + m[j + 12] + m[j + 13] + m[j + 14] + m[j + 15]
        val score = high / (high + quiet + 0.001f)
        return score > PREAMBLE_THRESHOLD
    }

    private fun tryDecode(m: FloatArray, j: Int, now: Long): Int {
        val base = j + PREAMBLE_SAMPLES
        var df = 0
        for (bit in 0 until 5) {
            df = (df shl 1) or (if (m[base + 2 * bit] > m[base + 2 * bit + 1]) 1 else 0)
        }
        if (df !in SUPPORTED_DF) return 0
        val bits = if (df >= 16) LONG_BITS else SHORT_BITS
        val len = bits / 8

        msg.fill(0)
        for (bit in 0 until bits) {
            if (m[base + 2 * bit] > m[base + 2 * bit + 1]) {
                msg[bit shr 3] = msg[bit shr 3] or (0x80 ushr (bit and 7))
            }
        }
        return if (handleMessage(df, len, now)) PREAMBLE_SAMPLES + bits * 2 else 0
    }

    /** Decodes one already-sliced frame (hex, 14 or 28 digits). Used by the JVM tests. */
    internal fun decodeHex(hex: String, now: Long): Boolean {
        val len = hex.length / 2
        msg.fill(0)
        for (i in 0 until len) msg[i] = hex.substring(2 * i, 2 * i + 2).toInt(16)
        val df = msg[0] ushr 3
        if (df !in SUPPORTED_DF || len != (if (df >= 16) LONG_BYTES else LONG_BYTES / 2)) return false
        return handleMessage(df, len, now)
    }

    internal fun snapshot(now: Long): Map<String, Aircraft> {
        publish(now)
        return _aircraft.value
    }

    private fun handleMessage(df: Int, len: Int, now: Long): Boolean {
        val parity = (msg[len - 3] shl 16) or (msg[len - 2] shl 8) or msg[len - 1]
        val residual = crc24(msg, len - 3) xor parity

        val accepted = synchronized(lock) {
            when (df) {
                17 -> if (residual == 0) {
                    handleExtendedSquitter(icaoFromAa(), now)
                    true
                } else false
                18 -> if (residual == 0 && getBits(5, 3) == 0) {
                    handleExtendedSquitter(icaoFromAa(), now)
                    true
                } else false
                // DF11 parity is overlaid with the interrogator code, so a non-zero residual
                // only proves anything for an aircraft already heard; new tracks need IID 0,
                // or noise that passes the 17-bit check would create ghost aircraft.
                11 -> if (residual == 0) {
                    touch(icaoFromAa(), now)
                    true
                } else if ((residual and 0xFFFF80) == 0 && tracks.containsKey(icaoFromAa())) {
                    touch(icaoFromAa(), now)
                    true
                } else false
                0, 4, 16, 20 -> {
                    val track = tracks[hex(residual)]
                    if (track != null) {
                        track.aircraft.lastSeenMs = now
                        decodeAc13(getBits(19, 13))?.let { track.aircraft.altitudeFt = it }
                        dirty = true
                        true
                    } else false
                }
                5, 21 -> {
                    val track = tracks[hex(residual)]
                    if (track != null) {
                        track.aircraft.lastSeenMs = now
                        applySquawk(track, squawkString(decodeId13(getBits(19, 13))))
                        dirty = true
                        true
                    } else false
                }
                else -> false
            }
        }
        return accepted
    }

    private fun icaoFromAa(): String = hex((msg[1] shl 16) or (msg[2] shl 8) or msg[3])

    private fun hex(v: Int): String = "%06X".format(v and 0xFFFFFF)

    private fun touch(icao: String, now: Long): Track {
        val track = tracks.getOrPut(icao) { Track(Aircraft(icao)) }
        track.aircraft.lastSeenMs = now
        dirty = true
        return track
    }

    private fun handleExtendedSquitter(icao: String, now: Long) {
        val track = touch(icao, now)
        val ac = track.aircraft
        when (getBits(32, 5)) {
            in 1..4 -> {
                val sb = StringBuilder(8)
                for (c in 0 until 8) sb.append(CALLSIGN_CHARSET[getBits(40 + 6 * c, 6)])
                val cs = sb.toString().trim()
                if (cs.isNotEmpty() && '?' !in cs) ac.callsign = cs
            }
            in 9..18 -> {
                decodeAlt12(getBits(40, 12))?.let { ac.altitudeFt = it }
                val odd = getBits(53, 1) == 1
                val cprLat = getBits(54, 17)
                val cprLon = getBits(71, 17)
                if (odd) {
                    track.oddLat = cprLat; track.oddLon = cprLon; track.oddMs = now
                } else {
                    track.evenLat = cprLat; track.evenLon = cprLon; track.evenMs = now
                }
                if (track.evenMs != 0L && track.oddMs != 0L && abs(track.evenMs - track.oddMs) <= CPR_PAIR_WINDOW_MS) {
                    globalPosition(track, odd)?.let { (lat, lon) ->
                        ac.lat = lat
                        ac.lon = lon
                    }
                }
            }
            19 -> decodeVelocity(ac)
            28 -> if (getBits(37, 3) == 1) {
                track.esEmergency = getBits(40, 3) != 0
                val code = getBits(43, 13)
                if (code != 0) applySquawk(track, squawkString(decodeId13(code)))
                else ac.isEmergency = track.esEmergency || ac.squawk in EMERGENCY_SQUAWKS
            }
            else -> Unit
        }
    }

    private fun applySquawk(track: Track, squawk: String) {
        track.aircraft.squawk = squawk
        track.aircraft.isEmergency = track.esEmergency || squawk in EMERGENCY_SQUAWKS
    }

    private fun decodeVelocity(ac: Aircraft) {
        val st = getBits(37, 3)
        val mult = if (st == 2 || st == 4) 4 else 1
        when (st) {
            1, 2 -> {
                val vew = getBits(46, 10)
                val vns = getBits(57, 10)
                if (vew != 0 && vns != 0) {
                    val vx = (vew - 1) * mult * (if (getBits(45, 1) == 1) -1 else 1)
                    val vy = (vns - 1) * mult * (if (getBits(56, 1) == 1) -1 else 1)
                    ac.speedKts = hypot(vx.toDouble(), vy.toDouble()).roundToInt()
                    var hdg = Math.toDegrees(atan2(vx.toDouble(), vy.toDouble()))
                    if (hdg < 0) hdg += 360.0
                    ac.headingDeg = hdg.roundToInt() % 360
                }
            }
            3, 4 -> {
                if (getBits(45, 1) == 1) ac.headingDeg = (getBits(46, 10) * 360 / 1024) % 360
                val airspeed = getBits(57, 10)
                if (airspeed != 0) ac.speedKts = (airspeed - 1) * mult
            }
            else -> return
        }
        val vr = getBits(69, 9)
        if (vr != 0) ac.verticalRateFpm = (vr - 1) * 64 * (if (getBits(68, 1) == 1) -1 else 1)
    }

    private fun globalPosition(t: Track, newestOdd: Boolean): Pair<Double, Double>? =
        cprGlobal(t.evenLat, t.evenLon, t.oddLat, t.oddLon, newestOdd)

    private fun getBits(start: Int, count: Int): Int {
        var v = 0
        for (b in start until start + count) {
            val bit = (msg[b shr 3] ushr (7 - (b and 7))) and 1
            v = (v shl 1) or bit
        }
        return v
    }

    private fun evictStale(now: Long) {
        synchronized(lock) {
            val removed = tracks.entries.removeIf { now - it.value.aircraft.lastSeenMs > STALE_MS }
            if (removed) dirty = true
        }
        publish(now)
    }

    private fun publish(now: Long) {
        val snapshot = synchronized(lock) {
            dirty = false
            lastPublishMs = now
            tracks.mapValues { it.value.aircraft.copy() }
        }
        _aircraft.value = snapshot
    }

    internal companion object {
        fun crc24(data: IntArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                crc = ((crc shl 8) xor CRC_TABLE[((crc ushr 16) xor data[i]) and 0xFF]) and 0xFFFFFF
            }
            return crc
        }

        /** Airborne CPR global decode of an even/odd pair; null if the pair straddles an NL zone. */
        fun cprGlobal(evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, newestOdd: Boolean): Pair<Double, Double>? {
            val lat0 = evenLat.toDouble()
            val lat1 = oddLat.toDouble()
            val lon0 = evenLon.toDouble()
            val lon1 = oddLon.toDouble()

            val j = floor((59 * lat0 - 60 * lat1) / CPR_SCALE + 0.5)
            var rlat0 = AIR_DLAT0 * (cprMod(j, 60) + lat0 / CPR_SCALE)
            var rlat1 = AIR_DLAT1 * (cprMod(j, 59) + lat1 / CPR_SCALE)
            if (rlat0 >= 270.0) rlat0 -= 360.0
            if (rlat1 >= 270.0) rlat1 -= 360.0
            if (rlat0 < -90.0 || rlat0 > 90.0 || rlat1 < -90.0 || rlat1 > 90.0) return null

            val nl0 = cprNl(rlat0)
            val nl1 = cprNl(rlat1)
            if (nl0 != nl1) return null

            val lat: Double
            var lon: Double
            if (newestOdd) {
                val ni = maxOf(nl1 - 1, 1)
                val m = floor((lon0 * (nl1 - 1) - lon1 * nl1) / CPR_SCALE + 0.5)
                lon = (360.0 / ni) * (cprMod(m, ni) + lon1 / CPR_SCALE)
                lat = rlat1
            } else {
                val ni = maxOf(nl0, 1)
                val m = floor((lon0 * (nl0 - 1) - lon1 * nl0) / CPR_SCALE + 0.5)
                lon = (360.0 / ni) * (cprMod(m, ni) + lon0 / CPR_SCALE)
                lat = rlat0
            }
            lon -= floor((lon + 180.0) / 360.0) * 360.0
            return lat to lon
        }

        fun cprMod(a: Double, b: Int): Double {
            val r = a % b
            return if (r < 0) r + b else r
        }

        fun cprNl(lat: Double): Int {
            val a = abs(lat)
            if (a == 0.0) return 59
            if (a == 87.0) return 2
            if (a > 87.0) return 1
            val c = cos(PI / 180.0 * a)
            val x = 1.0 - (1.0 - cos(PI / (2.0 * CPR_NZ))) / (c * c)
            return floor(2.0 * PI / acos(x)).toInt()
        }

        fun decodeAlt12(a: Int): Int? {
            if (a == 0) return null
            if ((a and 0x10) != 0) {
                val n = ((a and 0xFE0) shr 1) or (a and 0x0F)
                return n * 25 - 1000
            }
            val ac13 = ((a and 0xFC0) shl 1) or (a and 0x3F)
            val hundreds = modeAToModeC(decodeId13(ac13))
            return if (hundreds < -12) null else hundreds * 100
        }

        fun decodeAc13(a: Int): Int? {
            if (a == 0) return null
            // M bit: metric altitude. Its encoding is not pinned down here (the old code also
            // dropped two bits instead of one), so report nothing rather than a wrong number.
            if ((a and 0x40) != 0) return null
            if ((a and 0x10) != 0) {
                val n = ((a and 0x1F80) shr 2) or ((a and 0x20) shr 1) or (a and 0x0F)
                return n * 25 - 1000
            }
            val hundreds = modeAToModeC(decodeId13(a))
            return if (hundreds < -12) null else hundreds * 100
        }

        fun decodeId13(f: Int): Int {
            var g = 0
            if ((f and 0x1000) != 0) g = g or 0x0010
            if ((f and 0x0800) != 0) g = g or 0x1000
            if ((f and 0x0400) != 0) g = g or 0x0020
            if ((f and 0x0200) != 0) g = g or 0x2000
            if ((f and 0x0100) != 0) g = g or 0x0040
            if ((f and 0x0080) != 0) g = g or 0x4000
            if ((f and 0x0020) != 0) g = g or 0x0100
            if ((f and 0x0010) != 0) g = g or 0x0001
            if ((f and 0x0008) != 0) g = g or 0x0200
            if ((f and 0x0004) != 0) g = g or 0x0002
            if ((f and 0x0002) != 0) g = g or 0x0400
            if ((f and 0x0001) != 0) g = g or 0x0004
            return g
        }

        fun squawkString(gillham: Int): String = "%04x".format(gillham and 0xFFFF)

        fun modeAToModeC(modeA: Int): Int {
            if ((modeA and 0xFFFF8889.toInt()) != 0 || (modeA and 0xF0) == 0) return INVALID_ALT
            var oneHundreds = 0
            var fiveHundreds = 0
            if ((modeA and 0x0010) != 0) oneHundreds = oneHundreds xor 0x007
            if ((modeA and 0x0020) != 0) oneHundreds = oneHundreds xor 0x003
            if ((modeA and 0x0040) != 0) oneHundreds = oneHundreds xor 0x001
            if ((oneHundreds and 5) == 5) oneHundreds = oneHundreds xor 2
            if (oneHundreds > 5) return INVALID_ALT

            if ((modeA and 0x0002) != 0) fiveHundreds = fiveHundreds xor 0x0FF
            if ((modeA and 0x0004) != 0) fiveHundreds = fiveHundreds xor 0x07F
            if ((modeA and 0x1000) != 0) fiveHundreds = fiveHundreds xor 0x03F
            if ((modeA and 0x2000) != 0) fiveHundreds = fiveHundreds xor 0x01F
            if ((modeA and 0x4000) != 0) fiveHundreds = fiveHundreds xor 0x00F
            if ((modeA and 0x0100) != 0) fiveHundreds = fiveHundreds xor 0x007
            if ((modeA and 0x0200) != 0) fiveHundreds = fiveHundreds xor 0x003
            if ((modeA and 0x0400) != 0) fiveHundreds = fiveHundreds xor 0x001

            if ((fiveHundreds and 1) != 0) oneHundreds = 6 - oneHundreds
            return fiveHundreds * 5 + oneHundreds - 13
        }

        private const val PREAMBLE_SAMPLES = 16
        private const val LONG_BITS = 112
        private const val SHORT_BITS = 56
        private const val LONG_BYTES = LONG_BITS / 8
        private const val FRAME_SAMPLES = PREAMBLE_SAMPLES + LONG_BITS * 2
        private const val PREAMBLE_THRESHOLD = 0.6f

        private const val HOUSEKEEPING_MS = 10_000L
        private const val STALE_MS = 60_000L
        private const val PUBLISH_INTERVAL_MS = 250L
        private const val CPR_PAIR_WINDOW_MS = 10_000L

        private const val CPR_SCALE = 131072.0
        private const val CPR_NZ = 15.0
        private const val AIR_DLAT0 = 360.0 / 60.0
        private const val AIR_DLAT1 = 360.0 / 59.0
        private const val INVALID_ALT = -9999

        private const val CALLSIGN_CHARSET =
            "?ABCDEFGHIJKLMNOPQRSTUVWXYZ????? ???????????????0123456789??????"

        private val SUPPORTED_DF = setOf(0, 4, 5, 11, 16, 17, 18, 20, 21)
        private val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

        private val MAG_LUT = FloatArray(65536) { idx ->
            val i = (idx ushr 8) - 127
            val q = (idx and 0xFF) - 127
            sqrt((i * i + q * q).toFloat())
        }

        private val CRC_TABLE = IntArray(256) { b ->
            var crc = b shl 16
            repeat(8) {
                crc = crc shl 1
                if ((crc and 0x1000000) != 0) crc = crc xor 0x1FFF409
            }
            crc and 0xFFFFFF
        }
    }
}
