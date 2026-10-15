package com.xat.aegis

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

data class ObdData(
    val speedKph: Int? = null,
    val rpm: Int? = null,
    val coolantTempC: Int? = null,
    val intakePressureKpa: Int? = null,
    val throttlePct: Int? = null,
    val dtcs: List<String> = emptyList(),
    val connected: Boolean = false,
    val deviceName: String? = null
)

class ObdBluetoothManager(private val context: Context) {

    private val _data = MutableStateFlow(ObdData())
    val data: StateFlow<ObdData> = _data.asStateFlow()

    private var scope = newScope()
    private var pollJob: Job? = null

    private val ioMutex = Mutex()
    private val connectMutex = Mutex()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var isCan: Boolean? = null

    private class Reply(val text: String, val prompted: Boolean)

    private class Frame(val header: String, val bytes: List<Int>)

    suspend fun connect(): Boolean = connectMutex.withLock {
        if (!scope.isActive) scope = newScope()
        pollJob?.cancel()
        pollJob = null
        closeLink()
        val ok = establish()
        if (ok) startPolling()
        ok
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        closeLink()
        _data.value = ObdData()
    }

    suspend fun sendCommand(cmd: String): String = exchange(cmd, COMMAND_TIMEOUT_MS).text

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollLoop()
                } catch (_: IOException) {
                    closeLink()
                    _data.update {
                        it.copy(
                            speedKph = null, rpm = null, coolantTempC = null,
                            intakePressureKpa = null, throttlePct = null, connected = false
                        )
                    }
                }
                var recovered = false
                var attempts = 0
                while (isActive && attempts < MAX_RECONNECT_ATTEMPTS) {
                    attempts++
                    delay(RECONNECT_DELAY_MS)
                    if (establish()) {
                        recovered = true
                        break
                    }
                }
                if (!recovered) {
                    closeLink()
                    _data.update { it.copy(connected = false) }
                    break
                }
            }
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val speed = queryPid("010D", 0x0D)?.let { it[0] }
            val rpm = queryPid("010C", 0x0C)?.takeIf { it.size >= 2 }?.let { (256 * it[0] + it[1]) / 4 }
            val coolant = queryPid("0105", 0x05)?.let { it[0] - 40 }
            val map = queryPid("010B", 0x0B)?.let { it[0] }
            val throttle = queryPid("0111", 0x11)?.let { (it[0] * 100) / 255 }
            _data.update {
                it.copy(
                    speedKph = speed,
                    rpm = rpm,
                    coolantTempC = coolant,
                    intakePressureKpa = map,
                    throttlePct = throttle,
                    connected = true
                )
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun establish(): Boolean = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) return@withContext false
        val adapter = bluetoothAdapter() ?: return@withContext false
        try {
            if (!adapter.isEnabled) return@withContext false
            val device = findElmDevice(adapter) ?: return@withContext false
            val opened = openSocket(adapter, device) ?: return@withContext false
            socket = opened
            input = opened.inputStream
            output = opened.outputStream
            val name = runCatching { device.name }.getOrNull()
            val initialized = try {
                initialize()
            } catch (_: IOException) {
                false
            }
            if (!initialized) {
                closeLink()
                return@withContext false
            }
            _data.value = ObdData(connected = true, deviceName = name)
            val dtcs = try {
                readDtcs()
            } catch (_: IOException) {
                closeLink()
                _data.value = ObdData()
                return@withContext false
            }
            _data.update { it.copy(dtcs = dtcs) }
            true
        } catch (_: SecurityException) {
            closeLink()
            false
        }
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun findElmDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices ?: return null
        return bonded.firstOrNull { device ->
            val name = runCatching { device.name }.getOrNull()?.uppercase(Locale.US) ?: return@firstOrNull false
            DEVICE_KEYWORDS.any { name.contains(it) }
        }
    }

    private fun openSocket(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket? {
        // Discovery saturates the radio and makes RFCOMM connects time out on most chipsets.
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val primary = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (_: IOException) {
            null
        }
        if (primary != null) {
            pendingSocket = primary
            try {
                primary.connect()
                pendingSocket = null
                return primary
            } catch (_: IOException) {
                runCatching { primary.close() }
            }
        }
        // Many cheap ELM327 clones advertise a broken SDP record; channel 1 is where they actually listen.
        val fallback = try {
            BluetoothDevice::class.java.getMethod("createRfcommSocket", Int::class.java)
                .invoke(device, 1) as? BluetoothSocket
        } catch (_: Exception) {
            null
        } ?: run {
            pendingSocket = null
            return null
        }
        pendingSocket = fallback
        return try {
            fallback.connect()
            fallback
        } catch (_: IOException) {
            runCatching { fallback.close() }
            null
        } finally {
            pendingSocket = null
        }
    }

    private suspend fun initialize(): Boolean {
        isCan = null
        val reset = exchange("ATZ", RESET_TIMEOUT_MS)
        // Some clones reboot their UART on ATZ and swallow the first prompt; give them a moment before ATE0.
        if (!reset.prompted) delay(RESET_SETTLE_MS)
        for (cmd in listOf("ATE0", "ATL0", "ATSP0", "ATH1")) {
            val reply = exchange(cmd, COMMAND_TIMEOUT_MS)
            if (!reply.prompted || reply.text.trim() == "?") return false
        }
        // ATSP0 defers protocol search until the first OBD request, which can take several seconds.
        exchange("0100", SEARCH_TIMEOUT_MS)
        isCan = detectCan()
        return true
    }

    private suspend fun detectCan(): Boolean? {
        val raw = exchange("ATDPN", COMMAND_TIMEOUT_MS).text
        val token = raw.lines().map { it.trim().uppercase(Locale.US) }
            .firstOrNull { it.isNotEmpty() && it != "ATDPN" } ?: return null
        val code = token.removePrefix("A").firstOrNull()?.digitToIntOrNull(16) ?: return null
        if (code == 0) return null
        return code in 6..12
    }

    private suspend fun queryPid(cmd: String, pid: Int): List<Int>? {
        val lines = cleanLines(exchange(cmd, COMMAND_TIMEOUT_MS).text, cmd) ?: return null
        return payloads(lines)
            .firstOrNull { it.size >= 3 && it[0] == 0x41 && it[1] == pid }
            ?.drop(2)
    }

    private suspend fun readDtcs(): List<String> {
        val lines = cleanLines(exchange("03", DTC_TIMEOUT_MS).text, "03") ?: return emptyList()
        val can = isCan == true
        val codes = mutableListOf<String>()
        for (payload in payloads(lines)) {
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

    private fun decodeDtc(a: Int, b: Int): String {
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

    private fun tokenize(line: String): List<String>? {
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

    private fun parseFrame(line: String): Frame? {
        val tokens = tokenize(line) ?: return null
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

    private fun payloads(lines: List<String>): List<List<Int>> {
        val frames = lines.mapNotNull { parseFrame(it) }
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

    private suspend fun exchange(cmd: String, timeoutMs: Long): Reply = ioMutex.withLock {
        withContext(Dispatchers.IO) {
            val inStream = input ?: throw IOException("OBD link not open")
            val outStream = output ?: throw IOException("OBD link not open")
            val stale = inStream.available()
            if (stale > 0) inStream.skip(stale.toLong())
            outStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            outStream.flush()
            val text = StringBuilder()
            val buf = ByteArray(256)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var prompted = false
            while (!prompted) {
                val available = inStream.available()
                if (available > 0) {
                    val n = inStream.read(buf, 0, minOf(available, buf.size))
                    if (n < 0) throw IOException("OBD stream closed")
                    for (i in 0 until n) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        if (c == '>') {
                            prompted = true
                            break
                        }
                        if (c != '\u0000') text.append(c)
                    }
                } else {
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    delay(READ_POLL_MS)
                }
            }
            Reply(text.toString(), prompted)
        }
    }

    private fun closeLink() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        runCatching { pendingSocket?.close() }
        input = null
        output = null
        socket = null
        pendingSocket = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val DEVICE_KEYWORDS = listOf("OBD", "ELM", "V-LINK", "OBDII")
        val ERROR_MARKERS = listOf("NO DATA", "UNABLE TO CONNECT", "ERROR", "STOPPED")
        val DTC_SYSTEMS = charArrayOf('P', 'C', 'B', 'U')
        const val HEX_CHARS = "0123456789ABCDEF"
        const val COMMAND_TIMEOUT_MS = 1_000L
        const val RESET_TIMEOUT_MS = 2_000L
        const val RESET_SETTLE_MS = 500L
        const val SEARCH_TIMEOUT_MS = 6_000L
        const val DTC_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 150L
        const val READ_POLL_MS = 10L
        const val RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
