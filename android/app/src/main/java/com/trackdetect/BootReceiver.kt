package com.trackdetect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Brings scanning back after a reboot — but only for someone who actually had it
 * running, and only when the permissions a location foreground service needs are
 * still granted.
 *
 * Previously this started the service unconditionally, so every reboot put the phone
 * back into a continuous BLE and GPS scan whether or not the user had ever asked for
 * one, and on a device missing location permission it started a service that could
 * only immediately kill itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        AppSettings.load(context)
        if (!AppSettings.scanEnabled) return

        val required = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
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
