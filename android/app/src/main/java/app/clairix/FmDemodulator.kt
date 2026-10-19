package app.clairix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

fun hannFir(numTaps: Int, cutoffRatio: Float): FloatArray {
    val taps = FloatArray(numTaps)
    val mid = (numTaps - 1) / 2.0
    val wc = 2.0 * PI * cutoffRatio
    var sum = 0.0
    for (n in 0 until numTaps) {
        val k = n - mid
        val sinc = if (k == 0.0) wc / PI else sin(wc * k) / (PI * k)
        val window = 0.5 - 0.5 * cos(2.0 * PI * n / (numTaps - 1))
        val v = sinc * window
        taps[n] = v.toFloat()
        sum += v
    }
    val norm = (1.0 / sum).toFloat()
    for (n in 0 until numTaps) taps[n] *= norm
    return taps
}

class FmDemodulator {
    companion object {
        // 42 x 48 kHz, so the two decimation stages land exactly on OUTPUT_RATE.
        const val INPUT_RATE = 2_016_000
        const val OUTPUT_RATE = 48_000
        const val STAGE1_DECIM = 6
        const val STAGE2_DECIM = 7
        const val TOTAL_DECIM = STAGE1_DECIM * STAGE2_DECIM
        const val CHUNK_SAMPLES = 1920
        const val RMS_WINDOW = 4800
        private const val TAPS = 31
        private const val DEEMPHASIS_TAU = 300e-6
        private const val DC_OFFSET = 127.5f
        /** The squelch closes again only once the noise rises this far above the open level. */
        const val SQUELCH_HYSTERESIS = 1.2f

        /**
         * Noise-squelch open threshold for a slider position 0..1 (1 = tightest). The noise
         * measure is the RMS of the discriminator's second difference: about 0.78 on an empty
         * channel, 0.01..0.3 with a carrier present, depending on its strength.
         */
        fun noiseThresholdFor(level: Float): Float = 0.6f - 0.55f * level.coerceIn(0f, 1f)
    }

    /**
     * Noise squelch: the channel opens while the high-frequency noise of the discriminator
     * output is BELOW this level. Without a carrier FM demodulates to loud wideband static;
     * a carrier quiets it, so an audio-level squelch would open on static and close on voice.
     */
    @Volatile var squelchThreshold: Float = noiseThresholdFor(0.3f)

    private val _squelchOpen = MutableStateFlow(false)
    val squelchOpen: StateFlow<Boolean> = _squelchOpen.asStateFlow()

    private val stage1Taps = hannFir(TAPS, 64_000f / INPUT_RATE)
    private val stage2Taps = hannFir(TAPS, 9_000f / (INPUT_RATE.toFloat() / STAGE1_DECIM))

    private class ComplexDecimator(private val taps: FloatArray, private val factor: Int) {
        private val histI = FloatArray(taps.size)
        private val histQ = FloatArray(taps.size)
        private var pos = 0
        private var sinceOutput = 0

        fun push(i: Float, q: Float, out: FloatArray, outCount: Int): Int {
            histI[pos] = i
            histQ[pos] = q
            pos = (pos + 1) % taps.size
            sinceOutput++
            if (sinceOutput < factor) return outCount
            sinceOutput = 0
            var accI = 0f
            var accQ = 0f
            var idx = pos
            for (t in taps.indices) {
                idx = if (idx == 0) taps.size - 1 else idx - 1
                val c = taps[t]
                accI += c * histI[idx]
                accQ += c * histQ[idx]
            }
            out[outCount] = accI
            out[outCount + 1] = accQ
            return outCount + 2
        }
    }

    private class DemodState(stage1Taps: FloatArray, stage2Taps: FloatArray) {
        val stage1 = ComplexDecimator(stage1Taps, STAGE1_DECIM)
        val stage2 = ComplexDecimator(stage2Taps, STAGE2_DECIM)
        var phasePrev = 0f
        var x1 = 0f
        var x2 = 0f
        var open = false
        var deemphY = 0f
        val deemphAlpha = (1.0 - exp(-1.0 / (OUTPUT_RATE * DEEMPHASIS_TAU))).toFloat()
        val rmsRing = FloatArray(RMS_WINDOW)
        var rmsPos = 0
        var rmsSumSq = 0.0
        var rmsFilled = 0
        val chunk = ShortArray(CHUNK_SAMPLES)
        var chunkFill = 0
        var stage1Out = FloatArray(2)
        var stage2Out = FloatArray(2)

        fun ensureCapacity(inputPairs: Int) {
            val need1 = (inputPairs / STAGE1_DECIM + 2) * 2
            if (stage1Out.size < need1) stage1Out = FloatArray(need1)
            val need2 = (need1 / 2 / STAGE2_DECIM + 2) * 2
            if (stage2Out.size < need2) stage2Out = FloatArray(need2)
        }

        fun rms(): Float {
            val n = if (rmsFilled < RMS_WINDOW) rmsFilled else RMS_WINDOW
            if (n == 0) return 0f
            val mean = rmsSumSq / n
            return if (mean <= 0.0) 0f else sqrt(mean).toFloat()
        }

        fun pushRms(v: Float) {
            val old = rmsRing[rmsPos]
            rmsSumSq += v.toDouble() * v - old.toDouble() * old
            rmsRing[rmsPos] = v
            rmsPos = (rmsPos + 1) % RMS_WINDOW
            if (rmsFilled < RMS_WINDOW) rmsFilled++
        }
    }

    fun demodulate(samples: Flow<ByteArray>): Flow<ShortArray> = flow {
        val state = DemodState(stage1Taps, stage2Taps)
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        samples.collect { bytes ->
            val pairs = bytes.size / 2
            state.ensureCapacity(pairs)
            var n1 = 0
            for (p in 0 until pairs) {
                val i = (bytes[p * 2].toInt() and 0xFF) - DC_OFFSET
                val q = (bytes[p * 2 + 1].toInt() and 0xFF) - DC_OFFSET
                n1 = state.stage1.push(i, q, state.stage1Out, n1)
            }
            var n2 = 0
            var k = 0
            while (k < n1) {
                n2 = state.stage2.push(state.stage1Out[k], state.stage1Out[k + 1], state.stage2Out, n2)
                k += 2
            }
            k = 0
            while (k < n2) {
                val i = state.stage2Out[k]
                val q = state.stage2Out[k + 1]
                k += 2
                val phase = atan2(q, i)
                var delta = phase - state.phasePrev
                state.phasePrev = phase
                if (delta > pi) delta -= twoPi else if (delta < -pi) delta += twoPi
                val x = delta / pi
                // Second difference: ~0 for voice-band audio, large for discriminator noise.
                state.pushRms(x - 2f * state.x1 + state.x2)
                state.x2 = state.x1
                state.x1 = x
                state.deemphY = state.deemphAlpha * x + (1f - state.deemphAlpha) * state.deemphY
                val y = state.deemphY
                val clamped = if (y > 1f) 1f else if (y < -1f) -1f else y
                state.chunk[state.chunkFill++] = (clamped * 32767f).toInt().toShort()
                if (state.chunkFill == CHUNK_SAMPLES) {
                    val noise = state.rms()
                    val threshold = squelchThreshold
                    state.open = if (state.open) noise < threshold * SQUELCH_HYSTERESIS else noise < threshold
                    _squelchOpen.value = state.open
                    // Closed squelch is silence, not static.
                    emit(if (state.open) state.chunk.copyOf() else ShortArray(CHUNK_SAMPLES))
                    state.chunkFill = 0
                }
            }
        }
    }
}
