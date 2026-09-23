package com.xat.aegis.comms

import android.content.Context
import android.util.Base64

/**
 * Pairing state for the relay: where it is, who this phone is to it, and how far
 * the local cache has synced. The bearer token is stored encrypted under the
 * comms Keystore key; everything else is plain preference data.
 */
class CommsConfig(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Base URL of the deployed Worker, no trailing slash, or null until paired. */
    val workerUrl: String? get() = prefs.getString(KEY_URL, null)

    val deviceId: String? get() = prefs.getString(KEY_DEVICE_ID, null)

    /** The owner's number in E.164 as the relay reports it. */
    val number: String? get() = prefs.getString(KEY_NUMBER, null)

    /** Highest message sequence number the cache holds. */
    val syncCursor: Long get() = prefs.getLong(KEY_SEQ, 0L)

    /** Relay clock (epoch millis) up to which status updates have been applied. */
    val updatesCursor: Long get() = prefs.getLong(KEY_UPDATED, 0L)

    /** The FCM token the relay last acknowledged, so a repeat is not re-sent. */
    val registeredPushToken: String? get() = prefs.getString(KEY_PUSH, null)

    val isPaired: Boolean get() = workerUrl != null && prefs.contains(KEY_TOKEN)

    /** The bearer token, decrypted on demand; null when unpaired or undecryptable. */
    fun token(): String? {
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching { CommsCrypto.decryptString(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull()
    }

    fun savePairing(workerUrl: String, deviceId: String, token: String, number: String?) {
        val sealed = Base64.encodeToString(CommsCrypto.encryptString(token), Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_URL, workerUrl.trimEnd('/'))
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, sealed)
            .putString(KEY_NUMBER, number)
            .putLong(KEY_SEQ, 0L)
            .putLong(KEY_UPDATED, 0L)
            .remove(KEY_PUSH)
            .apply()
    }

    fun setNumber(number: String?) { prefs.edit().putString(KEY_NUMBER, number).apply() }

    fun setSyncCursor(seq: Long) { prefs.edit().putLong(KEY_SEQ, seq).apply() }

    fun setUpdatesCursor(ts: Long) { prefs.edit().putLong(KEY_UPDATED, ts).apply() }

    fun setRegisteredPushToken(token: String?) { prefs.edit().putString(KEY_PUSH, token).apply() }

    fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val PREFS = "comms"
        const val KEY_URL = "worker_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token_enc"
        const val KEY_NUMBER = "number"
        const val KEY_SEQ = "sync_seq"
        const val KEY_UPDATED = "sync_updated"
        const val KEY_PUSH = "push_token"
    }
}
