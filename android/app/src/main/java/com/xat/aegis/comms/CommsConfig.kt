package com.xat.aegis.comms

import android.content.Context

/** Plain preference data for the comms module; secrets live in [IdentityStore]. */
class CommsConfig(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Base URL of the relay Worker, no trailing slash, or null until registered. */
    val relayUrl: String? get() = prefs.getString(KEY_RELAY, null)

    /** This identity's Aegis number, once the relay has allocated one. */
    val number: String? get() = prefs.getString(KEY_NUMBER, null)

    val displayName: String get() = prefs.getString(KEY_NAME, "") ?: ""

    val listed: Boolean get() = prefs.getBoolean(KEY_LISTED, true)

    /** The owner's choice to keep the relay connection open in the background. */
    val online: Boolean get() = prefs.getBoolean(KEY_ONLINE, false)

    /** The UnifiedPush endpoint the relay last acknowledged. */
    val pushEndpoint: String? get() = prefs.getString(KEY_PUSH, null)

    val isRegistered: Boolean get() = relayUrl != null && number != null

    /** Set once the background connection has been switched on by default for this install. */
    val onlineDefaultApplied: Boolean get() = prefs.getBoolean(KEY_ONLINE_DEFAULT, false)

    fun markOnlineDefaultApplied() { prefs.edit().putBoolean(KEY_ONLINE_DEFAULT, true).apply() }

    /** When this identity last published a new fallback key; 0 before the first rotation. */
    val fallbackRotatedAt: Long get() = prefs.getLong(KEY_FALLBACK_ROTATED, 0L)

    fun setFallbackRotatedAt(ts: Long) { prefs.edit().putLong(KEY_FALLBACK_ROTATED, ts).apply() }

    fun saveRegistration(relayUrl: String, number: String, name: String, listed: Boolean) {
        prefs.edit()
            .putString(KEY_RELAY, relayUrl.trimEnd('/'))
            .putString(KEY_NUMBER, number)
            .putString(KEY_NAME, name)
            .putBoolean(KEY_LISTED, listed)
            .remove(KEY_PUSH)
            .apply()
    }

    fun setDisplayName(name: String) { prefs.edit().putString(KEY_NAME, name).apply() }
    fun setListed(listed: Boolean) { prefs.edit().putBoolean(KEY_LISTED, listed).apply() }
    fun setOnline(online: Boolean) { prefs.edit().putBoolean(KEY_ONLINE, online).apply() }
    fun setPushEndpoint(endpoint: String?) { prefs.edit().putString(KEY_PUSH, endpoint).apply() }

    fun clear() { prefs.edit().clear().apply() }

    /**
     * Removes what the Twilio-era module kept in this same preferences file:
     * its worker URL, device token, sync cursors and voice token, and the phone
     * number that went with them. Runs once; a file without the old keys is
     * left alone.
     */
    fun purgeLegacy() {
        if (LEGACY_KEYS.none { prefs.contains(it) }) return
        prefs.edit().apply { LEGACY_KEYS.forEach { remove(it) }; remove(KEY_NUMBER) }.apply()
    }

    private companion object {
        const val PREFS = "comms"
        const val KEY_RELAY = "relay_url"
        const val KEY_NUMBER = "number"
        const val KEY_NAME = "name"
        const val KEY_LISTED = "listed"
        const val KEY_ONLINE = "online"
        const val KEY_PUSH = "push_endpoint"
        const val KEY_ONLINE_DEFAULT = "online_default_applied"
        const val KEY_FALLBACK_ROTATED = "fallback_rotated_at"
        val LEGACY_KEYS = listOf(
            "worker_url", "device_id", "token_enc", "sync_seq", "sync_updated",
            "push_token", "sync_calls", "voice_token", "voice_at"
        )
    }
}
