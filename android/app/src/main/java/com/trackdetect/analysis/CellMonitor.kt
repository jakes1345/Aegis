package com.trackdetect.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.trackdetect.Rat
import com.trackdetect.ServingCell

/**
 * Reads the serving cell your baseband is actually camped on, natively.
 *
 * A fake tower does not announce itself in the spectrum in any way software can
 * tell from a real one — but it *does* change what your modem reports: which
 * cell serves you, on what technology, how loud, and with how many neighbours.
 * Android exposes exactly those four through TelephonyManager, no root and no
 * adb required, so watching them over time is how catchers stand out.
 */
class CellMonitor(private val context: Context) {

    private val tm: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun unavailableReason(): String? {
        if (tm == null) return "No telephony service on this device"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return "Location permission needed to read cell identity"
        if (tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) return "No cellular radio (Wi-Fi-only device)"
        return null
    }

    /** The serving cell right now, or null if it cannot be read. */
    fun sample(): ServingCell? {
        val manager = tm ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val all = try {
            manager.allCellInfo
        } catch (_: SecurityException) {
            return null
        } ?: return null
        if (all.isEmpty()) return null

        val registered = all.firstOrNull { it.isRegistered } ?: return null
        val neighbors = all.count { !it.isRegistered }
        return build(registered, neighbors)
    }

    private fun build(info: CellInfo, neighbors: Int): ServingCell? {
        val now = System.currentTimeMillis()
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val ci = id.ci
                if (ci == Int.MAX_VALUE) return null
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = id.tac.takeIf { it != Int.MAX_VALUE }?.toString(),
                    cellId = ci.toString(), rat = Rat.LTE,
                    signalDbm = info.cellSignalStrength.dbm.takeIf { it > -160 && it < 0 },
                    neighbors = neighbors, ts = now
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val cid = id.cid
                if (cid == Int.MAX_VALUE) return null
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = id.lac.takeIf { it != Int.MAX_VALUE }?.toString(),
                    cellId = cid.toString(), rat = Rat.UMTS,
                    signalDbm = info.cellSignalStrength.dbm.takeIf { it > -160 && it < 0 },
                    neighbors = neighbors, ts = now
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val cid = id.cid
                if (cid == Int.MAX_VALUE) return null
                ServingCell(
                    mcc = id.mccString, mnc = id.mncString,
                    tac = id.lac.takeIf { it != Int.MAX_VALUE }?.toString(),
                    cellId = cid.toString(), rat = Rat.GSM,
                    signalDbm = info.cellSignalStrength.dbm.takeIf { it > -160 && it < 0 },
                    neighbors = neighbors, ts = now
                )
            }
            else -> buildNr(info, neighbors, now)
        }
    }

    private fun buildNr(info: CellInfo, neighbors: Int, now: Long): ServingCell? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info !is CellInfoNr) return null
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val nci = id.nci
        if (nci == Long.MAX_VALUE) return null
        val dbm = (info.cellSignalStrength as? CellSignalStrengthNr)?.dbm
        return ServingCell(
            mcc = id.mccString, mnc = id.mncString,
            tac = id.tac.takeIf { it != Int.MAX_VALUE }?.toString(),
            cellId = nci.toString(), rat = Rat.NR5G,
            signalDbm = dbm?.takeIf { it > -160 && it < 0 },
            neighbors = neighbors, ts = now
        )
    }
}
