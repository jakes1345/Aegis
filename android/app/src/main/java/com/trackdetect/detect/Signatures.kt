package com.trackdetect.detect

import android.bluetooth.le.ScanRecord
import com.trackdetect.Threat
import com.trackdetect.TrackerType

private const val APPLE = 0x004C
private const val MICROSOFT = 0x0006

private val COMPANY_NAMES = mapOf(
    0x004C to "Apple", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0157 to "Google",
    0x0006 to "Microsoft", 0x01D7 to "Xiaomi", 0x0059 to "Nordic Semiconductor",
    0x02E5 to "Espressif", 0x0499 to "Ruuvi", 0x00D7 to "Tile",
    0x0057 to "Harman", 0x00F8 to "Bose", 0x046D to "Logitech",
    0x007D to "Motorola", 0x022B to "Jabra", 0x006F to "Sony",
    0x0110 to "Amazon", 0x01AE to "GoPro", 0x0087 to "Garmin",
    0x0038 to "Texas Instruments", 0x001D to "Qualcomm", 0x0025 to "NXP",
    0x0469 to "Fitbit", 0x005E to "Tile", 0x0017 to "Ericsson",
    0x0048 to "Plantronics", 0x016D to "Bang & Olufsen",
    0x09AF to "Nothing", 0x038F to "MediaTek"
)

/**
 * Tracker signatures based on public reverse engineering — OpenHaystack,
 * packet captures and vendor documentation.
 *
 * Note the shape of Android's API differs from most write-ups:
 * getManufacturerSpecificData(id) returns the payload *after* the company ID,
 * so the Apple type byte is at index 0, not index 2.
 */
object Signatures {

    val AIRTAG = TrackerType(
        "airtag", "Apple AirTag", "Apple", Threat.CRITICAL,
        "Apple Find My tracker. Rotates its Bluetooth address, which is why this app " +
            "tracks it by advertisement fingerprint rather than by MAC. Commonly hidden in " +
            "wheel wells, bumpers and bag linings."
    )

    val FINDMY = TrackerType(
        "findmy", "Apple Find My item", "Apple / third party", Threat.HIGH,
        "An item on Apple's Find My network — Chipolo ONE Spot, Pebblebee, Motorola Tag " +
            "or similar. Same global crowdsourced network as an AirTag."
    )

    val TILE = TrackerType(
        "tile", "Tile tracker", "Tile / Life360", Threat.HIGH,
        "Every Tile app in range passively reports this tag's position back to Tile."
    )

    val SMARTTAG = TrackerType(
        "smarttag", "Samsung SmartTag", "Samsung", Threat.HIGH,
        "Samsung's Find network. Any nearby Galaxy running SmartThings reports it."
    )

    val SMARTTAG2 = TrackerType(
        "smarttag2", "Samsung SmartTag2", "Samsung", Threat.HIGH,
        "Second generation SmartTag with UWB as well as BLE, and much longer battery life."
    )

    val CHIPOLO = TrackerType(
        "chipolo", "Chipolo tracker", "Chipolo", Threat.HIGH,
        "Runs its own crowdsourced network, and the ONE Spot variant also joins Apple's."
    )

    val PEBBLEBEE = TrackerType(
        "pebblebee", "Pebblebee tracker", "Pebblebee", Threat.HIGH,
        "Clip, Card or Tag. Rechargeable, so it does not die after a year like a coin cell."
    )

    val ORBIT = TrackerType(
        "orbit", "Orbit / KeySmart", "Orbit", Threat.HIGH,
        "Uses its own network and piggybacks on Tile's."
    )

    val NUT = TrackerType(
        "nut", "Nut tracker", "Nut / Nutale", Threat.HIGH,
        "Crowdsourced Nut network. Cheap and widely sold outside the US."
    )

    val MOTOROLA_TAG = TrackerType(
        "motorola_tag", "Motorola MA1", "Motorola / Apple", Threat.HIGH,
        "Find My compatible tracker. Same Apple global crowdsourced network as AirTag."
    )

    val INVOXIA = TrackerType(
        "invoxia", "Invoxia GPS tracker", "Invoxia", Threat.HIGH,
        "Cellular + BLE GPS tracker designed for vehicle and asset tracking."
    )

    val CHIPOLO_SPOT = TrackerType(
        "chipolo_spot", "Chipolo ONE Spot", "Chipolo", Threat.HIGH,
        "Works on Apple's Find My network in addition to Chipolo's own."
    )

    val GPS_BLE = TrackerType(
        "gps_ble", "GPS tracker (BLE config)", "Unknown", Threat.HIGH,
        "A standalone GPS/LTE tracker advertising a Bluetooth configuration interface. " +
            "This is the kind of unit that gets magnet-mounted under a car."
    )

    val EDDYSTONE = TrackerType(
        "eddystone", "Eddystone beacon", "Google / generic", Threat.MEDIUM,
        "Proximity beacon. Usually retail analytics, occasionally repurposed for tracking."
    )

    private val GPS_NAME_HINTS = listOf(
        "gps", "tracker", "tk102", "tk103", "tk303", "gt02", "gt06", "gl300", "gl500",
        "st-9", "st901", "coban", "concox", "sinotrack", "meitrack", "queclink",
        "teltonika", "calamp", "bouncie", "optimus", "landairsea", "brickhouse",
        "spytec", "americaloc", "vyncs", "linxup", "samsara", "geotab"
    )

    /** Matches a 16-bit service UUID in either the UUID list or the service data. */
    private fun ScanRecord.hasShortUuid(short: Int): Boolean {
        val hex = "%04x".format(short)
        serviceUuids?.forEach {
            if (it.uuid.toString().regionMatches(4, hex, 0, 4, ignoreCase = true)) return true
        }
        serviceData?.keys?.forEach {
            if (it.uuid.toString().regionMatches(4, hex, 0, 4, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Devices that superficially look like trackers but are not. Checked first,
     * because otherwise every pair of AirPods in a coffee shop raises an alert
     * and the app becomes noise.
     */
    fun isBenign(record: ScanRecord?): Boolean {
        if (record == null) return false

        record.getManufacturerSpecificData(MICROSOFT)?.let { return true }

        val apple = record.getManufacturerSpecificData(APPLE) ?: return false
        if (apple.isEmpty()) return false

        return when (apple[0].toInt() and 0xFF) {
            0x01, 0x07 -> true                       // AirPods, Beats
            0x02 -> true                             // iBeacon — fixed infrastructure
            0x05, 0x09, 0x0B, 0x0D, 0x0E, 0x10 -> true // Handoff, Nearby, Watch, AirPlay
            else -> false
        }
    }

    fun match(record: ScanRecord?): TrackerType? {
        if (record == null) return null

        record.getManufacturerSpecificData(APPLE)?.let { apple ->
            if (apple.size >= 2 && (apple[0].toInt() and 0xFF) == 0x12) {
                // Type 0x12 is Find My. Length 0x19 is the AirTag's own payload size;
                // third-party Find My items use the same type with other lengths.
                return if ((apple[1].toInt() and 0xFF) == 0x19) AIRTAG else FINDMY
            }
        }

        if (record.hasShortUuid(0xFEED)) return TILE
        if (record.hasShortUuid(0xFD5A)) return SMARTTAG
        if (record.hasShortUuid(0xFD70)) return SMARTTAG2
        // 0xFE9F is used by Google Nearby but also Chipolo ONE Spot; fingerprint match only
        if (record.hasShortUuid(0xFEBE) || record.hasShortUuid(0xFE2B)) return CHIPOLO
        // 0xFE2C = Google Fast Pair, 0xFEE7 = Tencent — both previously misassigned to Pebblebee
        // Pebblebee uses proprietary service UUIDs not yet reversed; skip until confirmed
        // 0xFFE0/0xFFF3 are generic HM-10 UART UUIDs used by countless non-Orbit devices
        if (record.hasShortUuid(0xFFF3) && record.hasShortUuid(0xFFE0)) return ORBIT
        if (record.hasShortUuid(0xAA01) || record.hasShortUuid(0xAA02)) return NUT
        if (record.hasShortUuid(0xFEAA)) return EDDYSTONE
        if (record.hasShortUuid(0xFE85)) return INVOXIA

        val name = record.deviceName?.lowercase()
        if (name != null && GPS_NAME_HINTS.any { name.contains(it) }) return GPS_BLE

        return null
    }

    /**
     * Returns a human-readable brand label from the manufacturer-specific data company ID.
     * Used as a fallback when the device name is blank and no tracker signature matched.
     */
    fun companyLabel(record: ScanRecord?): String? {
        if (record == null) return null
        COMPANY_NAMES.forEach { (id, name) ->
            if (record.getManufacturerSpecificData(id) != null) return "$name device"
        }
        return null
    }

    /** Free-space path loss estimate. Rough, and honest about being rough. */
    fun approximateMetres(rssi: Int, txPower: Int?): Double? {
        if (rssi == 0) return null
        val reference = txPower ?: -59
        val ratio = rssi.toDouble() / reference.toDouble()
        val metres = if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
        return if (metres.isFinite() && metres > 0) metres else null
    }
}
