package com.trackdetect

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * HCE service that replays a recorded ISO 14443-4 APDU conversation.
 *
 * The user records a card by scanning it with the NFC reader; the scanner captures the
 * PPSE and AID SELECT exchanges. On emulation, those pairs are loaded here so the phone
 * responds to a reader exactly as the original card did.
 *
 * Limitations:
 * - Only works for ISO 14443-4 (IsoDep) cards. MIFARE Classic, Ultralight, FeliCa and
 *   ISO 15693 use different RF protocols that Android's HCE stack cannot emulate.
 * - Challenge-response cards (DESFire EV2, MIFARE Plus SL3) protect per-transaction
 *   nonces with private keys the phone does not hold, so replayed responses will be
 *   rejected by a secure reader. They are still stored for identification purposes.
 * - EMV payment cards are tokenized by the card network; raw APDU replay does not produce
 *   valid cryptograms and will be declined at any POS terminal.
 */
class CardEmulationService : HostApduService() {

    companion object {
        /** Populated by the Activity when the user activates emulation for a vault card. */
        @Volatile
        var activePairs: List<Pair<ByteArray, ByteArray>> = emptyList()

        private val SW_NOT_FOUND  = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_CONDITIONS = byteArrayOf(0x69.toByte(), 0x85.toByte())

        fun hexToBytes(hex: String): ByteArray {
            val s = hex.replace(" ", "")
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun loadPairs(hexPairs: List<Pair<String, String>>) {
            activePairs = hexPairs.map { (cmd, resp) -> hexToBytes(cmd) to hexToBytes(resp) }
        }
    }

    override fun processCommandApdu(command: ByteArray, extras: Bundle?): ByteArray {
        if (activePairs.isEmpty()) return SW_CONDITIONS

        // Exact match first
        activePairs.firstOrNull { (cmd, _) -> cmd.contentEquals(command) }
            ?.let { return it.second }

        // Prefix match on CLA+INS+P1+P2 (first 4 bytes) for commands with variable data
        if (command.size >= 4) {
            activePairs.firstOrNull { (cmd, _) ->
                cmd.size >= 4 && cmd.slice(0..3) == command.slice(0..3)
            }?.let { return it.second }
        }

        return SW_NOT_FOUND
    }

    override fun onDeactivated(reason: Int) {
        // Keep activePairs loaded — the user may tap again without re-arming.
        // Pairs are cleared when the user disables emulation from the UI.
    }
}
