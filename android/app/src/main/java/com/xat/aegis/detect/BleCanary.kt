package com.xat.aegis.detect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.xat.aegis.Registry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

sealed interface BleCanaryState {
    data object Starting : BleCanaryState
    /** Advertising; [payloadHex] is the manufacturer data on the air, for the self-test screen. */
    data class Advertising(val payloadHex: String) : BleCanaryState
    /** The scanner saw the canary. [address] is the (random) address the stack chose for it. */
    data class Detected(val address: String, val rssi: Int) : BleCanaryState
    data object Stopped : BleCanaryState
    data class Error(val message: String) : BleCanaryState
}

/**
 * A self-test: the phone pretends to be an AirTag for a moment, and the detector
 * has to notice.
 *
 * The advertisement is built to the Find My "separated" format — Apple company id,
 * type 0x12, length byte 0x19, twenty-five bytes of status and key — which is
 * exactly what [Signatures.match] classifies as an AirTag. The key bytes are random
 * per run, so the payload also serves as the canary's identity: [isCanary] matches on
 * them, and the scanning service drops the packet before it can become a detection
 * or trigger a DULT interrogation of ourselves.
 *
 * One honest caveat. Whether a phone can hear its own advertisement depends on the
 * Bluetooth controller: some report their own packets to the scanner, many do not,
 * and the address they advertise under is a random one the stack never exposes to
 * the app. So on the phones that cannot, this test only proves the advertiser works,
 * and a second phone running Aegis is what proves the scanner does. The state says
 * which happened.
 */
object BleCanary {

    private const val APPLE = 0x004C
    private const val FIND_MY_TYPE = 0x12
    private const val FIND_MY_LENGTH = 0x19

    /** Status byte of an AirTag that has been away from its owner: full battery, separated. */
    private const val STATUS_SEPARATED = 0x10

    @Volatile
    private var active: ByteArray? = null

    /** True when [record] carries the canary payload currently on the air. */
    fun isCanary(record: ScanRecord?): Boolean {
        val payload = active ?: return false
        val apple = record?.getManufacturerSpecificData(APPLE) ?: return false
        return apple.contentEquals(payload)
    }

    /**
     * Starts advertising for [durationMs] and reports what happens. Cancelling the
     * collector stops the advertisement early.
     */
    @SuppressLint("MissingPermission") // Checked first; the stack's own checks are also caught.
    fun start(context: Context, durationMs: Long): Flow<BleCanaryState> = callbackFlow {
        val app = context.applicationContext
        trySend(BleCanaryState.Starting)

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            trySend(BleCanaryState.Error("Bluetooth advertise permission not granted"))
            close()
            return@callbackFlow
        }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            trySend(BleCanaryState.Error("Bluetooth is off"))
            close()
            return@callbackFlow
        }
        val advertiser: BluetoothLeAdvertiser? = try { adapter.bluetoothLeAdvertiser } catch (_: SecurityException) { null }
        if (advertiser == null) {
            trySend(BleCanaryState.Error("This phone's Bluetooth controller cannot advertise"))
            close()
            return@callbackFlow
        }

        val payload = buildPayload()
        active = payload

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            // Non-connectable: the stack then omits the 3-byte flags field, and 2 + 2 + 27
            // bytes of manufacturer data is the whole 31-byte legacy budget.
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(APPLE, payload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                trySend(BleCanaryState.Advertising(payload.joinToString("") { "%02X".format(it) }))
            }
            override fun onStartFailure(errorCode: Int) {
                trySend(BleCanaryState.Error(failureText(errorCode)))
                close()
            }
        }

        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            trySend(BleCanaryState.Error("Advertising denied: ${e.message}"))
            close()
            return@callbackFlow
        } catch (e: IllegalStateException) {
            trySend(BleCanaryState.Error("Bluetooth unavailable: ${e.message}"))
            close()
            return@callbackFlow
        }

        // The scanning service publishes every packet it receives; the canary's own
        // payload among them is the proof. Each distinct address is reported once.
        val seen = HashSet<String>()
        launch {
            Registry.scans.collect { result ->
                if (!isCanary(result.scanRecord)) return@collect
                val address = BleNames.formatMac(result.device?.address ?: return@collect)
                if (seen.add(address)) trySend(BleCanaryState.Detected(address, result.rssi))
            }
        }

        launch {
            delay(durationMs.coerceAtLeast(1_000L))
            trySend(BleCanaryState.Stopped)
            close()
        }

        awaitClose {
            active = null
            // Stopping an advertisement that never started is harmless; stopping one
            // that did is what keeps the fake AirTag from outliving the test.
            try { advertiser.stopAdvertising(callback) } catch (_: SecurityException) {} catch (_: IllegalStateException) {}
        }
    }

    /**
     * Apple manufacturer payload as [ScanRecord.getManufacturerSpecificData] returns it:
     * type, length, status, 22 key bytes, the key's top two bits, and a hint byte.
     */
    private fun buildPayload(): ByteArray {
        val random = SecureRandom()
        val payload = ByteArray(2 + FIND_MY_LENGTH)
        payload[0] = FIND_MY_TYPE.toByte()
        payload[1] = FIND_MY_LENGTH.toByte()
        payload[2] = STATUS_SEPARATED.toByte()
        val key = ByteArray(22)
        random.nextBytes(key)
        key.copyInto(payload, 3)
        payload[25] = (random.nextInt(4)).toByte()
        payload[26] = 0x00
        return payload
    }

    private fun failureText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement too large for this controller"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "No free advertising slot on the controller"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Canary already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth stack internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising not supported on this phone"
        else -> "Advertising failed ($code)"
    }
}
