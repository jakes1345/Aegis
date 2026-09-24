package com.xat.aegis.comms

import android.content.Context
import android.util.Base64
import uniffi.aegis_comms_crypto.Identity
import uniffi.aegis_comms_crypto.randomKey
import java.io.File

/**
 * Owns the device's cryptographic identity (the Rust [Identity]: Olm account,
 * sealing key pair, sessions) and keeps it on disk.
 *
 * The identity is serialised by the crate under a random 32-byte key; that key
 * is itself wrapped by the Android Keystore ([KeystoreBox]) and stored in
 * preferences, so the pickle on disk is useless without this device's secure
 * hardware. Every mutation is followed by a write to disk, under one lock, so
 * a ratchet step is never lost between a message and a crash. If the pickle
 * cannot be written the mutation fails loudly rather than leaving the in-memory
 * ratchet ahead of the one on disk: a rolled-back ratchet would reuse message
 * keys after a restart.
 */
class IdentityStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("comms_identity", Context.MODE_PRIVATE)
    private val file = File(appContext.filesDir, "comms_identity.pickle")
    private val lock = Any()

    @Volatile
    private var identity: Identity? = null

    /** The unwrapped pickle key, held once the Keystore has released it. */
    private var pickleKey: ByteArray? = null

    /** True when an identity exists on disk (or in memory). */
    fun exists(): Boolean = identity != null || (file.exists() && prefs.contains(KEY_WRAPPED))

    /** The identity, restored from disk on first use, or null when none exists. */
    fun get(): Identity? {
        identity?.let { return it }
        synchronized(lock) {
            identity?.let { return it }
            if (!file.exists()) return null
            val key = keyLocked() ?: return null
            // An unreadable pickle is an error, not "no identity": callers then leave
            // envelopes on the relay instead of acknowledging and losing them.
            val restored = try {
                Identity.restore(file.readText(), key)
            } catch (e: Exception) {
                throw IllegalStateException("The identity on this phone could not be read: ${e.message}", e)
            }
            identity = restored
            return restored
        }
    }

    /** Creates and persists a brand-new identity, replacing any existing one. */
    fun create(): Identity {
        synchronized(lock) {
            val fresh = Identity.create()
            val key = randomKey()
            prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(KeystoreBox.encrypt(key), Base64.NO_WRAP)).commit()
            pickleKey = key
            persistLocked(fresh, key)
            identity = fresh
            return fresh
        }
    }

    /** Writes the current state to disk. Throws when the pickle cannot be written. */
    fun persist() {
        synchronized(lock) {
            val current = identity ?: return
            persistLocked(current, requireKeyLocked())
        }
    }

    /**
     * Runs [block] on the identity and persists afterwards. The block's result
     * is returned only once the new state is on disk; a persistence failure
     * surfaces as an exception, so no caller acts on an unsaved ratchet step.
     */
    fun <T> update(block: (Identity) -> T): T {
        synchronized(lock) {
            val current = get() ?: throw IllegalStateException("No identity")
            val key = requireKeyLocked()
            val result = block(current)
            try {
                persistLocked(current, key)
            } catch (e: Exception) {
                // The in-memory ratchet is now ahead of the one on disk. Drop it, so
                // the next use reloads the last saved state and a redelivered
                // envelope decrypts again instead of looking like a spent key.
                identity = null
                runCatching { current.destroy() }
                throw IllegalStateException("The identity could not be saved: ${e.message}", e)
            }
            return result
        }
    }

    /** Deletes the identity, its pickle and its wrapping key. */
    fun destroy() {
        synchronized(lock) {
            identity?.destroy()
            identity = null
            pickleKey?.fill(0)
            pickleKey = null
            file.delete()
            prefs.edit().clear().commit()
        }
    }

    private fun persistLocked(current: Identity, key: ByteArray) {
        val pickle = current.pickle(key)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Written and synced to a temporary file, then renamed over the pickle:
        // a crash mid-write leaves either the old pickle or the new one, never
        // half of one.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(pickle.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${file.name}")
        }
    }

    private fun requireKeyLocked(): ByteArray =
        keyLocked() ?: throw IllegalStateException("The identity's pickle key is unavailable; the Keystore refused to unwrap it")

    /** The pickle key, unwrapped through the Keystore once and then cached. */
    private fun keyLocked(): ByteArray? {
        pickleKey?.let { return it }
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        val key = runCatching { KeystoreBox.decrypt(Base64.decode(wrapped, Base64.NO_WRAP)) }.getOrNull() ?: return null
        pickleKey = key
        return key
    }

    private companion object {
        const val KEY_WRAPPED = "pickle_key_wrapped"
    }
}
