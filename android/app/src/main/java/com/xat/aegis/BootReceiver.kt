package com.xat.aegis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.xat.aegis.comms.CommsRepository

/**
 * Brings scanning back after a reboot — but only for someone who actually had it
 * running, who turned on "Resume scanning after reboot", and only when the
 * permissions a location foreground service needs at boot are granted.
 *
 * Android only lets a location-typed foreground service start from BOOT_COMPLETED
 * with ACCESS_BACKGROUND_LOCATION ("Allow all the time"). Without it the service's
 * startForeground() is refused, the service stops without ever having called it,
 * and the system kills the app for breaking the startForegroundService() contract.
 *
 * Previously this started the service unconditionally, so every reboot put the phone
 * back into a continuous BLE and GPS scan whether or not the user had ever asked for
 * one, and on a device missing location permission it started a service that could
 * only immediately kill itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Encrypted calls and messages: an owner who keeps comms online gets the
        // relay connection back after a reboot, instead of missing every call
        // until they happen to open Aegis. init() starts it only in that case.
        runCatching { CommsRepository.init(context.applicationContext) }

        AppSettings.load(context)
        if (!AppSettings.scanEnabled || !AppSettings.resumeAfterReboot) return

        val required = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        if (required.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        ) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, ScanService::class.java)
        )
    }
}
