package app.clairix

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class FmDemodulatorTest {

    private val fs = FmDemodulator.INPUT_RATE

    private fun u8(v: Double): Byte = (v + 127.5).roundToInt().coerceIn(0, 255).toByte()

    /** Half a second of I/Q at 2.016 MHz, in USB-sized buffers. */
    private fun iq(sample: (Int) -> Pair<Double, Double>): List<ByteArray> {
        val pairs = fs / 2
        val chunk = 65536
        val out = ArrayList<ByteArray>()
        var n = 0
        while (n < pairs) {
            val len = minOf(chunk, pairs - n)
            val b = ByteArray(len * 2)
            for (k in 0 until len) {
                val (i, q) = sample(n + k)
                b[2 * k] = u8(i)
                b[2 * k + 1] = u8(q)
            }
            out += b
            n += len
        }
        return out
    }

    private fun run(demod: FmDemodulator, input: List<ByteArray>): List<ShortArray> =
        runBlocking { demod.demodulate(input.asFlow()).toList() }

    @Test
    fun decimationLandsExactlyOn48k() {
        assertEquals(42, FmDemodulator.TOTAL_DECIM)
        assertEquals(FmDemodulator.OUTPUT_RATE, FmDemodulator.INPUT_RATE / FmDemodulator.TOTAL_DECIM)
        assertEquals(0, FmDemodulator.INPUT_RATE % FmDemodulator.TOTAL_DECIM)
        val out = run(FmDemodulator(), iq { 0.0 to 0.0 })
        // fs/2 input pairs -> 24000 audio samples, emitted in whole 1920-sample chunks.
        assertEquals((fs / 2) / 42 / FmDemodulator.CHUNK_SAMPLES, out.size)
    }

    @Test
    fun noiseAloneKeepsTheSquelchClosedAndSilent() {
        val rnd = Random(7)
        val demod = FmDemodulator()
        val out = run(demod, iq { rnd.nextGaussian() * 20 to rnd.nextGaussian() * 20 })
        assertFalse(demod.squelchOpen.value)
        assertTrue(out.last().all { it == 0.toShort() })
    }

    @Test
    fun nfmCarrierWithToneOpensTheSquelch() {
        val rnd = Random(11)
        val demod = FmDemodulator()
        val beta = 3000.0 / 1000.0 // 3 kHz deviation, 1 kHz tone
        val out = run(demod, iq { n ->
            val t = n.toDouble() / fs
            val phase = beta * sin(2 * PI * 1000.0 * t)
            (60 * cos(phase) + rnd.nextGaussian() * 20) to (60 * sin(phase) + rnd.nextGaussian() * 20)
        })
        assertTrue(demod.squelchOpen.value)
        // The tone comes through: a 1 kHz sine at 48 kHz has a strong 48-sample period.
        val a = out.last()
        var corr = 0.0
        var energy = 0.0
        for (k in 0 until a.size - 48) {
            corr += a[k].toDouble() * a[k + 48]
            energy += a[k].toDouble() * a[k]
        }
        assertTrue("energy=$energy", energy > 0)
        assertTrue("corr=${corr / energy}", corr / energy > 0.8)
    }

    @Test
    fun tighterSquelchNeedsAQuieterChannel() {
        assertTrue(FmDemodulator.noiseThresholdFor(1f) < FmDemodulator.noiseThresholdFor(0f))
        // Even the loosest setting stays below the ~0.78 noise floor of an empty channel,
        // including the hysteresis margin.
        assertTrue(FmDemodulator.noiseThresholdFor(0f) * FmDemodulator.SQUELCH_HYSTERESIS < 0.78f)
    }
}
