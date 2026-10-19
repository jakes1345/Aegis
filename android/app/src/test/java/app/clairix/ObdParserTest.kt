package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ELM327 replies with headers on (ATH1): header and PCI/checksum bytes never leak into the data. */
class ObdParserTest {

    @Test
    fun can11BitSpeedStripsHeaderAndPci() {
        // Padding bytes after the PCI length must be ignored too.
        val raw = "7E8 03 41 0D 32 AA AA AA AA\r\r"
        assertEquals(listOf(0x32), ObdParser.pidData(raw, "010D", 0x0D, isCan = true))
    }

    @Test
    fun can11BitRpmFromTwoEcusTakesTheOneThatAnswersThePid() {
        val raw = "7E9 03 7F 01 12\r7E8 04 41 0C 1A F8\r"
        val data = ObdParser.pidData(raw, "010C", 0x0C, isCan = true)!!
        assertEquals(listOf(0x1A, 0xF8), data)
        assertEquals(1726, (256 * data[0] + data[1]) / 4)
    }

    @Test
    fun headerRecognisedEvenWhenProtocolUnknown() {
        assertEquals(listOf(0x7B), ObdParser.pidData("7E8 03 41 05 7B\r", "0105", 0x05, isCan = null))
    }

    @Test
    fun can29BitHeaderIsFourBytes() {
        val raw = "18 DA F1 10 03 41 0D 32\r"
        assertEquals(listOf(0x32), ObdParser.pidData(raw, "010D", 0x0D, isCan = true))
    }

    @Test
    fun legacyJ1850DropsHeaderAndChecksum() {
        val raw = "48 6B 10 41 0D 32 F5\r"
        assertEquals(listOf(0x32), ObdParser.pidData(raw, "010D", 0x0D, isCan = false))
    }

    @Test
    fun echoSearchingAndNoData() {
        assertEquals(
            listOf(0x32),
            ObdParser.pidData("010D\rSEARCHING...\r7E8 03 41 0D 32\r", "010D", 0x0D, isCan = true)
        )
        assertNull(ObdParser.pidData("NO DATA\r", "010D", 0x0D, isCan = true))
        assertNull(ObdParser.pidData("7E8 03 41 0C 32\r", "010D", 0x0D, isCan = true))
    }

    @Test
    fun canDtcsSingleFrameUseTheCountByte() {
        val raw = "7E8 06 43 02 01 33 02 00 00\r"
        assertEquals(listOf("P0133", "P0200"), ObdParser.dtcs(raw, isCan = true))
    }

    @Test
    fun canDtcsMultiFrameIsoTp() {
        val raw = "7E8 10 0A 43 04 01 33 C1 23\r7E8 21 81 05 00 00 00 00 00\r"
        assertEquals(listOf("P0133", "U0123", "B0105"), ObdParser.dtcs(raw, isCan = true))
    }

    @Test
    fun legacyDtcs() {
        val raw = "48 6B 10 43 01 33 00 00 00 00 A1\r"
        assertEquals(listOf("P0133"), ObdParser.dtcs(raw, isCan = false))
    }

    @Test
    fun dtcLetters() {
        assertEquals("P0300", ObdParser.decodeDtc(0x03, 0x00))
        assertEquals("C1234", ObdParser.decodeDtc(0x52, 0x34))
        assertEquals("B2ABC", ObdParser.decodeDtc(0xAA, 0xBC))
        assertEquals("U3FFF", ObdParser.decodeDtc(0xFF, 0xFF))
    }
}
