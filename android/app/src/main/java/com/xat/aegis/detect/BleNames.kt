package com.xat.aegis.detect

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.xat.aegis.TrackerType
import java.util.Locale

/**
 * Turns the raw contents of a BLE advertisement into words a person can read.
 *
 * Everything here is display-only. Nothing in this file decides whether a device is
 * a threat — that stays in [Signatures] and the analysis package. This just answers
 * "what is this thing called, who made it, and what is it advertising" using the
 * Bluetooth SIG assigned numbers, so the list shows "Apple [A1:B2] · Nearby Info"
 * rather than a company ID and a 128-bit UUID.
 */
object BleNames {

    // ── Bluetooth SIG company identifiers ────────────────────────────────────
    // https://www.bluetooth.com/specifications/assigned-numbers/ ("Company Identifiers").
    // These are the IDs that show up in manufacturer-specific data (AD type 0xFF).
    private val COMPANIES: Map<Int, String> = mapOf(
        0x0000 to "Ericsson",
        0x0001 to "Nokia",
        0x0002 to "Intel",
        0x0003 to "IBM",
        0x0004 to "Toshiba",
        0x0006 to "Microsoft",
        0x0008 to "Motorola",
        0x0009 to "Infineon",
        0x000A to "Qualcomm (CSR)",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x0013 to "Atmel",
        0x001D to "Qualcomm",
        0x0025 to "NXP",
        0x0030 to "STMicroelectronics",
        0x003A to "Panasonic",
        0x003C to "BlackBerry",
        0x0040 to "Seiko Epson",
        0x0046 to "MediaTek",
        0x0048 to "Marvell",
        0x004C to "Apple",
        0x0055 to "Plantronics",
        0x0057 to "Harman (JBL)",
        0x0058 to "Vizio",
        0x0059 to "Nordic Semiconductor",
        0x005C to "Belkin",
        0x005D to "Realtek",
        0x0065 to "HP",
        0x0067 to "GN Netcom (Jabra)",
        0x006B to "Polar",
        0x0075 to "Samsung",
        0x0076 to "Creative",
        0x0078 to "Nike",
        0x0082 to "Sennheiser",
        0x0087 to "Garmin",
        0x0089 to "GN ReSound",
        0x008A to "Jawbone",
        0x008C to "Gimbal",
        0x009E to "Bose",
        0x009F to "Suunto",
        0x00A0 to "Kensington",
        0x00C4 to "LG Electronics",
        0x00CC to "Beats",
        0x00CD to "Microchip",
        0x00D0 to "Dexcom",
        0x00D2 to "Dialog Semiconductor",
        0x00D9 to "Turtle Beach",
        0x00E0 to "Google",
        0x0100 to "TomTom",
        0x0103 to "Bang & Olufsen",
        0x010E to "Audi",
        0x010F to "HiSilicon",
        0x0111 to "SteelSeries",
        0x0118 to "Radius Networks",
        0x011C to "Baidu",
        0x011F to "Volkswagen",
        0x0120 to "Porsche",
        0x012D to "Sony",
        0x0131 to "Cypress (Infineon)",
        0x0157 to "Huami (Amazfit / Mi Band)",
        0x027D to "Huawei",
        0x02E5 to "Espressif",
        0x038F to "Xiaomi",
        0x046D to "Logitech",
        0x0499 to "Ruuvi",
        0x0553 to "Nintendo",
        0x05A7 to "Sonos",
    )

    /** Human name for a Bluetooth SIG company ID, or null when it is not in the table. */
    fun companyName(id: Int): String? = COMPANIES[id]

    /** Name, or a plain "Company 0x1234" when unknown — never null, never a bare number. */
    fun companyLabel(id: Int): String = COMPANIES[id] ?: "Company 0x%04X".format(id)

    // ── Apple Continuity / Microsoft CDP payload types ───────────────────────
    // The first byte of Apple manufacturer data says what the packet is for. These
    // are the names AirDrop, Nearby and friends actually go by.
    private val APPLE_TYPES: Map<Int, String> = mapOf(
        0x02 to "iBeacon",
        0x03 to "AirPrint",
        0x05 to "AirDrop",
        0x06 to "HomeKit",
        0x07 to "AirPods pairing",
        0x08 to "Hey Siri",
        0x09 to "AirPlay target",
        0x0A to "AirPlay source",
        0x0B to "Watch (Magic Switch)",
        0x0C to "Handoff",
        0x0D to "Tethering target",
        0x0E to "Tethering source",
        0x0F to "Nearby Action",
        0x10 to "Nearby Info",
        0x12 to "Find My",
    )

    private val MICROSOFT_TYPES: Map<Int, String> = mapOf(
        0x01 to "Connected Devices (Nearby Share)",
        0x03 to "Swift Pair",
    )

    // ── 16-bit service UUIDs ─────────────────────────────────────────────────
    // GATT services (0x18xx) and SIG member allocations (0xFDxx/0xFExx).
    private val SERVICES_16: Map<Int, String> = mapOf(
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x1802 to "Immediate Alert",
        0x1803 to "Link Loss",
        0x1804 to "Tx Power",
        0x1805 to "Current Time",
        0x1808 to "Glucose",
        0x1809 to "Health Thermometer",
        0x180A to "Device Information",
        0x180D to "Heart Rate",
        0x180E to "Phone Alert Status",
        0x180F to "Battery",
        0x1810 to "Blood Pressure",
        0x1811 to "Alert Notification",
        0x1812 to "HID (keyboard/mouse)",
        0x1813 to "Scan Parameters",
        0x1814 to "Running Speed & Cadence",
        0x1816 to "Cycling Speed & Cadence",
        0x1818 to "Cycling Power",
        0x1819 to "Location & Navigation",
        0x181A to "Environmental Sensing",
        0x181B to "Body Composition",
        0x181C to "User Data",
        0x181D to "Weight Scale",
        0x181E to "Bond Management",
        0x1820 to "Internet Protocol Support",
        0x1821 to "Indoor Positioning",
        0x1822 to "Pulse Oximeter",
        0x1826 to "Fitness Machine",
        0x1827 to "Mesh Provisioning",
        0x1828 to "Mesh Proxy",
        0x183E to "Physical Activity Monitor",
        0x1843 to "Audio Input Control",
        0x1844 to "Volume Control",
        0x184E to "Audio Stream Control (LE Audio)",
        0x1850 to "Published Audio Capabilities",
        0x1852 to "Broadcast Audio (Auracast)",
        0x1854 to "Hearing Access",
        0x1855 to "Telephony & Media Audio",
        // Member services
        0xFD3D to "SwitchBot",
        0xFD50 to "Tuya smart home",
        0xFD5A to "Samsung SmartThings Find",
        0xFD6F to "Exposure Notification",
        0xFD82 to "Sony headphones",
        0xFD84 to "Tile",
        0xFE03 to "Amazon",
        0xFE07 to "Sonos",
        0xFE0F to "Philips Hue",
        0xFE2C to "Google Fast Pair",
        0xFE33 to "Chipolo",
        0xFE59 to "Nordic DFU",
        0xFE61 to "Logitech",
        0xFE78 to "HP",
        0xFE95 to "Xiaomi Mi Home",
        0xFE9A to "Estimote beacon",
        0xFE9F to "Google Nearby",
        0xFEAA to "Eddystone beacon",
        0xFEAB to "Nokia",
        0xFEB9 to "LG Electronics",
        0xFEBB to "Adafruit",
        0xFEBE to "Bose",
        0xFEC7 to "Apple",
        0xFEC8 to "Apple",
        0xFEC9 to "Apple",
        0xFECA to "Apple",
        0xFED0 to "Apple",
        0xFED1 to "Apple",
        0xFED2 to "Apple",
        0xFED3 to "Apple",
        0xFED4 to "Apple",
        0xFED8 to "Google Physical Web",
        0xFEE0 to "Huami (Mi Band)",
        0xFEE7 to "Tencent",
        0xFEED to "Tile",
        0xFEF3 to "Google",
        0xFEF5 to "Dialog Semiconductor",
        0xFEFF to "GN Netcom (Jabra)",
    )

    // A few 128-bit UUIDs that turn up constantly and are worth naming.
    private val SERVICES_128: Map<String, String> = mapOf(
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to "Nordic UART",
        "7905f431-b5ce-4e99-a40f-4b1e122d00d0" to "Apple Notification Center",
        "89d3502b-0f36-433a-8ef4-c502ad55f8dc" to "Apple Media Service",
        "d0611e78-bbb4-4591-a5f8-487910ae4366" to "Apple Continuity",
        "9fa480e0-4967-4542-9390-d343dc5d04ae" to "Apple Nearby",
    )

    private const val BASE_UUID_TAIL = "-0000-1000-8000-00805f9b34fb"

    /**
     * Name for a service UUID. A 16-bit SIG UUID resolves to its name or "0xFEED";
     * an unknown 128-bit vendor UUID is shortened to its first group, "6E400001…",
     * so the row never fills with a 36-character hex string.
     */
    fun serviceName(uuid: ParcelUuid): String = serviceName(uuid.uuid.toString())

    fun serviceName(uuid: String): String {
        val lower = uuid.lowercase(Locale.US)
        SERVICES_128[lower]?.let { return it }
        if (lower.length == 36 && lower.startsWith("0000") && lower.endsWith(BASE_UUID_TAIL)) {
            val short = lower.substring(4, 8).toIntOrNull(16) ?: return uuid
            SERVICES_16[short]?.let { return it }
            return if (short in 0xFF00..0xFFFF) "Vendor 0x%04X".format(short)
            else "0x%04X".format(short)
        }
        return lower.substring(0, 8).uppercase(Locale.US) + "…"
    }

    // ── Reading a scan record ────────────────────────────────────────────────

    /**
     * Manufacturer of the advertisement, resolved from the first company ID in the
     * manufacturer-specific data. Null when the packet carries none — many devices
     * advertise only service UUIDs.
     */
    fun manufacturer(record: ScanRecord?): String? {
        val mfr = record?.manufacturerSpecificData ?: return null
        if (mfr.size() == 0) return null
        // Prefer a company we can name over one we would have to show as a number.
        for (i in 0 until mfr.size()) COMPANIES[mfr.keyAt(i)]?.let { return it }
        return companyLabel(mfr.keyAt(0))
    }

    /**
     * Everything the advertisement says about what the device does, as short names:
     * Apple/Microsoft payload types first, then service UUIDs from both the UUID list
     * and the service-data map. De-duplicated and in a stable order.
     */
    fun services(record: ScanRecord?): List<String> {
        if (record == null) return emptyList()
        val out = LinkedHashSet<String>()

        record.getManufacturerSpecificData(0x004C)?.let { apple ->
            if (apple.isNotEmpty()) {
                val type = apple[0].toInt() and 0xFF
                out.add(APPLE_TYPES[type] ?: "Apple type 0x%02X".format(type))
            }
        }
        record.getManufacturerSpecificData(0x0006)?.let { ms ->
            if (ms.isNotEmpty()) {
                val type = ms[0].toInt() and 0xFF
                out.add(MICROSOFT_TYPES[type] ?: "Microsoft type 0x%02X".format(type))
            }
        }

        record.serviceUuids?.forEach { out.add(serviceName(it)) }
        record.serviceData?.keys?.forEach { out.add(serviceName(it)) }
        return out.toList()
    }

    /**
     * The name the device put in its own advertisement, cleaned up. Some firmware pads
     * the field with NULs or ships control bytes, which is where "weird names" come
     * from; those are stripped, and a name that is empty after that is no name at all.
     */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.filter { it.code >= 0x20 && it != '�' && !it.isISOControl() }.trim()
        if (cleaned.isEmpty()) return null
        // A "name" that is nothing but hex is a firmware default, not a name.
        if (cleaned.length >= 8 && cleaned.all { it.isLetterOrDigit() } &&
            cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return cleaned
    }

    /**
     * Name from the scan record, then the stack's cached name for the device (bonded or
     * previously resolved). The latter needs BLUETOOTH_CONNECT on Android 12+; without
     * the grant it is skipped rather than allowed to throw inside the scan callback.
     */
    fun advertisedName(context: Context, record: ScanRecord?, device: BluetoothDevice): String? {
        cleanName(record?.deviceName)?.let { return it }
        if (!hasConnect(context)) return null
        return try { cleanName(device.name) } catch (_: SecurityException) { null }
    }

    /** "Classic", "BLE", "Dual" or "Unknown" from [BluetoothDevice.getType]. */
    fun radio(context: Context, device: BluetoothDevice): String {
        if (!hasConnect(context)) return "BLE"
        val type = try { device.type } catch (_: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
        return when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "Unknown"
        }
    }

    /**
     * Whether the address is public or one of the random kinds. Only Android 15 exposes
     * the address type; below that, or when the stack does not know, this is null. A
     * resolvable private address is the one that rotates — the very thing this app
     * has to see through — so it is worth saying out loud.
     */
    fun addressKind(device: BluetoothDevice, address: String): String? {
        if (Build.VERSION.SDK_INT < 35) return null
        val type = try { device.addressType } catch (_: Throwable) { return null }
        return when (type) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "public"
            BluetoothDevice.ADDRESS_TYPE_RANDOM -> {
                val first = address.take(2).toIntOrNull(16) ?: return "random"
                when (first ushr 6) {
                    0b11 -> "random static"
                    0b01 -> "resolvable private (rotates)"
                    0b00 -> "non-resolvable private"
                    else -> "random"
                }
            }
            else -> null
        }
    }

    /** Upper-case, colon-separated, whatever shape the string arrived in. */
    fun formatMac(address: String): String {
        val hex = address.filter { it.isLetterOrDigit() }.uppercase(Locale.US)
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            return address.uppercase(Locale.US)
        }
        return hex.chunked(2).joinToString(":")
    }

    /** Last two octets, "A1:B2", for telling apart several devices from one maker. */
    fun macSuffix(address: String): String {
        val mac = formatMac(address)
        return if (mac.length >= 5) mac.takeLast(5) else mac
    }

    /**
     * What the list shows. Advertised name wins; then the tracker signature label,
     * which carries far more information than a brand for an AirTag; then the
     * manufacturer with the address suffix, "Apple [A1:B2]"; then just the suffix.
     * Never a company ID, never a UUID, never a hex blob.
     */
    fun displayName(
        advertisedName: String?,
        tracker: TrackerType?,
        manufacturer: String?,
        address: String
    ): String {
        advertisedName?.let { return it }
        tracker?.let { return it.label }
        val suffix = macSuffix(address)
        return if (manufacturer != null) "$manufacturer [$suffix]" else "Device [$suffix]"
    }

    /** Plain-English strength word to sit beside the dBm figure. */
    fun signalLabel(rssi: Int): String = when {
        rssi == 0 -> "no reading"
        rssi >= -50 -> "very strong"
        rssi >= -65 -> "strong"
        rssi >= -80 -> "fair"
        rssi >= -90 -> "weak"
        else -> "very weak"
    }

    private fun hasConnect(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
