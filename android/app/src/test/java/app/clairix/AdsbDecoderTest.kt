package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mode S / ADS-B decoding against the worked examples of "The 1090 MHz Riddle". */
class AdsbDecoderTest {

    private fun bytes(hex: String) = IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }

    @Test
    fun crc24OfValidFramesMatchesTheirParity() {
        for (hex in listOf(
            "8D40621D58C382D690C8AC2863A7",
            "8D40621D58C386435CC412692AD6",
            "8D4840D6202CC371C32CE0576098",
            "8D485020994409940838175B284F"
        )) {
            val b = bytes(hex)
            val parity = (b[11] shl 16) or (b[12] shl 8) or b[13]
            assertEquals(hex, parity, AdsbDecoder.crc24(b, 11))
        }
    }

    @Test
    fun aCorruptedFrameIsRejected() {
        val d = AdsbDecoder()
        assertFalse(d.decodeHex("8D4840D6202CC371C32CE0576099", 1_000))
        assertTrue(d.snapshot(1_000).isEmpty())
    }

    @Test
    fun cprGlobalDecodeOfTheReferencePair() {
        val d = AdsbDecoder()
        assertTrue(d.decodeHex("8D40621D58C386435CC412692AD6", 1_000)) // odd, older
        assertTrue(d.decodeHex("8D40621D58C382D690C8AC2863A7", 1_500)) // even, newest
        val ac = d.snapshot(1_500)["40621D"]
        assertNotNull(ac)
        assertEquals(52.2572, ac!!.lat!!, 1e-4)
        assertEquals(3.91937, ac.lon!!, 1e-4)
        assertEquals(38000, ac.altitudeFt)
    }

    @Test
    fun cprPureFunctionMatchesBothNewestChoices() {
        // Fields of the reference pair: even lat/lon 93000/51372, odd 74158/50194.
        val even = AdsbDecoder.cprGlobal(93000, 51372, 74158, 50194, newestOdd = false)!!
        assertEquals(52.25720, even.first, 1e-5)
        assertEquals(3.91937, even.second, 1e-5)
        val odd = AdsbDecoder.cprGlobal(93000, 51372, 74158, 50194, newestOdd = true)!!
        assertEquals(52.26578, odd.first, 1e-5)
        assertEquals(3.93891, odd.second, 1e-4)
    }

    @Test
    fun nlTableEdges() {
        assertEquals(59, AdsbDecoder.cprNl(0.0))
        assertEquals(59, AdsbDecoder.cprNl(10.0))
        assertEquals(36, AdsbDecoder.cprNl(52.2572))
        assertEquals(2, AdsbDecoder.cprNl(87.0))
        assertEquals(1, AdsbDecoder.cprNl(88.0))
        assertEquals(AdsbDecoder.cprNl(-45.0), AdsbDecoder.cprNl(45.0))
    }

    @Test
    fun callsign() {
        val d = AdsbDecoder()
        assertTrue(d.decodeHex("8D4840D6202CC371C32CE0576098", 1_000))
        assertEquals("KLM1023", d.snapshot(1_000)["4840D6"]!!.callsign)
    }

    @Test
    fun groundSpeedHeadingAndVerticalRate() {
        val d = AdsbDecoder()
        assertTrue(d.decodeHex("8D485020994409940838175B284F", 1_000))
        val ac = d.snapshot(1_000)["485020"]!!
        assertEquals(159, ac.speedKts)
        assertEquals(183, ac.headingDeg)
        assertEquals(-832, ac.verticalRateFpm)
    }

    @Test
    fun qBitAltitudeIs25FootSteps() {
        // 12-bit field of the reference even message: 0xC38 -> N = 1560 -> 38000 ft.
        assertEquals(38000, AdsbDecoder.decodeAlt12(0xC38))
        assertNull(AdsbDecoder.decodeAlt12(0))
    }

    @Test
    fun metricAc13IsNotReportedAsFeet() {
        assertNull(AdsbDecoder.decodeAc13(0x0040 or 0x0010 or 0x0123))
    }

    /** Independent Gillham encoder: Gray-coded 500 ft (D2..B4) and the 5-cycle 100 ft code (C1 C2 C4). */
    private fun gillham(feet: Int): Int {
        val n = (feet + 1200) / 100
        val five = n / 5
        var one = n % 5
        if (five % 2 == 1) one = 4 - one
        val gray = five xor (five shr 1)
        val fiveBits = intArrayOf(0x0002, 0x0004, 0x1000, 0x2000, 0x4000, 0x0100, 0x0200, 0x0400) // D2..B4, MSB first
        var code = 0
        for (i in 0 until 8) if ((gray shr (7 - i)) and 1 == 1) code = code or fiveBits[i]
        val c1 = 0x10; val c2 = 0x20; val c4 = 0x40
        code = code or intArrayOf(c4, c2 or c4, c2, c1 or c2, c1)[one]
        return code
    }

    @Test
    fun gillhamDecodeMatchesAnIndependentEncoder() {
        for (feet in -1200..60000 step 100) {
            assertEquals("$feet ft", feet / 100, AdsbDecoder.modeAToModeC(gillham(feet)))
        }
    }

    @Test
    fun df11WithNonZeroInterrogatorDoesNotCreateAircraft() {
        val d = AdsbDecoder()
        // A DF11 all-call reply from 4840D6 with IID 1 in the low parity bits.
        val head = intArrayOf(0x5D, 0x48, 0x40, 0xD6)
        val crc = AdsbDecoder.crc24(head, 4) xor 0x000001
        val hex = (head.toList() + listOf((crc shr 16) and 0xFF, (crc shr 8) and 0xFF, crc and 0xFF))
            .joinToString("") { "%02X".format(it) }
        assertFalse(d.decodeHex(hex, 1_000))
        assertTrue(d.snapshot(1_000).isEmpty())
        // Once the aircraft is known, the same reply refreshes it.
        assertTrue(d.decodeHex("8D4840D6202CC371C32CE0576098", 1_000))
        assertTrue(d.decodeHex(hex, 2_000))
        assertEquals(2_000L, d.snapshot(2_000)["4840D6"]!!.lastSeenMs)
    }
}
