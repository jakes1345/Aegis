package app.clairix

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
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
    /** Bumped by connect() and disconnect(); a link set up under an older value is dropped. */
    @Volatile private var generation = 0

    private class Reply(val text: String, val prompted: Boolean)


    suspend fun connect(): Boolean = connectMutex.withLock {
        val gen = ++generation
        if (!scope.isActive) scope = newScope()
        val old = pollJob
        pollJob = null
        old?.cancel()
        closeLink()
        // A reconnect attempt of the old poll loop may still be inside establish(); it sees the
        // new generation and gives up, but wait so it cannot close the link set up below.
        old?.join()
        val ok = establish(gen)
        if (ok && gen == generation) startPolling(gen)
        ok
    }

    fun disconnect() {
        generation++
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        closeLink()
        _data.value = ObdData()
    }

    suspend fun sendCommand(cmd: String): String = exchange(cmd, COMMAND_TIMEOUT_MS).text

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startPolling(gen: Int) {
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
                    if (establish(gen)) {
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

    /**
     * Opens and initialises the link. The blocking RFCOMM connect is not interrupted by
     * coroutine cancellation, so after each blocking step it re-checks that this attempt is
     * still wanted (same [gen], coroutine active); if not, it closes what it opened and
     * publishes nothing.
     */
    private suspend fun establish(gen: Int): Boolean = withContext(Dispatchers.IO) {
        fun stale() = gen != generation || !isActive
        if (!hasConnectPermission()) return@withContext false
        val adapter = bluetoothAdapter() ?: return@withContext false
        var opened: BluetoothSocket? = null
        fun abandon(): Boolean {
            if (socket === opened) closeLink() else runCatching { opened?.close() }
            return false
        }
        try {
            if (!adapter.isEnabled) return@withContext false
            val device = findElmDevice(adapter) ?: return@withContext false
            opened = openSocket(adapter, device, ::stale) ?: return@withContext false
            if (stale()) return@withContext abandon()
            socket = opened
            input = opened.inputStream
            output = opened.outputStream
            val name = runCatching { device.name }.getOrNull()
            val initialized = initialize()
            if (!initialized || stale()) return@withContext abandon()
            val dtcs = readDtcs()
            if (stale()) return@withContext abandon()
            _data.value = ObdData(connected = true, deviceName = name, dtcs = dtcs)
            true
        } catch (e: CancellationException) {
            abandon()
            throw e
        } catch (_: IOException) {
            abandon()
        } catch (_: SecurityException) {
            abandon()
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

    private fun openSocket(adapter: BluetoothAdapter, device: BluetoothDevice, stale: () -> Boolean): BluetoothSocket? {
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
        // A disconnect() during the first connect closes it; do not then dial channel 1.
        if (stale()) {
            pendingSocket = null
            return null
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

    private suspend fun queryPid(cmd: String, pid: Int): List<Int>? =
        ObdParser.pidData(exchange(cmd, COMMAND_TIMEOUT_MS).text, cmd, pid, isCan)

    private suspend fun readDtcs(): List<String> =
        ObdParser.dtcs(exchange("03", DTC_TIMEOUT_MS).text, isCan)

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
