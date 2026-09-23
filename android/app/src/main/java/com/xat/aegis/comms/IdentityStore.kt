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
 * hardware. Every mutation is followed by [persist], under one lock, so a
 * ratchet step is never lost between a message and a crash.
 */
class IdentityStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("comms_identity", Context.MODE_PRIVATE)
    private val file = File(appContext.filesDir, "comms_identity.pickle")
    private val lock = Any()

    @Volatile
    private var identity: Identity? = null

    /** True when an identity exists on disk (or in memory). */
    fun exists(): Boolean = identity != null || (file.exists() && prefs.contains(KEY_WRAPPED))

    /** The identity, restored from disk on first use, or null when none exists. */
    fun get(): Identity? {
        identity?.let { return it }
        synchronized(lock) {
            identity?.let { return it }
            if (!file.exists()) return null
            val key = unwrapKey() ?: return null
            val restored = Identity.restore(file.readText(), key)
            identity = restored
            return restored
        }
    }

    /** Creates and persists a brand-new identity, replacing any existing one. */
    fun create(): Identity {
        synchronized(lock) {
            val fresh = Identity.create()
            val key = randomKey()
            prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(KeystoreBox.encrypt(key), Base64.NO_WRAP)).apply()
            identity = fresh
            persistLocked(fresh, key)
            return fresh
        }
    }

    /** Writes the current state to disk. Call after any session or key change. */
    fun persist() {
        synchronized(lock) {
            val current = identity ?: return
            val key = unwrapKey() ?: return
            persistLocked(current, key)
        }
    }

    /** Runs [block] on the identity and persists afterwards. */
    fun <T> update(block: (Identity) -> T): T {
        synchronized(lock) {
            val current = get() ?: throw IllegalStateException("No identity")
            val result = block(current)
            unwrapKey()?.let { persistLocked(current, it) }
            return result
        }
    }

    /** Deletes the identity, its pickle and its wrapping key. */
    fun destroy() {
        synchronized(lock) {
            identity?.destroy()
            identity = null
            file.delete()
            prefs.edit().clear().apply()
        }
    }

    private fun persistLocked(current: Identity, key: ByteArray) {
        val pickle = current.pickle(key)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(pickle)
        if (!tmp.renameTo(file)) {
            file.writeText(pickle)
            tmp.delete()
        }
    }

    private fun unwrapKey(): ByteArray? {
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        return runCatching { KeystoreBox.decrypt(Base64.decode(wrapped, Base64.NO_WRAP)) }.getOrNull()
    }

    private companion object {
        const val KEY_WRAPPED = "pickle_key_wrapped"
    }
}
