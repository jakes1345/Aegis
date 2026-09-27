package com.xat.aegis.comms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Photos and videos sent or received in a conversation.
 *
 * The bytes are far too big for a row in the messages table (which is
 * decrypted whole every time a conversation is shown), so each file lives
 * under its message id in the app's private storage, encrypted under the same
 * Keystore key as message bodies. The message row keeps only [MediaBody]: the
 * type, dimensions, size and a small thumbnail.
 *
 * This is also where a picked image is made small enough to send: scaled to
 * at most [MAX_IMAGE_EDGE] on its long side and re-encoded as JPEG at
 * [JPEG_QUALITY], with the camera's orientation applied. Videos are sent as
 * they are, up to [MAX_BYTES]; transcoding them is out of scope for a relay
 * that carries 64 KB envelopes.
 */
object CommsMedia {

    const val MAX_IMAGE_EDGE = 1920
    const val JPEG_QUALITY = 80
    /** The largest file that may be sent; [CommsWire.MEDIA_CHUNK_BYTES] chunks of it. */
    const val MAX_BYTES = 5L * 1024 * 1024
    /** The most envelopes one file may take; follows from the two limits above. */
    val MAX_CHUNKS: Int = ((MAX_BYTES + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES).toInt()
    private const val THUMB_EDGE = 240
    /** The thumbnail rides in the header envelope beside the sender's keys; keep it small. */
    private const val THUMB_MAX_B64 = 16_000

    /** A file ready to send. */
    class Prepared(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val thumbB64: String
    ) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    private lateinit var dir: File
    private lateinit var cacheDir: File

    fun init(context: Context) {
        val app = context.applicationContext
        dir = File(app.filesDir, "comms_media").also { it.mkdirs() }
        cacheDir = File(app.cacheDir, "comms_media_play").also { it.mkdirs() }
        purgeTempFiles(app)
    }

    /** Anything younger than this may still be in use by a recording or camera capture. */
    private const val TEMP_STALE_MS = 5L * 60 * 1000

    /**
     * Deletes plaintext temporaries left in the cache by a previous run: video
     * play copies, voicemail play copies, and voicemail recordings and camera
     * captures old enough that no recording or capture can still be writing them.
     */
    fun purgeTempFiles(context: Context) {
        val cache = context.applicationContext.cacheDir
        val cutoff = System.currentTimeMillis() - TEMP_STALE_MS
        fun File.stale() = lastModified() < cutoff
        runCatching {
            File(cache, "comms_media_play").listFiles()?.forEach { it.delete() }
            cache.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".amr")) return@forEach
                if (f.name.startsWith("vm_play_")) f.delete()
                else if (f.name.startsWith("vm_") && f.stale()) f.delete()
            }
            File(cache, "camera").listFiles()?.forEach { if (it.isFile && it.stale()) it.delete() }
        }
    }

    private fun fileFor(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.bin")

    fun exists(id: String): Boolean = ::dir.isInitialized && fileFor(id).isFile

    /** Stores [bytes] encrypted under the message [id]. */
    fun save(id: String, bytes: ByteArray) {
        val f = fileFor(id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(KeystoreBox.encrypt(bytes))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The decrypted file for message [id], or null when it is gone or unreadable. */
    fun load(id: String): ByteArray? {
        val f = fileFor(id)
        if (!f.isFile) return null
        return runCatching { KeystoreBox.decrypt(f.readBytes()) }.getOrNull()
    }

    fun delete(id: String) { runCatching { fileFor(id).delete() } }

    fun deleteAll() {
        if (!::dir.isInitialized) return
        dir.listFiles()?.forEach { it.delete() }
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * A plaintext copy of message [id] in the cache, for players that read from a
     * path (VideoView). The caller deletes it with [discardTemp] when done.
     */
    fun tempCopy(id: String, extension: String): File? {
        val bytes = load(id) ?: return null
        val f = File(cacheDir, "play_${System.currentTimeMillis()}.$extension")
        return runCatching { f.writeBytes(bytes); f }.getOrNull()
    }

    fun discardTemp(f: File?) { f?.let { runCatching { it.delete() } } }

    // ── Preparing what the owner picked ──────────────────────────────────

    /**
     * Reads the image at [uri], scales it down to [MAX_IMAGE_EDGE] and encodes
     * it as JPEG. Throws [IllegalArgumentException] with a message for the
     * owner when the image cannot be read.
     */
    fun prepareImage(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null in bounds-only mode, so the lambda must
        // report success itself rather than hand back the (always null) bitmap.
        val opened = resolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, bounds)
            true
        } ?: throw IllegalArgumentException("That image could not be opened")
        if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("That file is not an image this phone can read")
        // Decode at a power-of-two fraction first, so a 50-megapixel photo never
        // exists in memory at full size, then scale exactly.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("That image could not be decoded")
        val rotation = resolver.openInputStream(uri)?.use { s -> runCatching { exifRotation(ExifInterface(s)) }.getOrDefault(0) } ?: 0
        val upright = rotate(fit(decoded, MAX_IMAGE_EDGE), rotation)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("That image is too large to send even after compression")
        val thumb = thumbnail(upright)
        return Prepared(bytes, "image/jpeg", upright.width, upright.height, 0L, thumb)
    }

    /**
     * Reads the video at [uri] as it is, with a frame for its thumbnail. Throws
     * [IllegalArgumentException] when it is bigger than [MAX_BYTES] or unreadable.
     */
    fun prepareVideo(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > MAX_BYTES) throw IllegalArgumentException(tooBig(size))
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream(if (size > 0) size.toInt() else 1 shl 20)
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalArgumentException(tooBig(total))
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: throw IllegalArgumentException("That video could not be opened")
        if (bytes.isEmpty()) throw IllegalArgumentException("That video is empty")
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val retriever = MediaMetadataRetriever()
        var width = 0
        var height = 0
        var duration = 0L
        var thumb = ""
        try {
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val w = width; width = height; height = w }
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { thumb = thumbnail(it) }
        } catch (_: Exception) {
            // No frame: the bubble shows a plain video tile.
        } finally {
            runCatching { retriever.release() }
        }
        return Prepared(bytes, mime, width, height, duration, thumb)
    }

    private fun tooBig(size: Long): String =
        "That video is ${size / (1024 * 1024)} MB; the most that can be sent is ${MAX_BYTES / (1024 * 1024)} MB. Trim it or pick a shorter clip."

    /** A JPEG at most [THUMB_EDGE] on its long side, small enough for the header envelope. */
    private fun thumbnail(source: Bitmap): String {
        val small = fit(source, THUMB_EDGE)
        var quality = 60
        while (true) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
            if (b64.length <= THUMB_MAX_B64 || quality <= 20) return b64
            quality -= 10
        }
    }

    /** [bitmap] scaled so its long side is at most [edge]; the bitmap itself when it already is. */
    private fun fit(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val scale = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    // ── Showing ──────────────────────────────────────────────────────────

    /** Decodes a JPEG (or any image) to a bitmap no larger than [maxEdge] on its long side. */
    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun decodeB64(b64: String, maxEdge: Int): Bitmap? =
        runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.let { decode(it, maxEdge) }
}
