package com.xat.aegis.detect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * What a tracker told us over the DULT non-owner service. Fields fill in one at a
 * time as the indications arrive, so a collector can show "Apple…" before the model
 * name has landed. [complete] is set on the final emission, with [error] saying why
 * the session ended early when it did.
 *
 * [serialHash] is a SHA-256 prefix of the obfuscated identifier the tracker returned,
 * never the identifier itself: it is enough to recognise the same tracker again and
 * to hand to a manufacturer on request, and nothing more.
 */
data class DultResult(
    val address: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val category: String? = null,
    /** The 8-byte product data as hex, when the tracker returned it. */
    val productData: String? = null,
    val serialHash: String? = null,
    val soundStarted: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null
) {
    /** True once at least one query was answered — the tracker really is in separated mode. */
    val answered: Boolean
        get() = manufacturer != null || model != null || category != null ||
            productData != null || serialHash != null

    /** "Apple AirTag" / "Chipolo ONE Spot" / "Finder" — whatever the tracker said about itself. */
    val label: String?
        get() {
            val name = listOfNotNull(manufacturer, model).joinToString(" ").trim()
            if (name.isNotEmpty()) return name
            return category
        }
}

/**
 * Talks to a tracker over the IETF DULT ("Detecting Unwanted Location Trackers")
 * accessory protocol.
 *
 * A compliant tracker — AirTag, Find My items, Tile, Chipolo, Pebblebee, SmartTag
 * on current firmware — exposes a *Non-Owner Service* over GATT once it has been
 * away from its owner for a while. Anyone can connect to it and ask the tracker to
 * name its maker and model, to return an obfuscated serial that the maker can tie
 * to an account under legal process, and to make a sound so it can be found by ear.
 * None of this is possible while the tracker is with its owner: the service simply
 * is not advertised, and a connection attempt fails or finds nothing. That failure
 * is itself information — it means the tracker's owner is nearby.
 *
 * Commands are written to the control point and the answer comes back as an
 * indication on the same characteristic. One command is in flight at a time; the
 * whole exchange has a hard ten-second budget, and the link is dropped cleanly
 * afterwards so the tracker is not left holding a connection.
 */
object DultInterrogator {

    /** DULT Non-Owner Service. */
    val NON_OWNER_SERVICE: UUID = UUID.fromString("15190001-12F4-C99E-7DFA-3C4B7A2B6A1D")

    /** Non-owner control point: write-with-response for commands, indicate for answers. */
    val CONTROL_POINT: UUID = UUID.fromString("8E0C0001-1D68-FB79-7825-05A2BD6D6E3A")

    private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OP_GET_PRODUCT_DATA = 0x03
    const val OP_GET_MANUFACTURER_NAME = 0x04
    const val OP_GET_MODEL_NAME = 0x05
    const val OP_GET_ACCESSORY_CATEGORY = 0x06
    const val OP_GET_IDENTIFIER = 0x07
    const val OP_SOUND_START = 0x0E
    const val OP_SOUND_STOP = 0x0F

    /** DULT "Get_*_Response" opcodes are the request opcode with bit 11 set. */
    private const val RESPONSE_FLAG = 0x0800

    /** DULT Command_Response opcode: echoes the command and carries a status word. */
    private const val OP_COMMAND_RESPONSE = 0x0302

    /** Whole session budget: connect, discover, subscribe, every query, disconnect. */
    const val TIMEOUT_MS = 10_000L

    /** Larger than the 23-byte default so a manufacturer name fits in one indication. */
    private const val REQUESTED_MTU = 185

    /** Give the controller a moment to send the disconnect before the client is torn down. */
    private const val CLOSE_DELAY_MS = 400L

    /**
     * Tracker signature ids ([com.xat.aegis.TrackerType.id]) whose makers ship or have
     * announced DULT firmware. Generic GPS boxes and Eddystone beacons never will.
     */
    val SUPPORTED: Set<String> = setOf(
        "airtag", "findmy", "tile", "chipolo", "chipolo_spot",
        "pebblebee", "motorola_tag", "smarttag", "smarttag2"
    )

    /**
     * Accessory category values from the DULT accessory protocol draft. 1 is the
     * dedicated location tracker; the 129+ range is "a thing with a tracker in it".
     */
    private val CATEGORIES: Map<Int, String> = mapOf(
        1 to "Finder (location tracker)",
        129 to "Luggage", 130 to "Backpack", 131 to "Jacket", 132 to "Coat",
        133 to "Shoes", 134 to "Bike", 135 to "Scooter", 136 to "Stroller",
        137 to "Wheelchair", 138 to "Boat", 139 to "Helmet", 140 to "Skateboard",
        141 to "Skis", 142 to "Snowboard", 143 to "Surfboard", 144 to "Camera",
        145 to "Laptop", 146 to "Watch", 147 to "Flash drive", 148 to "Drone",
        149 to "Headphones", 150 to "Earphones", 151 to "Inhaler", 152 to "Sunglasses",
        153 to "Glasses", 154 to "Wallet", 155 to "Phone case", 156 to "Phone",
        157 to "Tablet", 158 to "Umbrella", 159 to "Keys", 160 to "Remote",
        161 to "Charging cable", 162 to "Keychain", 163 to "Water bottle",
        164 to "Toy", 165 to "Pet collar", 166 to "Medical device", 167 to "Vehicle"
    )

    fun categoryName(code: Int?): String? {
        if (code == null) return null
        return CATEGORIES[code] ?: "Category $code"
    }

    /**
     * Connects, asks the tracker who it is, and disconnects. Emits a growing
     * [DultResult] after every answer and a final one with [DultResult.complete] set.
     * With [startSound] the tracker is also asked to make a sound at the end, so the
     * user can find it by ear — off by default, because the service runs this on
     * every newly seen tracker and a café full of beeping AirTags helps nobody.
     */
    fun interrogate(
        device: BluetoothDevice,
        context: Context,
        startSound: Boolean = false
    ): Flow<DultResult> {
        val ops = ArrayList<Int>(6)
        ops.add(OP_GET_MANUFACTURER_NAME)
        ops.add(OP_GET_MODEL_NAME)
        ops.add(OP_GET_ACCESSORY_CATEGORY)
        ops.add(OP_GET_PRODUCT_DATA)
        ops.add(OP_GET_IDENTIFIER)
        if (startSound) ops.add(OP_SOUND_START)
        return session(device, context, ops)
    }

    /** Only the sound command: start the beeping, or stop it again. */
    fun sound(device: BluetoothDevice, context: Context, start: Boolean): Flow<DultResult> =
        session(device, context, listOf(if (start) OP_SOUND_START else OP_SOUND_STOP))

    @SuppressLint("MissingPermission") // Checked up front; every call is also wrapped.
    private fun session(device: BluetoothDevice, context: Context, ops: List<Int>): Flow<DultResult> = callbackFlow {
        val app = context.applicationContext
        val address = BleNames.formatMac(device.address ?: "")
        var result = DultResult(address)

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            trySend(result.copy(complete = true, error = "Nearby devices permission not granted"))
            close()
            return@callbackFlow
        }

        val lock = Any()
        val queue = ArrayDeque(ops)
        var pending: Int? = null
        var finished = false
        var gattRef: BluetoothGatt? = null
        var controlPoint: BluetoothGattCharacteristic? = null

        fun finish(error: String?) {
            synchronized(lock) {
                if (finished) return
                finished = true
                result = result.copy(complete = true, error = error ?: result.error)
            }
            trySend(result)
            close()
        }

        fun sendNext() {
            val gatt = gattRef
            val cp = controlPoint
            if (gatt == null || cp == null) { finish("Control point unavailable"); return }
            val op: Int
            synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) { pending = null; finish(null); return }
                pending = next
                op = next
            }
            if (!writeCharacteristic(gatt, cp, encode(op))) finish("Write of opcode 0x%02X failed".format(op))
        }

        fun onIndication(value: ByteArray) {
            val op = synchronized(lock) { pending } ?: return
            val updated = parse(op, value, result)
            synchronized(lock) { result = updated; pending = null }
            trySend(updated)
            sendNext()
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish("Connection failed (status $status)")
                            return
                        }
                        // A refused MTU request still has to lead to discovery.
                        val requested = try { gatt.requestMtu(REQUESTED_MTU) } catch (_: SecurityException) { false }
                        if (!requested) discover(gatt)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val why = if (result.answered) null
                        else "Tracker refused the connection (status $status) — it is probably still with its owner"
                        finish(why)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { discover(gatt) }

            private fun discover(gatt: BluetoothGatt) {
                val ok = try { gatt.discoverServices() } catch (_: SecurityException) { false }
                if (!ok) finish("Service discovery could not start")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { finish("Service discovery failed (status $status)"); return }
                val service = gatt.getService(NON_OWNER_SERVICE)
                if (service == null) {
                    finish("No DULT non-owner service — the tracker is with its owner, or predates the spec")
                    return
                }
                val cp = service.getCharacteristic(CONTROL_POINT)
                if (cp == null) { finish("DULT service present but no control point"); return }
                controlPoint = cp
                val subscribed = try { gatt.setCharacteristicNotification(cp, true) } catch (_: SecurityException) { false }
                if (!subscribed) { finish("Could not subscribe to the control point"); return }
                val cccd = cp.getDescriptor(CLIENT_CONFIG)
                if (cccd == null) { finish("Control point has no client configuration descriptor"); return }
                if (!writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    finish("Could not enable indications")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CLIENT_CONFIG) return
                if (status != BluetoothGatt.GATT_SUCCESS) { finish("Indication enable rejected (status $status)"); return }
                sendNext()
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid != CONTROL_POINT) return
                if (status == BluetoothGatt.GATT_SUCCESS) return
                // A rejected opcode (unsupported on this firmware) is skipped, not fatal:
                // the next query may well be answered.
                synchronized(lock) { pending = null }
                sendNext()
            }

            // Android 13+ delivers the value directly; the older overload still fires
            // on 12, where the value has to be read off the characteristic.
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == CONTROL_POINT) onIndication(value)
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (characteristic.uuid == CONTROL_POINT) onIndication(characteristic.value ?: ByteArray(0))
            }
        }

        trySend(result)
        gattRef = try {
            device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            finish("Connect denied: ${e.message}")
            null
        }
        if (gattRef == null && !finished) finish("Bluetooth stack refused the connection")

        val budget = launch {
            delay(TIMEOUT_MS)
            finish(if (result.answered) null else "No answer within ${TIMEOUT_MS / 1000} s")
        }

        awaitClose {
            budget.cancel()
            val gatt = gattRef ?: return@awaitClose
            // Disconnect first, close a moment later: closing straight away tears the
            // client down before the stack has sent the disconnect, and some trackers
            // then hold the link open until their own supervision timeout.
            try { gatt.disconnect() } catch (_: SecurityException) {}
            Handler(Looper.getMainLooper()).postDelayed({
                try { gatt.close() } catch (_: SecurityException) {} catch (_: IllegalStateException) {}
            }, CLOSE_DELAY_MS)
        }
    }

    // ── Wire format ──────────────────────────────────────────────────────────

    /** DULT frames begin with a little-endian 16-bit opcode. */
    private fun encode(op: Int): ByteArray = byteArrayOf((op and 0xFF).toByte(), ((op shr 8) and 0xFF).toByte())

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    /**
     * Strips the response header when one is present. A Get_* answer leads with the
     * request opcode plus the response flag; a control command answers with
     * Command_Response, which echoes the opcode and adds a status word. Firmware that
     * sends the bare operand is accepted as it is — one command is outstanding at a
     * time, so there is no ambiguity about what the bytes answer.
     */
    private fun operand(op: Int, value: ByteArray): ByteArray {
        if (value.size < 2) return value
        val head = le16(value, 0)
        if (head == (op or RESPONSE_FLAG) || head == op) return value.copyOfRange(2, value.size)
        if (head == OP_COMMAND_RESPONSE) {
            // Command_Response: [0x0302][echoed opcode][status]
            return if (value.size >= 4) value.copyOfRange(4, value.size) else ByteArray(0)
        }
        return value
    }

    private fun parse(op: Int, value: ByteArray, current: DultResult): DultResult {
        val body = operand(op, value)
        return when (op) {
            OP_GET_MANUFACTURER_NAME -> current.copy(manufacturer = utf8(body) ?: current.manufacturer)
            OP_GET_MODEL_NAME -> current.copy(model = utf8(body) ?: current.model)
            OP_GET_ACCESSORY_CATEGORY ->
                current.copy(category = categoryName(body.firstOrNull()?.toInt()?.and(0xFF)) ?: current.category)
            OP_GET_PRODUCT_DATA -> current.copy(productData = hex(body).takeIf { it.isNotEmpty() } ?: current.productData)
            OP_GET_IDENTIFIER -> current.copy(serialHash = if (body.isEmpty()) current.serialHash else serialHash(body))
            OP_SOUND_START -> current.copy(soundStarted = statusOk(body))
            OP_SOUND_STOP -> current.copy(soundStarted = false)
            else -> current
        }
    }

    /** Command status word, little-endian, zero meaning success; an empty acknowledgement counts as success. */
    private fun statusOk(body: ByteArray): Boolean =
        body.size < 2 || le16(body, body.size - 2) == 0

    private fun utf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, StandardCharsets.UTF_8)
        return BleNames.cleanName(text) ?: text.trim().takeIf { it.isNotEmpty() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** First eight bytes of SHA-256 over the identifier, as hex — recognisable, not reversible. */
    private fun serialHash(identifier: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identifier)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    // ── API-level shims ──────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = value
            gatt.writeCharacteristic(ch)
        }
    } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) { false }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) { false }
}
