package com.xat.aegis.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

/** Invite and pairing codes survive every form they travel in: QR text, invite link, app link. */
class PairingCodeTest {

    private val code = PairingCode(
        relayUrl = "https://aegis-comms.example.workers.dev",
        number = "482913605",
        ed25519 = "a+b/c=",
        curve25519 = "d+e/f=",
        sealing = "g+h/i=",
        signature = "j+k/l==",
        name = "Jake & Co",
        invite = "AbCdEfGhIjKlMnOpQrStUv"
    )

    @Test
    fun theQrTextRoundTrips() {
        assertEquals(code, PairingCode.decode(code.encode()))
        assertEquals(code, PairingCode.fromText("  " + code.encode() + "\n"))
    }

    @Test
    fun theInviteLinkRoundTrips() {
        val link = code.inviteLink()
        assertEquals(true, link.startsWith("https://aegis-comms.example.workers.dev/i#"))
        assertEquals(code, PairingCode.fromText(link))
        // Pasted as part of the shared message.
        assertEquals(code, PairingCode.fromText("Join me on Aegis: $link"))
    }

    @Test
    fun theAppLinkRoundTrips() {
        // What the invite page hands the app: encodeURIComponent(payload).
        val appLink = "aegis://invite?c=" + URLEncoder.encode(code.encode(), "UTF-8").replace("+", "%20")
        assertEquals(code, PairingCode.fromText(appLink))
    }

    @Test
    fun aContactCodeIsNotAnInvite() {
        val plain = code.copy(invite = null)
        assertEquals(plain, PairingCode.fromText(plain.encode()))
        assertNull(PairingCode.fromText(plain.encode())!!.invite)
    }

    @Test
    fun aMalformedInviteCodeIsDropped() {
        val bad = code.encode().replace("i=AbCdEfGhIjKlMnOpQrStUv", "i=short")
        assertNull(PairingCode.decode(bad)!!.invite)
        assertNull(PairingCode.fromText("https://relay/i#garbage"))
    }
}
