package com.xat.aegis.analysis

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.xat.aegis.Alert
import com.xat.aegis.AudioRouteEvent
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import com.xat.aegis.comms.CallManager

/**
 * Watches for a microphone appearing where none should be.
 *
 * A Bluetooth or USB headset that connects while the owner is on a call or
 * listening to something is a headset. One whose *input* side appears while
 * nothing is playing and no call is up is how a paired earpiece left in a bag,
 * a "smart" pen, or a Bluetooth audio bug gets a live microphone on this phone:
 * once the phone routes its input to the device, anything that records hears
 * the room through it — and some devices stream the other way, the phone's
 * microphone feeding the remote device. Either way the owner should know a
 * wireless input has just attached itself.
 *
 * Reported through [Registry.publishAudioRoute] for the Device tab and through
 * [Registry.publishAlert] for the timeline and the shade. Deduplicated per
 * device so the same headset re-pairing every morning is one line a day.
 */
object AudioRouteMonitor {

    /**
     * Input device types worth a warning: the wireless ones and USB. Spelled out
     * where the constant is newer than minSdk (TYPE_BLE_BROADCAST is API 33).
     */
    private val SUSPECT_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,   // 7
        AudioDeviceInfo.TYPE_USB_HEADSET,     // 22
        AudioDeviceInfo.TYPE_BLE_HEADSET,     // 26
        30                                    // TYPE_BLE_BROADCAST
    )

    /**
     * Inputs every phone has and that mean nothing: the built-in microphone, the
     * modem's voice path, the FM tuner, the mixer's own loopback and the echo
     * reference. The startup sweep flags any input that is not one of these.
     */
    private val BENIGN_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,     // 15
        AudioDeviceInfo.TYPE_TELEPHONY,       // 18
        AudioDeviceInfo.TYPE_FM_TUNER,        // 16
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,   // 25
        28                                    // TYPE_ECHO_REFERENCE
    )

    private const val DEDUPE_MS = 6 * 60 * 60_000L

    private val lock = Any()
    private var audioManager: AudioManager? = null
    private var callback: AudioDeviceCallback? = null

    /** Registers for device changes and sweeps what is already connected. Idempotent. */
    fun start(context: Context) {
        synchronized(lock) {
            if (callback != null) return
            val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                    added ?: return
                    val quiet = isQuiet(am)
                    for (d in added) {
                        if (d.isSource && d.type in SUSPECT_INPUT_TYPES && quiet) report(d, startup = false)
                    }
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = Unit
            }
            runCatching { am.registerAudioDeviceCallback(cb, null) }.onFailure { return }
            audioManager = am
            callback = cb
            // The callback reports only changes; a microphone attached before the
            // service started is still a microphone.
            sweep(am)
        }
    }

    fun stop() {
        synchronized(lock) {
            val am = audioManager
            val cb = callback
            audioManager = null
            callback = null
            if (am != null && cb != null) runCatching { am.unregisterAudioDeviceCallback(cb) }
        }
    }

    /** Any input device beyond the phone's own, with nothing playing and no call up. */
    private fun sweep(am: AudioManager) {
        if (!isQuiet(am)) return
        val inputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull() ?: return
        for (d in inputs) {
            if (d.isSource && d.type !in BENIGN_INPUT_TYPES) report(d, startup = true)
        }
    }

    /**
     * Nothing is using audio: no media playing, the audio mode is normal (not in
     * a cellular or VoIP call, not ringing) and no Aegis call is up. Aegis's own
     * calls set MODE_IN_COMMUNICATION too, but CallManager is consulted directly
     * so a call that is still being set up is not mistaken for silence.
     */
    private fun isQuiet(am: AudioManager): Boolean {
        val music = runCatching { am.isMusicActive }.getOrDefault(false)
        if (music) return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return false
        if (CallManager.call.value != null) return false
        return true
    }

    private fun report(d: AudioDeviceInfo, startup: Boolean) {
        val now = System.currentTimeMillis()
        val name = runCatching { d.productName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unnamed device"
        val address = runCatching { d.address }.getOrNull()?.takeIf { it.isNotBlank() }
        val event = AudioRouteEvent(ts = now, deviceType = d.type, deviceName = name, productName = name)
        Registry.publishAudioRoute(event)
        val label = event.deviceTypeLabel
        val wireless = d.type != AudioDeviceInfo.TYPE_USB_HEADSET && d.type != AudioDeviceInfo.TYPE_USB_DEVICE
        Registry.publishAlert(
            Alert(
                id = "audio_route@${d.type}@${address ?: name}@$now",
                ts = now,
                severity = Severity.MEDIUM,
                title = if (wireless) "Bluetooth audio input connected while nothing is playing — possible listening device"
                        else "Audio input connected while nothing is playing — possible listening device",
                detail = "$name ($label${if (address != null) ", $address" else ""}) " +
                    (if (startup) "has a live microphone route to this phone" else "just connected a microphone to this phone") +
                    " with no call up and no media playing. A headset you are wearing is fine; " +
                    "anything you cannot see is not. Settings → Connected devices shows what is paired.",
                kind = EventKind.AUDIO_ROUTE,
                dedupeKey = "audio_route@${d.type}@${address ?: name}"
            ),
            dedupeWindowMs = DEDUPE_MS
        )
    }
}
