package app.clairix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed class SdrState {
    object Closed : SdrState()
    object Opening : SdrState()
    data class Ready(val freqHz: Long, val sampleRateHz: Int) : SdrState()
    data class Error(val msg: String) : SdrState()
}

class SdrUsbDriver(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<SdrState>(SdrState.Closed)
    val state: StateFlow<SdrState> = _state.asStateFlow()

    private val ioLock = Any()

    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var bulkIn: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null

    private val tunerShadow = IntArray(TUNER_REG_COUNT)
    private var filterCalCode = 0
    private var pllLocked = false

    suspend fun open(freqHz: Long = 1_090_000_000L, sampleRateHz: Int = 2_048_000): Boolean {
        close()
        _state.value = SdrState.Opening

        if (!isValidSampleRate(sampleRateHz)) {
            _state.value = SdrState.Error("Unsupported sample rate $sampleRateHz Hz")
            return false
        }
        if (!isTunable(freqHz)) {
            _state.value = SdrState.Error("Frequency $freqHz Hz outside R820T range")
            return false
        }

        val device = findDevice()
        if (device == null) {
            _state.value = SdrState.Error("No RTL2832U dongle attached")
            return false
        }

        val granted = usbManager.hasPermission(device) || requestPermission(device)
        if (!granted) {
            _state.value = SdrState.Error("USB permission denied")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                synchronized(ioLock) {
                    connect(device)
                    initBaseband()
                    initTuner()
                    applySampleRate(sampleRateHz)
                    tune(freqHz)
                }
                _state.value = SdrState.Ready(freqHz, sampleRateHz)
                true
            } catch (e: IOException) {
                synchronized(ioLock) { teardown() }
                _state.value = SdrState.Error(e.message ?: "USB I/O failure")
                false
            }
        }
    }

    fun close() {
        synchronized(ioLock) {
            if (connection != null) {
                try {
                    setI2cRepeater(true)
                    r82xxStandby()
                    setI2cRepeater(false)
                    writeReg(BLOCK_SYS, SYS_DEMOD_CTL, 0x20, 1)
                } catch (e: IOException) {
                    pllLocked = false
                }
            }
            teardown()
        }
        _state.value = SdrState.Closed
    }

    /**
     * Retunes an open dongle. Returns false when [hz] is out of range or the PLL did not
     * lock there; the device stays Ready (on its previous frequency) either way, so one bad
     * channel does not stop a scanner from moving on to the next.
     */
    suspend fun setFrequency(hz: Long): Boolean {
        if (_state.value !is SdrState.Ready || !isTunable(hz)) return false
        return withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                val conn = connection ?: return@synchronized false
                val ready = _state.value as? SdrState.Ready ?: return@synchronized false
                try {
                    tune(hz)
                    // Only report the new frequency if nobody closed or reopened the dongle meanwhile.
                    if (connection === conn) _state.value = SdrState.Ready(hz, ready.sampleRateHz)
                    true
                } catch (e: IOException) {
                    false
                }
            }
        }
    }

    fun sampleFlow(): Flow<ByteArray> = channelFlow {
        val conn: UsbDeviceConnection
        val ep: UsbEndpoint
        synchronized(ioLock) {
            conn = connection ?: throw IllegalStateException("SDR not open")
            ep = bulkIn ?: throw IllegalStateException("SDR bulk endpoint missing")
            resetBuffer()
        }
        val buf = ByteArray(BULK_BUFFER_BYTES)
        var failures = 0
        while (isActive && connection === conn) {
            val n = conn.bulkTransfer(ep, buf, buf.size, BULK_TIMEOUT_MS)
            if (n > 0) {
                failures = 0
                send(buf.copyOf(n))
            } else {
                failures++
                if (failures >= MAX_BULK_FAILURES) {
                    if (connection === conn) _state.value = SdrState.Error("USB sample stream stalled")
                    break
                }
            }
        }
    }.buffer(SAMPLE_QUEUE_DEPTH, BufferOverflow.DROP_OLDEST).flowOn(Dispatchers.IO)

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { dev ->
            KNOWN_DONGLES.any { it.first == dev.vendorId && it.second == dev.productId }
        }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    if (done.compareAndSet(false, true)) {
                        runCatching { appContext.unregisterReceiver(this) }
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            // FLAG_MUTABLE is required: the system fills in EXTRA_PERMISSION_GRANTED on our intent.
            val pending = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pending)
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { appContext.unregisterReceiver(receiver) }
            }
        }

    private fun connect(device: UsbDevice) {
        if (device.interfaceCount == 0) throw IOException("Dongle exposes no USB interfaces")
        val intf = device.getInterface(0)
        val conn = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("Unable to claim USB interface 0")
        }
        var ep: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val candidate = intf.getEndpoint(i)
            if (candidate.address == BULK_IN_ADDRESS) {
                ep = candidate
                break
            }
            if (ep == null && candidate.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                candidate.direction == UsbConstants.USB_DIR_IN
            ) {
                ep = candidate
            }
        }
        if (ep == null) {
            conn.releaseInterface(intf)
            conn.close()
            throw IOException("Bulk IN endpoint 0x81 not found")
        }
        connection = conn
        claimedInterface = intf
        bulkIn = ep
    }

    private fun teardown() {
        val conn = connection
        connection = null
        bulkIn = null
        if (conn != null) {
            claimedInterface?.let { runCatching { conn.releaseInterface(it) } }
            conn.close()
        }
        claimedInterface = null
        pllLocked = false
    }

    private fun initBaseband() {
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)

        writeReg(BLOCK_SYS, SYS_DEMOD_CTL_1, 0x22, 1)
        writeReg(BLOCK_SYS, SYS_DEMOD_CTL, 0xE8, 1)

        demodWrite(1, 0x01, 0x14, 1)
        demodWrite(1, 0x01, 0x10, 1)

        demodWrite(1, 0x15, 0x00, 1)
        demodWrite(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWrite(1, 0x16 + i, 0x00, 1)

        setFir()

        demodWrite(0, 0x19, 0x05, 1)
        demodWrite(1, 0x93, 0xF0, 1)
        demodWrite(1, 0x94, 0x0F, 1)
        demodWrite(1, 0x11, 0x00, 1)
        demodWrite(1, 0x04, 0x00, 1)
        demodWrite(0, 0x61, 0x60, 1)
        demodWrite(0, 0x06, 0x80, 1)
        demodWrite(1, 0xB1, 0x1B, 1)
        demodWrite(0, 0x0D, 0x83, 1)
    }

    private fun setFir() {
        val fir = IntArray(20)
        for (i in 0 until 8) fir[i] = FIR_DEFAULT[i] and 0xFF
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]
            val v1 = FIR_DEFAULT[8 + i + 1]
            val k = 8 + i * 3 / 2
            fir[k] = (v0 shr 4) and 0xFF
            fir[k + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0F)) and 0xFF
            fir[k + 2] = v1 and 0xFF
            i += 2
        }
        for (j in fir.indices) demodWrite(1, 0x1C + j, fir[j], 1)
    }

    private fun initTuner() {
        setI2cRepeater(true)
        val id = i2cReadReg(R820T_I2C_ADDR, R82XX_CHECK_ADDR)
        if (id != R82XX_CHECK_VAL) {
            setI2cRepeater(false)
            throw IOException("Unsupported tuner (expected R820T/R820T2)")
        }

        demodWrite(1, 0xB1, 0x1A, 1)
        demodWrite(0, 0x08, 0x4D, 1)
        setIfFreq(R82XX_IF_HZ)
        demodWrite(1, 0x15, 0x01, 1)

        r82xxInit()
        r82xxSetMaxGain()
        setI2cRepeater(false)
    }

    private fun applySampleRate(rate: Int) {
        if (!isValidSampleRate(rate)) throw IOException("Unsupported sample rate $rate Hz")
        val ratio = ((RTL_XTAL_HZ shl 22) / rate) and 0x0FFFFFFCL
        demodWrite(1, 0x9F, ((ratio shr 16) and 0xFFFF).toInt(), 2)
        demodWrite(1, 0xA1, (ratio and 0xFFFF).toInt(), 2)
        demodWrite(1, 0x3F, 0x00, 1)
        demodWrite(1, 0x3E, 0x00, 1)
        demodWrite(1, 0x01, 0x14, 1)
        demodWrite(1, 0x01, 0x10, 1)
    }

    private fun setIfFreq(freqHz: Long) {
        val ifFreq = -((freqHz shl 22) / RTL_XTAL_HZ).toInt()
        demodWrite(1, 0x19, (ifFreq shr 16) and 0x3F, 1)
        demodWrite(1, 0x1A, (ifFreq shr 8) and 0xFF, 1)
        demodWrite(1, 0x1B, ifFreq and 0xFF, 1)
    }

    private fun resetBuffer() {
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
    }

    private fun tune(freqHz: Long) {
        setI2cRepeater(true)
        try {
            val lo = freqHz + R82XX_IF_HZ
            r82xxSetMux(lo)
            r82xxSetPll(lo)
            if (!pllLocked) throw IOException("R820T PLL failed to lock at $freqHz Hz")
            // librtlsdr switches reg 0x05 between Air-In and Cable1 at 345 MHz only on the
            // R828D. R820T/R820T2 sticks wire the antenna to Air-In, so the input stays put.
        } finally {
            setI2cRepeater(false)
        }
    }

    private fun r82xxInit() {
        tunerShadow.fill(0)
        tunerWrite(R82XX_SHADOW_START, R82XX_INIT)
        r82xxSetTvStandard()
        r82xxSysFreqSel()
    }

    private fun r82xxSetTvStandard() {
        val filtCalLoHz = 56_000_000L
        val filtGain = 0x10
        val imgR = 0x00
        val filtQ = 0x10
        val hpCor = 0x6B
        val extEnable = 0x60
        val loopThrough = 0x00
        val ltAtt = 0x00
        val fltExtWidest = 0x00
        val polyfilCur = 0x60

        for (i in R82XX_INIT.indices) tunerShadow[R82XX_SHADOW_START + i] = R82XX_INIT[i]

        tunerWriteMask(0x0C, 0x00, 0x0F)
        tunerWriteMask(0x13, R82XX_VER_NUM, 0x3F)
        tunerWriteMask(0x1D, 0x00, 0x38)

        filterCalCode = 0
        for (attempt in 0 until 2) {
            tunerWriteMask(0x0B, hpCor, 0x60)
            tunerWriteMask(0x0F, 0x04, 0x04)
            tunerWriteMask(0x10, 0x00, 0x03)
            r82xxSetPll(filtCalLoHz)
            if (!pllLocked) throw IOException("R820T calibration PLL failed to lock")
            tunerWriteMask(0x0B, 0x10, 0x10)
            Thread.sleep(1) // librtlsdr: usleep_range(1000, 2000) between start and stop trigger
            tunerWriteMask(0x0B, 0x00, 0x10)
            tunerWriteMask(0x0F, 0x00, 0x04)
            val data = tunerRead(5)
            filterCalCode = data[4] and 0x0F
            if (filterCalCode != 0 && filterCalCode != 0x0F) break
        }
        if (filterCalCode == 0x0F) filterCalCode = 0

        tunerWriteMask(0x0A, filtQ or filterCalCode, 0x1F)
        tunerWriteMask(0x0B, hpCor, 0xEF)
        tunerWriteMask(0x07, imgR, 0x80)
        tunerWriteMask(0x06, filtGain, 0x30)
        tunerWriteMask(0x1E, extEnable, 0x60)
        tunerWriteMask(0x05, loopThrough, 0x80)
        tunerWriteMask(0x1F, ltAtt, 0x80)
        tunerWriteMask(0x0F, fltExtWidest, 0x80)
        tunerWriteMask(0x19, polyfilCur, 0x60)
    }

    private fun r82xxSysFreqSel() {
        val mixerTop = 0x24
        val lnaTop = 0xE5
        val lnaVthL = 0x53
        val mixerVthL = 0x75
        val airCable1In = 0x00
        val cable2In = 0x00
        val lnaDischarge = 14
        val cpCur = 0x38
        val divBufCur = 0x30
        val filterCur = 0x40

        tunerWriteMask(0x1D, lnaTop, 0xC7)
        tunerWriteMask(0x1C, mixerTop, 0xF8)
        tunerWriteReg(0x0D, lnaVthL)
        tunerWriteReg(0x0E, mixerVthL)

        tunerWriteMask(0x05, airCable1In, 0x60)
        tunerWriteMask(0x06, cable2In, 0x08)
        tunerWriteMask(0x11, cpCur, 0x38)
        tunerWriteMask(0x17, divBufCur, 0x30)
        tunerWriteMask(0x0A, filterCur, 0x60)

        tunerWriteMask(0x1D, 0x00, 0x38)
        tunerWriteMask(0x1C, 0x00, 0x04)
        tunerWriteMask(0x06, 0x00, 0x40)
        tunerWriteMask(0x1A, 0x30, 0x30)
        tunerWriteMask(0x1D, 0x18, 0x38)
        tunerWriteMask(0x1C, mixerTop, 0x04)
        tunerWriteMask(0x1E, lnaDischarge, 0x1F)
        tunerWriteMask(0x1A, 0x20, 0x30)
    }

    // Fixed maximum gain: ADS-B bursts are short and the tuner AGC is too slow to track them.
    private fun r82xxSetMaxGain() {
        tunerWriteMask(0x05, 0x10, 0x10)
        tunerWriteMask(0x07, 0x00, 0x10)
        tunerWriteMask(0x0C, 0x08, 0x9F)
        tunerWriteMask(0x05, 0x0F, 0x0F)
        tunerWriteMask(0x07, 0x0F, 0x0F)
    }

    private fun r82xxSetMux(freqHz: Long) {
        val freqMhz = (freqHz / 1_000_000L).toInt()
        var idx = 0
        while (idx < FREQ_RANGES.size - 1 && freqMhz >= FREQ_RANGES[idx + 1][0]) idx++
        val range = FREQ_RANGES[idx]
        tunerWriteMask(0x17, range[1], 0x08)
        tunerWriteMask(0x1A, range[2], 0xC3)
        tunerWriteReg(0x1B, range[3])
        tunerWriteMask(0x10, range[6], 0x0B)
        tunerWriteMask(0x08, 0x00, 0x3F)
        tunerWriteMask(0x09, 0x00, 0x3F)
    }

    private fun r82xxSetPll(freqHz: Long) {
        val vcoMin = 1_770_000L
        val vcoMax = vcoMin * 2
        val freqKhz = (freqHz + 500) / 1000
        val pllRef = R82XX_XTAL_HZ

        tunerWriteMask(0x10, 0x00, 0x10)
        tunerWriteMask(0x1A, 0x00, 0x0C)
        tunerWriteMask(0x12, 0x80, 0xE0)

        var mixDiv = 2
        var divNum = 0
        var found = false
        while (mixDiv <= 64) {
            val vco = freqKhz * mixDiv
            if (vco >= vcoMin && vco < vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) {
                    divBuf = divBuf shr 1
                    divNum++
                }
                found = true
                break
            }
            mixDiv = mixDiv shl 1
        }
        if (!found) throw IOException("No VCO divider for $freqHz Hz")

        val status = tunerRead(5)
        val vcoFineTune = (status[4] and 0x30) shr 4
        if (vcoFineTune > VCO_POWER_REF) divNum-- else if (vcoFineTune < VCO_POWER_REF) divNum++
        tunerWriteMask(0x10, divNum shl 5, 0xE0)

        val vcoFreq = freqHz * mixDiv
        val nint = vcoFreq / (2 * pllRef)
        var vcoFra = (vcoFreq - 2 * pllRef * nint) / 1000
        if (nint > (128 / VCO_POWER_REF) - 1) throw IOException("R820T PLL N out of range for $freqHz Hz")

        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        tunerWriteReg(0x14, (ni + (si shl 6)).toInt() and 0xFF)
        tunerWriteMask(0x12, if (vcoFra == 0L) 0x08 else 0x00, 0x08)

        val pllRefKhz = (pllRef + 500) / 1000
        var nSdm = 2L
        var sdm = 0L
        while (vcoFra > 1 && nSdm <= 0x10000L) {
            val step = 2 * pllRefKhz / nSdm
            if (vcoFra > step) {
                sdm += 32768L / (nSdm / 2)
                vcoFra -= step
                if (nSdm >= 0x8000L) break
            }
            nSdm = nSdm shl 1
        }
        tunerWriteReg(0x16, ((sdm shr 8) and 0xFF).toInt())
        tunerWriteReg(0x15, (sdm and 0xFF).toInt())

        var locked = false
        for (i in 0 until 2) {
            Thread.sleep(PLL_LOCK_WAIT_MS) // librtlsdr r82xx_set_pll: sleep_time = 10000 us
            val data = tunerRead(3)
            if ((data[2] and 0x40) != 0) {
                locked = true
                break
            }
            if (i == 0) tunerWriteMask(0x12, 0x60, 0xE0)
        }
        pllLocked = locked
        if (locked) tunerWriteMask(0x1A, 0x08, 0x08)
    }

    private fun r82xxStandby() {
        tunerWriteReg(0x06, 0xB1)
        tunerWriteReg(0x05, 0x03)
        tunerWriteReg(0x07, 0x3A)
        tunerWriteReg(0x08, 0x40)
        tunerWriteReg(0x09, 0xC0)
        tunerWriteReg(0x0A, 0x36)
        tunerWriteReg(0x0C, 0x35)
        tunerWriteReg(0x0F, 0x68)
        tunerWriteReg(0x11, 0x03)
        tunerWriteReg(0x17, 0xF4)
        tunerWriteReg(0x19, 0x0C)
    }

    private fun tunerWrite(startReg: Int, values: IntArray) {
        var pos = 0
        while (pos < values.size) {
            val chunk = minOf(I2C_MAX_PAYLOAD, values.size - pos)
            val buf = ByteArray(chunk + 1)
            buf[0] = (startReg + pos).toByte()
            for (k in 0 until chunk) {
                val v = values[pos + k] and 0xFF
                buf[k + 1] = v.toByte()
                tunerShadow[startReg + pos + k] = v
            }
            i2cWrite(R820T_I2C_ADDR, buf)
            pos += chunk
        }
    }

    private fun tunerWriteReg(reg: Int, value: Int) = tunerWrite(reg, intArrayOf(value and 0xFF))

    private fun tunerWriteMask(reg: Int, value: Int, mask: Int) {
        val merged = (tunerShadow[reg] and mask.inv()) or (value and mask)
        tunerWriteReg(reg, merged and 0xFF)
    }

    // R820T always returns registers starting at 0x00, LSB-first, so each byte is bit-reversed.
    private fun tunerRead(len: Int): IntArray {
        i2cWrite(R820T_I2C_ADDR, byteArrayOf(0x00))
        val raw = i2cRead(R820T_I2C_ADDR, len)
        return IntArray(len) { (Integer.reverse(raw[it].toInt() and 0xFF) ushr 24) and 0xFF }
    }

    private fun setI2cRepeater(on: Boolean) = demodWrite(1, 0x01, if (on) 0x18 else 0x10, 1)

    private fun i2cReadReg(addr: Int, reg: Int): Int {
        i2cWrite(addr, byteArrayOf(reg.toByte()))
        return i2cRead(addr, 1)[0].toInt() and 0xFF
    }

    private fun i2cWrite(addr: Int, data: ByteArray) =
        controlOut(addr, (BLOCK_IIC shl 8) or 0x10, data)

    private fun i2cRead(addr: Int, len: Int): ByteArray =
        controlIn(addr, BLOCK_IIC shl 8, len)

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int) =
        controlOut(addr, (block shl 8) or 0x10, encode(value, len))

    private fun demodWrite(page: Int, addr: Int, value: Int, len: Int) {
        controlOut((addr shl 8) or 0x20, 0x10 or page, encode(value, len))
        demodRead(0x0A, 0x01, 1)
    }

    private fun demodRead(page: Int, addr: Int, len: Int): Int {
        val data = controlIn((addr shl 8) or 0x20, page, len)
        return if (len == 1) data[0].toInt() and 0xFF
        else ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    }

    private fun encode(value: Int, len: Int): ByteArray =
        if (len == 1) byteArrayOf((value and 0xFF).toByte())
        else byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun controlOut(value: Int, index: Int, data: ByteArray) {
        val conn = connection ?: throw IOException("SDR not open")
        val r = conn.controlTransfer(CTRL_OUT, 0, value, index, data, data.size, CTRL_TIMEOUT_MS)
        if (r < 0) throw IOException("Control write failed (value=0x%04x index=0x%04x)".format(value, index))
    }

    private fun controlIn(value: Int, index: Int, len: Int): ByteArray {
        val conn = connection ?: throw IOException("SDR not open")
        val buf = ByteArray(len)
        val r = conn.controlTransfer(CTRL_IN, 0, value, index, buf, len, CTRL_TIMEOUT_MS)
        if (r < 0) throw IOException("Control read failed (value=0x%04x index=0x%04x)".format(value, index))
        return buf
    }

    private fun isValidSampleRate(rate: Int): Boolean =
        rate in 225_001..300_000 || rate in 900_001..3_200_000

    companion object {
        /** R820T tuning range used by this driver (librtlsdr reports 24..1766 MHz). */
        const val MIN_FREQ_HZ = 24_000_000L
        const val MAX_FREQ_HZ = 1_766_000_000L

        fun isTunable(hz: Long): Boolean = hz in MIN_FREQ_HZ..MAX_FREQ_HZ

        /** USB vendor/product pairs this driver opens; res/xml/usb_device_filter.xml must match. */
        val KNOWN_DONGLES = listOf(
            0x0BDA to 0x2832, 0x0BDA to 0x2838,
            0x0413 to 0x6680, 0x0413 to 0x6F0F,
            0x0458 to 0x707F,
            0x0CCD to 0x00A9, 0x0CCD to 0x00B3, 0x0CCD to 0x00B4, 0x0CCD to 0x00B5,
            0x0CCD to 0x00B7, 0x0CCD to 0x00B8, 0x0CCD to 0x00B9, 0x0CCD to 0x00C0,
            0x0CCD to 0x00C6, 0x0CCD to 0x00D3, 0x0CCD to 0x00D7, 0x0CCD to 0x00E0,
            0x1554 to 0x5020,
            0x15F4 to 0x0131, 0x15F4 to 0x0133,
            0x185B to 0x0620, 0x185B to 0x0650, 0x185B to 0x0680,
            0x1B80 to 0xD393, 0x1B80 to 0xD394, 0x1B80 to 0xD395, 0x1B80 to 0xD397,
            0x1B80 to 0xD398, 0x1B80 to 0xD39D, 0x1B80 to 0xD3A4, 0x1B80 to 0xD3A8,
            0x1B80 to 0xD3AF, 0x1B80 to 0xD3B0,
            0x1D19 to 0x1101, 0x1D19 to 0x1102, 0x1D19 to 0x1103, 0x1D19 to 0x1104,
            0x1F4D to 0xA803, 0x1F4D to 0xB803, 0x1F4D to 0xC803, 0x1F4D to 0xD286, 0x1F4D to 0xD803
        )

        private const val PLL_LOCK_WAIT_MS = 10L
        private const val ACTION_USB_PERMISSION = "app.clairix.SDR_USB_PERMISSION"

        private const val CTRL_OUT = 0x40
        private const val CTRL_IN = 0xC0
        private const val CTRL_TIMEOUT_MS = 300

        private const val BULK_IN_ADDRESS = 0x81
        private const val BULK_BUFFER_BYTES = 131072
        private const val BULK_TIMEOUT_MS = 1000
        private const val MAX_BULK_FAILURES = 10
        private const val SAMPLE_QUEUE_DEPTH = 16

        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2
        private const val BLOCK_IIC = 6

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val SYS_DEMOD_CTL = 0x3000
        private const val SYS_DEMOD_CTL_1 = 0x300B

        private const val RTL_XTAL_HZ = 28_800_000L
        private const val R82XX_XTAL_HZ = 28_800_000L
        private const val R82XX_IF_HZ = 3_570_000L
        private const val R820T_I2C_ADDR = 0x34
        private const val R82XX_CHECK_ADDR = 0x00
        private const val R82XX_CHECK_VAL = 0x69
        private const val R82XX_VER_NUM = 49
        private const val R82XX_SHADOW_START = 0x05
        private const val TUNER_REG_COUNT = 0x20
        private const val VCO_POWER_REF = 2
        private const val I2C_MAX_PAYLOAD = 7

        private val FIR_DEFAULT = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,
            101, 156, 215, 273, 327, 372, 404, 421
        )

        private val R82XX_INIT = intArrayOf(
            0x83, 0x32, 0x75,
            0xC0, 0x40, 0xD6, 0x6C,
            0xF5, 0x63, 0x75, 0x68,
            0x6C, 0x83, 0x80, 0x00,
            0x0F, 0x00, 0xC0, 0x30,
            0x48, 0xCC, 0x60, 0x00,
            0x54, 0xAE, 0x4A, 0xC0
        )

        private val FREQ_RANGES = arrayOf(
            intArrayOf(0, 0x08, 0x02, 0xDF, 0x02, 0x01, 0x00),
            intArrayOf(50, 0x08, 0x02, 0xBE, 0x02, 0x01, 0x00),
            intArrayOf(55, 0x08, 0x02, 0x8B, 0x02, 0x01, 0x00),
            intArrayOf(60, 0x08, 0x02, 0x7B, 0x02, 0x01, 0x00),
            intArrayOf(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00),
            intArrayOf(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            intArrayOf(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00),
            intArrayOf(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            intArrayOf(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
        )
    }
}
