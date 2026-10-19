package app.clairix

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drive mode: one RTL-SDR dongle plus an ELM327 OBD-II adapter.
 *
 * A single dongle can only be tuned to one place, so the SDR runs in one of two modes:
 * the voice scanner while at least one scanner channel is enabled, ADS-B on 1090 MHz
 * otherwise. Toggling the last channel on or off switches the mode live.
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {

    private val sdr = SdrUsbDriver(app)
    private val adsb = AdsbDecoder()
    private val scanner = ScannerMonitor(app, sdr)
    private val obd = ObdBluetoothManager(app)

    val sdrState: StateFlow<SdrState> = sdr.state
    val aircraft: StateFlow<Map<String, Aircraft>> = adsb.aircraft
    val obdData: StateFlow<ObdData> = obd.data
    val scannerChannels: StateFlow<List<ScannerChannel>> = scanner.channels
    val activeChannel: StateFlow<ScannerChannel?> = scanner.activeChannel
    val squelchOpen: StateFlow<Boolean> = scanner.squelchOpen
    val isRecording: StateFlow<Boolean> = scanner.isRecording
    val lastRecordingPath: StateFlow<String?> = scanner.lastRecordingPath

    private val _squelchLevel = MutableStateFlow(DEFAULT_SQUELCH)
    val squelchLevel: StateFlow<Float> = _squelchLevel.asStateFlow()

    private val _driveModeActive = MutableStateFlow(false)
    val driveModeActive: StateFlow<Boolean> = _driveModeActive.asStateFlow()

    private var sdrJob: Job? = null
    private var obdJob: Job? = null
    private var playbackJob: Job? = null

    init {
        scanner.setSquelch(DEFAULT_SQUELCH)
        viewModelScope.launch { scanner.loadSaved() }
    }

    fun activateDriveMode() {
        if (_driveModeActive.value) return
        _driveModeActive.value = true
        DriveService.start(getApplication())
        obdJob = viewModelScope.launch { obd.connect() }
        sdrJob = viewModelScope.launch {
            scanner.channels
                .map { list -> list.any { it.active && SdrUsbDriver.isTunable(it.freqHz) } }
                .distinctUntilChanged()
                .collectLatest { scanMode -> runSdr(scanMode) }
        }
    }

    fun deactivateDriveMode() {
        if (!_driveModeActive.value) return
        _driveModeActive.value = false
        sdrJob?.cancel()
        sdrJob = null
        obdJob?.cancel()
        obdJob = null
        // Cancelling sdrJob runs runSdr's finally, but do it here too so the dongle is
        // released even if the job had already finished on an open failure.
        scanner.stop()
        adsb.stop()
        sdr.close()
        obd.disconnect()
        DriveService.stop(getApplication())
    }

    fun toggleDriveMode() {
        if (_driveModeActive.value) deactivateDriveMode() else activateDriveMode()
    }

    fun setSquelch(level: Float) {
        val l = level.coerceIn(0f, 1f)
        _squelchLevel.value = l
        scanner.setSquelch(l)
    }

    fun addChannel(name: String, freqHz: Long) = scanner.addChannel(name, freqHz)

    fun removeChannel(freqHz: Long) = scanner.removeChannel(freqHz)

    fun setChannelActive(freqHz: Long, active: Boolean) = scanner.setChannelActive(freqHz, active)

    fun connectObd() {
        obdJob?.cancel()
        obdJob = viewModelScope.launch { obd.connect() }
    }

    fun disconnectObd() {
        obdJob?.cancel()
        obdJob = null
        obd.disconnect()
    }

    /** Recordings are raw 16-bit mono PCM at 48 kHz, which no stock player opens, so play them here. */
    fun openRecording(path: String) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.isFile || file.length() < 2) return@withContext
                val bytes = file.readBytes()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(FmDemodulator.OUTPUT_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(
                        AudioTrack.getMinBufferSize(
                            FmDemodulator.OUTPUT_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ) * 4
                    )
                    .build()
                try {
                    track.play()
                    var pos = 0
                    val len = bytes.size and 1.inv()
                    while (pos < len && isActive) {
                        val n = track.write(bytes, pos, minOf(PLAYBACK_CHUNK, len - pos))
                        if (n <= 0) break
                        pos += n
                    }
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                }
            }
        }
    }

    private suspend fun runSdr(scanMode: Boolean) {
        try {
            if (scanMode) {
                val first = scanner.channels.value
                    .firstOrNull { it.active && SdrUsbDriver.isTunable(it.freqHz) } ?: return
                if (!sdr.open(first.freqHz, FmDemodulator.INPUT_RATE)) return
                scanner.start()
                awaitCancellation()
            } else {
                if (!sdr.open(ADSB_FREQ_HZ, ADSB_SAMPLE_RATE)) return
                adsb.start(sdr)
            }
        } finally {
            scanner.stop()
            adsb.stop()
            sdr.close()
        }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        deactivateDriveMode()
        super.onCleared()
    }

    private companion object {
        const val ADSB_FREQ_HZ = 1_090_000_000L
        // AdsbDecoder's preamble and bit slicer assume exactly 2 samples per microsecond.
        const val ADSB_SAMPLE_RATE = 2_000_000
        const val DEFAULT_SQUELCH = 0.3f
        const val PLAYBACK_CHUNK = 8192
    }
}
