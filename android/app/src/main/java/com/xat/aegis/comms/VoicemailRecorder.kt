package com.xat.aegis.comms

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.util.Base64

/**
 * Records up to [MAX_DURATION_MS] of voice from the microphone and hands the
 * recording back as base64, ready to go inside an encrypted envelope.
 *
 * AMR-NB at 4.75 kbit/s is the smallest speech codec Android encodes: thirty
 * seconds come to under 18 KB (24 KB as base64), well inside the relay's
 * envelope limit. The recording is written to the app's cache directory while
 * it is being made and deleted as soon as it has been read, so nothing stays
 * on disk in the clear.
 *
 * One recording at a time; the caller holds the RECORD_AUDIO permission.
 */
class VoicemailRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir: File = appContext.cacheDir
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds recorded so far, or 0 when not recording. */
    val elapsedMs: Long get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    /**
     * Starts recording. Throws [IllegalStateException] when the microphone
     * could not be opened (another app holds it, the permission is missing,
     * or the encoder is not available), with nothing left behind.
     */
    fun start() {
        check(recorder == null) { "Already recording" }
        val f = File(cacheDir, "vm_${System.currentTimeMillis()}.amr")
        val r = MediaRecorder(appContext)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(4750)
            r.setOutputFile(f.absolutePath)
            r.setMaxDuration(MAX_DURATION_MS.toInt())
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            throw IllegalStateException(e.message ?: "The microphone could not be opened", e)
        }
        file = f
        startedAt = System.currentTimeMillis()
        recorder = r
    }

    /**
     * Stops recording and returns the recording as (base64 audio, duration in
     * milliseconds), or null when there is nothing usable (stopped at once,
     * or the encoder failed). The file is gone either way.
     */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        val f = file
        val elapsed = (System.currentTimeMillis() - startedAt).coerceIn(0L, MAX_DURATION_MS)
        recorder = null
        file = null
        return try {
            // stop() throws when nothing was written yet (a tap shorter than a frame).
            r.stop()
            val bytes = f?.takeIf { it.isFile }?.readBytes() ?: return null
            if (bytes.isEmpty() || elapsed < MIN_DURATION_MS) null
            else Base64.getEncoder().encodeToString(bytes) to elapsed
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
            f?.delete()
        }
    }

    /** Throws the recording away. Safe to call when not recording. */
    fun cancel() {
        val r = recorder
        val f = file
        recorder = null
        file = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        f?.delete()
    }

    companion object {
        const val MAX_DURATION_MS = 30_000L
        /** Anything shorter is a mis-tap, not a message. */
        const val MIN_DURATION_MS = 500L
    }
}
