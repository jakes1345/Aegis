package app.clairix

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScannerChannel(val name: String, val freqHz: Long, val active: Boolean = true)

class ScannerMonitor(private val context: Context, private val driver: SdrUsbDriver) {
    companion object {
        private const val PREFS = "scanner"
        private const val KEY_CHANNELS = "channels"
        private const val SAMPLE_RATE = 48_000
        private const val DWELL_MS = 250L
        private const val SQUELCH_POLL_MS = 50L
    }

    val demodulator = FmDemodulator()

    private val _channels = MutableStateFlow<List<ScannerChannel>>(emptyList())
    val channels: StateFlow<List<ScannerChannel>> = _channels.asStateFlow()

    private val _activeChannel = MutableStateFlow<ScannerChannel?>(null)
    val activeChannel: StateFlow<ScannerChannel?> = _activeChannel.asStateFlow()

    val squelchOpen: StateFlow<Boolean> = demodulator.squelchOpen

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastRecordingPath = MutableStateFlow<String?>(null)
    val lastRecordingPath: StateFlow<String?> = _lastRecordingPath.asStateFlow()

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var audioJob: Job? = null
    private var track: AudioTrack? = null

    /** Loads the saved channel list so the UI can show it before scanning starts. */
    suspend fun loadSaved() {
        _channels.value = withContext(Dispatchers.IO) { loadChannels(context) }
    }

    suspend fun start() {
        stop()
        loadSaved()
        // A failure inside one loop must not take the process down through the default handler.
        val handler = CoroutineExceptionHandler { _, _ -> _isRecording.value = false }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        scope = newScope
        val audioTrack = buildTrack()
        track = audioTrack
        audioTrack.play()
        scanJob = newScope.launch { scanLoop() }
        audioJob = newScope.launch { audioLoop(audioTrack) }
    }

    fun stop() {
        scanJob?.cancel()
        audioJob?.cancel()
        scope?.cancel()
        scanJob = null
        audioJob = null
        scope = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        _activeChannel.value = null
        _isRecording.value = false
    }

    fun addChannel(name: String, freqHz: Long) {
        if (!SdrUsbDriver.isTunable(freqHz)) return
        val next = _channels.value.filterNot { it.freqHz == freqHz } + ScannerChannel(name, freqHz)
        _channels.value = next
        persistChannels(context)
    }

    fun setChannelActive(freqHz: Long, active: Boolean) {
        _channels.value = _channels.value.map { if (it.freqHz == freqHz) it.copy(active = active) else it }
        persistChannels(context)
    }

    fun removeChannel(freqHz: Long) {
        _channels.value = _channels.value.filterNot { it.freqHz == freqHz }
        persistChannels(context)
    }

    /** [level] 0..1, higher = tighter (needs a cleaner carrier to open). */
    fun setSquelch(level: Float) {
        demodulator.squelchThreshold = FmDemodulator.noiseThresholdFor(level)
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
    }

    private suspend fun scanLoop() {
        var index = 0
        while (currentCoroutineIsActive()) {
            val list = _channels.value.filter { it.active && SdrUsbDriver.isTunable(it.freqHz) }
            if (list.isEmpty()) {
                _activeChannel.value = null
                delay(DWELL_MS)
                continue
            }
            index %= list.size
            val ch = list[index]
            if (!driver.setFrequency(ch.freqHz)) {
                // PLL did not lock (or the dongle went away): move on instead of listening
                // to the previous channel under this one's name.
                index++
                delay(SQUELCH_POLL_MS)
                continue
            }
            _activeChannel.value = ch
            delay(DWELL_MS)
            while (currentCoroutineIsActive() && demodulator.squelchOpen.value) {
                delay(SQUELCH_POLL_MS)
            }
            index++
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        currentCoroutineContext().isActive

    private suspend fun audioLoop(audioTrack: AudioTrack) {
        var recorder: FileOutputStream? = null
        var recordingFile: File? = null
        try {
            demodulator.demodulate(driver.sampleFlow())
                .flowOn(Dispatchers.Default)
                .collect { pcm ->
                    audioTrack.write(pcm, 0, pcm.size)
                    val open = demodulator.squelchOpen.value
                    if (open && recorder == null) {
                        val dir = context.getExternalFilesDir("scanner") ?: context.filesDir
                        if (!dir.exists()) dir.mkdirs()
                        val freq = _activeChannel.value?.freqHz ?: 0L
                        val file = File(dir, "${System.currentTimeMillis()}_$freq.raw")
                        recordingFile = file
                        recorder = FileOutputStream(file)
                        _isRecording.value = true
                    }
                    recorder?.let { out ->
                        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        buf.asShortBuffer().put(pcm)
                        out.write(buf.array())
                    }
                    if (!open && recorder != null) {
                        runCatching { recorder?.close() }
                        recorder = null
                        _isRecording.value = false
                        _lastRecordingPath.value = recordingFile?.absolutePath
                        recordingFile = null
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // sampleFlow() throws when the SDR is not open; the driver's state already says why.
        } finally {
            recorder?.let { out ->
                runCatching { out.close() }
                _isRecording.value = false
                _lastRecordingPath.value = recordingFile?.absolutePath
            }
        }
    }

    private fun persistChannels(ctx: Context) {
        val arr = JSONArray()
        for (ch in _channels.value) {
            arr.put(
                JSONObject()
                    .put("name", ch.name)
                    .put("freqHz", ch.freqHz)
                    .put("active", ch.active)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNELS, arr.toString())
            .apply()
    }

    private fun loadChannels(ctx: Context): List<ScannerChannel> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNELS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScannerChannel(
                    name = o.optString("name", ""),
                    freqHz = o.getLong("freqHz"),
                    active = o.optBoolean("active", true)
                )
            }
        }.getOrDefault(emptyList())
    }
}
