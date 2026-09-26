package com.xat.aegis.comms

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.util.Base64

/**
 * Plays one voicemail at a time. The base64 AMR-NB from the message is written
 * to a file in the cache directory only for as long as it plays, since
 * MediaPlayer reads from a file, and deleted when playback stops.
 */
class VoicemailPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var playingId: String? = null

    /** The id of the message playing now, or null. */
    val nowPlaying: String? get() = playingId

    /**
     * Starts [audioB64] for message [id]; anything playing already stops
     * first. [onFinished] runs on the main thread when it ends or fails.
     * Returns false when the audio could not be decoded or played.
     */
    fun play(id: String, audioB64: String, onFinished: () -> Unit): Boolean {
        stop()
        val bytes = runCatching { Base64.getDecoder().decode(audioB64) }.getOrNull() ?: return false
        if (bytes.isEmpty()) return false
        val f = File(appContext.cacheDir, "vm_play_${System.currentTimeMillis()}.amr")
        val p = MediaPlayer()
        try {
            f.writeBytes(bytes)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener {
                if (playingId == id) stop()
                onFinished()
            }
            p.setOnErrorListener { _, _, _ ->
                if (playingId == id) stop()
                onFinished()
                true
            }
            p.prepare()
            p.start()
        } catch (_: Exception) {
            runCatching { p.release() }
            f.delete()
            return false
        }
        player = p
        file = f
        playingId = id
        return true
    }

    fun stop() {
        val p = player
        val f = file
        player = null
        file = null
        playingId = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        f?.delete()
    }
}
