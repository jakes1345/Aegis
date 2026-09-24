package com.xat.aegis.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every payload type built, given its sender and read back exactly as the
 * receiving phone reads it. The first test is the bug that stopped every call
 * from ringing: the sender's identity key overwrote the call kind.
 */
class CommsWireTest {

    private val sender = CommsWire.Sender(
        number = "138656903", name = "Flap",
        ed25519 = "ED25519KEYbase64+/=", curve25519 = "CURVEKEYbase64", sealing = "SEALKEYbase64", signature = "SIGbase64",
    )

    /** Serialises and parses the payload the way it crosses the wire. */
    private fun roundTrip(payload: JSONObject): JSONObject =
        CommsWire.parse(CommsWire.withSender(payload, sender).toString()) ?: throw AssertionError("payload did not parse")

    @Test
    fun callKindSurvivesTheSenderIdentity() {
        for ((built, kind) in listOf(
            CommsWire.callOffer("cid-1", 1_700_000_000_000, "v=0 offer sdp") to CommsWire.CALL_OFFER,
            CommsWire.callAnswer("cid-1", "v=0 answer sdp") to CommsWire.CALL_ANSWER,
            CommsWire.callIce("cid-1", listOf(CommsWire.Candidate("0", 0, "candidate:1 1 udp 1 1.2.3.4 5 typ host"))) to CommsWire.CALL_ICE,
            CommsWire.callEnd("cid-1", "hangup") to CommsWire.CALL_END,
        )) {
            val got = roundTrip(built)
            assertEquals(CommsWire.T_CALL, CommsWire.type(got))
            assertEquals(kind, CommsWire.callKind(got))
            assertEquals("cid-1", got.getString(CommsWire.F_CALL_ID))
            assertEquals(sender, CommsWire.senderOf(got))
        }
    }

    @Test
    fun offerCarriesItsSdpAndTimestamp() {
        val sdp = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n" + "a=candidate:x\r\n".repeat(200)
        val got = roundTrip(CommsWire.callOffer("cid-2", 42L, sdp))
        assertEquals(sdp, got.getString(CommsWire.F_SDP))
        assertEquals(42L, got.getLong(CommsWire.F_TS))
    }

    @Test
    fun iceCandidatesRoundTrip() {
        val candidates = listOf(
            CommsWire.Candidate("0", 0, "candidate:842163049 1 udp 1677729535 203.0.113.7 49203 typ srflx"),
            CommsWire.Candidate("audio", 1, "candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host"),
        )
        assertEquals(candidates, CommsWire.candidates(roundTrip(CommsWire.callIce("cid-3", candidates))))
    }

    @Test
    fun endCarriesItsReason() {
        assertEquals("busy", roundTrip(CommsWire.callEnd("cid-4", "busy")).getString(CommsWire.F_REASON))
    }

    @Test
    fun messageRoundTrip() {
        val got = roundTrip(CommsWire.message("m-1", 1234L, "hello \"there\" ✓"))
        assertEquals(CommsWire.T_MSG, CommsWire.type(got))
        assertEquals("m-1", got.getString(CommsWire.F_ID))
        assertEquals(1234L, got.getLong(CommsWire.F_TS))
        assertEquals("hello \"there\" ✓", got.getString(CommsWire.F_BODY))
        assertEquals(sender, CommsWire.senderOf(got))
    }

    @Test
    fun receiptAndResyncRoundTrip() {
        val receipt = roundTrip(CommsWire.receipt(listOf("a", "b"), CommsWire.STATUS_READ))
        assertEquals(CommsWire.T_RECEIPT, CommsWire.type(receipt))
        assertEquals(listOf("a", "b"), CommsWire.receiptIds(receipt))
        assertEquals(CommsWire.STATUS_READ, receipt.getString(CommsWire.F_STATUS))
        assertEquals(CommsWire.T_RESYNC, CommsWire.type(roundTrip(CommsWire.resync())))
    }

    @Test
    fun withSenderRefusesToOverwriteAField() {
        val clashing = JSONObject().put(CommsWire.F_VERSION, 1).put(CommsWire.F_TYPE, "call").put("k", "offer")
        try {
            CommsWire.withSender(clashing, sender)
            fail("a payload using a reserved field was accepted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("k"))
        }
    }

    @Test
    fun noBuilderUsesAReservedField() {
        val all = listOf(
            CommsWire.message("i", 1, "b"), CommsWire.receipt(listOf("i"), "read"), CommsWire.resync(),
            CommsWire.callOffer("c", 1, "s"), CommsWire.callAnswer("c", "s"),
            CommsWire.callIce("c", listOf(CommsWire.Candidate("0", 0, "x"))), CommsWire.callEnd("c", "r"),
        )
        for (p in all) for (f in CommsWire.SENDER_FIELDS) assertTrue("${CommsWire.type(p)} uses $f", !p.has(f))
    }

    @Test
    fun parseRejectsOtherVersionsAndNonJson() {
        assertNull(CommsWire.parse("not json"))
        assertNull(CommsWire.parse("""{"v":2,"t":"msg"}"""))
        assertNotNull(CommsWire.parse("""{"v":1,"t":"msg"}"""))
    }

    @Test
    fun senderOfNeedsEveryKey() {
        val partial = JSONObject().put(CommsWire.F_FROM, "123456789").put(CommsWire.F_ED25519, "k")
        assertNull(CommsWire.senderOf(partial))
    }
}
