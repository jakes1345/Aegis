package com.xat.aegis.analysis

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.xat.aegis.HealthFact
import com.xat.aegis.PhoneHealthFinding
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Device-level surveillance indicators: who is using the microphone or a camera
 * right now, plus the static findings in [scan].
 *
 * Microphone and camera use are watched through the public APIs that report on
 * *other* apps. The previous AppOpsManager.startWatchingActive route only reports
 * the caller's own UID unless the app holds the system-only WATCH_APPOPS
 * permission, so it never saw anything and the Device tab always read "clear".
 *
 * - Microphone: AudioManager's recording callback. A third-party app is told how
 *   many recordings are active but not which package owns them, so a count is all
 *   there is to show — no package name is guessed. Recordings the system has
 *   silenced are not counted: they receive no audio.
 * - Camera: CameraManager's availability callback. A camera becomes unavailable
 *   when some client opens it; Aegis never opens a camera, so any unavailable
 *   camera is held by another app. Opening one physical camera also takes its
 *   logical and multi-camera siblings out of service, so this is reported as a
 *   single "camera in use", not a count of ids.
 *
 * Changes are pushed straight into [Registry] as they happen rather than waiting
 * for the service's periodic scan.
 */
class PhoneHealthMonitor(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    /** Ids of every camera currently unavailable. */
    private val unavailableCameras: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            publishRecordings(configs)
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            unavailableCameras.add(cameraId)
            publishCameras()
        }

        override fun onCameraAvailable(cameraId: String) {
            unavailableCameras.remove(cameraId)
            publishCameras()
        }
    }

    fun start() {
        active = this
        runCatching {
            audioManager?.registerAudioRecordingCallback(recordingCallback, null)
            // The callback only fires on change; pick up anything already recording.
            publishRecordings(audioManager?.activeRecordingConfigurations)
        }
        runCatching {
            // Registration immediately reports the current state of every camera.
            cameraManager?.registerAvailabilityCallback(context.mainExecutor, cameraCallback)
        }
    }

    fun stop() {
        if (active === this) active = null
        runCatching { audioManager?.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
        unavailableCameras.clear()
        Registry.updatePhoneHealth { it.copy(activeRecordings = 0, cameraInUse = false) }
    }

    private fun publishRecordings(configs: List<AudioRecordingConfiguration>?) {
        // A silenced client is one Android has muted — a background app, or one that
        // lost the microphone to a higher-priority recorder. It hears nothing, so it
        // is not "an app recording audio".
        //
        // The list also includes this app's own recordings — an Aegis call, a voicemail
        // being recorded — which lit the MICROPHONE ACTIVE banner for the user's own
        // call. They are told apart by audio session id: `getClientUid()` is not in
        // the public SDK, and the platform hands a third-party app an anonymised copy
        // with the uid blanked anyway, so the recorders register their sessions here.
        val live = configs?.count { !it.isClientSilenced && it.clientAudioSessionId !in ownSessions } ?: 0
        Registry.updatePhoneHealth { it.copy(activeRecordings = live) }
    }

    /** Re-reads the recording list; for when the set of own sessions has changed. */
    private fun republishRecordings() {
        runCatching { publishRecordings(audioManager?.activeRecordingConfigurations) }
    }

    private fun publishCameras() {
        val inUse = unavailableCameras.isNotEmpty()
        Registry.updatePhoneHealth { it.copy(cameraInUse = inUse) }
    }

    /**
     * The static findings — accessibility services, device admins, debug settings.
     * Microphone and camera state is published separately as it changes.
     *
     * Cheap and service-free, so the Activity runs it too: the Device tab must not
     * say "no issues" merely because the scanner is off and nothing has looked.
     */
    fun scan(): List<PhoneHealthFinding> {
        val findings = mutableListOf<PhoneHealthFinding>()
        val pm = context.packageManager

        // Package visibility filtering hides most other apps' ApplicationInfo from a
        // third-party app, so looking a label up by package name throws
        // NameNotFoundException and the tab showed raw package names. Used only as
        // the last resort below, after the platform-supplied ResolveInfo.
        fun label(pkg: String) = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        // Accessibility services — spyware heavily abuses these
        runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { svc ->
                    val info = svc.resolveInfo.serviceInfo
                    val pkg = info.packageName
                    if (pkg == context.packageName) return@forEach
                    // The ResolveInfo the accessibility manager hands over already
                    // carries the label; no package lookup needed.
                    val name = runCatching { svc.resolveInfo.loadLabel(pm)?.toString() }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: label(pkg)
                    // The id carries the service class: one app can register several
                    // services, and the Device tab keys its list on the id, so two
                    // findings sharing "a11y_<pkg>" crashed it.
                    findings += PhoneHealthFinding(
                        id = "a11y_$pkg/${info.name}",
                        severity = Severity.HIGH,
                        category = "Accessibility",
                        title = "$name — accessibility service active",
                        detail = "Accessibility services can read every word on screen, intercept key presses, " +
                                "perform taps on your behalf, and run in the background indefinitely. " +
                                "Stalkerware almost always registers as one. Check Settings → Accessibility → Installed services."
                    )
                }
        }

        // Device admin — MDM / stalkerware control channel
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.forEach { admin ->
                if (admin.packageName == context.packageName) return@forEach
                val name = adminLabel(admin) ?: label(admin.packageName)
                findings += PhoneHealthFinding(
                    id = "admin_${admin.packageName}/${admin.className}",
                    severity = Severity.HIGH,
                    category = "Device Admin",
                    title = "$name — device admin",
                    detail = "Device admin apps can lock or wipe the device, enforce password policies, " +
                            "and block uninstallation. MDM agents and stalkerware both use this. " +
                            "Revoke from Settings → Security → Device admin apps."
                )
            }
        }

        // Known stalkerware, root managers and hooking frameworks. Package visibility
        // filtering hides other apps from a third-party app unless they are named in
        // the manifest's <queries> block, which is where this list also lives.
        for ((pkg, what) in WATCHED_PACKAGES) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
            if (!installed) continue
            val (severity, category, why) = what
            findings += PhoneHealthFinding(
                id = "pkg_$pkg",
                severity = severity,
                category = category,
                title = "${label(pkg)} is installed",
                detail = why
            )
        }

        // Guaranteed unique for the same reason IMSICatcher does it: a duplicate id
        // takes the Device tab down.
        return findings.distinctBy { it.id }
    }

    /**
     * The security checklist: one row per property of the phone itself that bears on
     * whether it can be trusted. Every read is best-effort — a vendor that blocks a
     * sysfs node or a getprop key gets "Unknown" for that row, not a crash.
     */
    fun facts(): List<HealthFact> {
        val out = ArrayList<HealthFact>(24)
        val cr = context.contentResolver

        // ── Integrity ───────────────────────────────────────────────────────
        val root = rootEvidence()
        out += if (root.isEmpty()) HealthFact("root", "Integrity", "Root access", "Not detected")
        else HealthFact(
            "root", "Integrity", "Root access", "DETECTED", Severity.HIGH,
            "Evidence: ${root.joinToString(", ")}. A rooted phone lets any app that gains root read every " +
                "other app's data, including messages and keys. If you did not root it yourself, treat the device as compromised."
        )

        val vbState = prop("ro.boot.verifiedbootstate")
        val flashLocked = prop("ro.boot.flash.locked")
        val vbmetaState = prop("ro.boot.vbmeta.device_state")
        val unlocked = flashLocked == "0" || vbmetaState == "unlocked" || vbState == "orange"
        out += when {
            unlocked -> HealthFact(
                "bootloader", "Integrity", "Bootloader", "UNLOCKED", Severity.HIGH,
                "An unlocked bootloader lets anyone with the phone in hand flash a modified system or recovery " +
                    "in minutes, and the phone will boot it. Verified boot cannot protect an unlocked device."
            )
            flashLocked == "1" || vbmetaState == "locked" || vbState == "green" -> HealthFact("bootloader", "Integrity", "Bootloader", "Locked")
            else -> HealthFact("bootloader", "Integrity", "Bootloader", "Unknown (${Build.BOOTLOADER})")
        }

        out += when (vbState) {
            "green" -> HealthFact("vb", "Integrity", "Verified boot", "Green — OS signed by manufacturer")
            "yellow" -> HealthFact(
                "vb", "Integrity", "Verified boot", "Yellow — custom signing key", Severity.MEDIUM,
                "The system was verified against a key that is not the manufacturer's. Expected on a custom ROM you installed; otherwise a warning sign."
            )
            "orange" -> HealthFact(
                "vb", "Integrity", "Verified boot", "Orange — unverified", Severity.HIGH,
                "The bootloader is not verifying the system at all. Anything could be running underneath Android."
            )
            "red" -> HealthFact("vb", "Integrity", "Verified boot", "Red — verification FAILED", Severity.CRITICAL,
                "The system image failed verification. The phone should not have booted; do not trust it.")
            null, "" -> HealthFact("vb", "Integrity", "Verified boot", "Not reported")
            else -> HealthFact("vb", "Integrity", "Verified boot", vbState)
        }

        val selinux = selinuxMode()
        out += when (selinux) {
            "Enforcing" -> HealthFact("selinux", "Integrity", "SELinux", "Enforcing")
            "Permissive" -> HealthFact("selinux", "Integrity", "SELinux", "PERMISSIVE", Severity.HIGH,
                "Mandatory access control is switched off. Every app sandbox boundary is advisory only.")
            "Disabled" -> HealthFact("selinux", "Integrity", "SELinux", "DISABLED", Severity.HIGH,
                "The kernel is running without SELinux. This is not a shipping configuration.")
            else -> HealthFact("selinux", "Integrity", "SELinux", "Unknown")
        }

        val tags = Build.TAGS ?: ""
        out += if (tags.contains("test-keys")) HealthFact(
            "buildkeys", "Integrity", "Build signature", "test-keys", Severity.MEDIUM,
            "The OS was signed with public AOSP test keys. Anyone can sign an update that this phone will accept."
        ) else HealthFact("buildkeys", "Integrity", "Build signature", if (tags.isBlank()) "Unknown" else tags)

        out += HealthFact(
            "integrity_api", "Integrity", "Play Integrity",
            "Not verifiable offline",
            detail = "Play Integrity and SafetyNet are attested by Google's servers, not on the device. Bootloader, verified boot, root and SELinux above are the local checks it would make."
        )

        // ── Access ──────────────────────────────────────────────────────────
        val kg = context.getSystemService(KeyguardManager::class.java)
        val secure = runCatching { kg?.isDeviceSecure }.getOrNull()
        out += when (secure) {
            true -> HealthFact("lock", "Access", "Screen lock", "PIN / pattern / password set")
            false -> HealthFact("lock", "Access", "Screen lock", "NONE", Severity.MEDIUM,
                "Anyone who picks the phone up has everything on it. Set a PIN in Settings → Security.")
            null -> HealthFact("lock", "Access", "Screen lock", "Unknown")
        }

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val enc = runCatching { dpm?.storageEncryptionStatus }.getOrNull()
        out += when (enc) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> HealthFact("enc", "Access", "Storage encryption", "Active")
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> HealthFact(
                "enc", "Access", "Storage encryption", "Default key only", Severity.MEDIUM,
                "Storage is encrypted with a key that is not tied to your screen lock, so it protects nothing against someone holding the phone."
            )
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> HealthFact("enc", "Access", "Storage encryption", "INACTIVE", Severity.HIGH,
                "The phone's storage is not encrypted. Data can be read off the flash directly.")
            DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> HealthFact("enc", "Access", "Storage encryption", "Unsupported", Severity.HIGH)
            else -> HealthFact("enc", "Access", "Storage encryption", "Unknown")
        }

        val adb = settingOn(Settings.Global.ADB_ENABLED)
        out += if (adb) HealthFact(
            "adb", "Access", "USB debugging", "ON", Severity.MEDIUM,
            "Any computer this phone is plugged into can be authorised for a full shell. Turn off when not developing: Settings → Developer options → USB debugging."
        ) else HealthFact("adb", "Access", "USB debugging", "Off")

        val adbWifi = settingOn("adb_wifi_enabled")
        out += if (adbWifi) HealthFact(
            "adb_wifi", "Access", "Wireless debugging", "ON", Severity.MEDIUM,
            "A debugging shell is reachable over the local Wi-Fi network, not just by cable. Settings → Developer options → Wireless debugging."
        ) else HealthFact("adb_wifi", "Access", "Wireless debugging", "Off")

        val dev = settingOn(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        out += if (dev) HealthFact(
            "dev", "Access", "Developer options", "Enabled", Severity.LOW,
            "Exposes mock locations, process inspection and debugging interfaces. Turn off in Settings → Developer options unless you need it."
        ) else HealthFact("dev", "Access", "Developer options", "Off")

        val unknownSources = runCatching {
            Settings.Secure.getInt(cr, "install_non_market_apps", 0) != 0
        }.getOrDefault(false)
        if (unknownSources) out += HealthFact(
            "sideload", "Access", "Unknown sources", "Allowed globally", Severity.LOW,
            "Apps can be installed from outside an app store without a per-app prompt."
        )

        // ── Software ────────────────────────────────────────────────────────
        out += HealthFact("android", "Software", "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val patch = Build.VERSION.SECURITY_PATCH
        val patchAge = patchAgeDays(patch)
        out += when {
            patchAge == null -> HealthFact("patch", "Software", "Security patch", patch ?: "Unknown")
            patchAge > 365 -> HealthFact("patch", "Software", "Security patch", "$patch (${patchAge / 30} months old)", Severity.HIGH,
                "More than a year of published, weaponised vulnerabilities apply to this phone. Commercial spyware routinely uses them.")
            patchAge > 120 -> HealthFact("patch", "Software", "Security patch", "$patch (${patchAge / 30} months old)", Severity.MEDIUM,
                "Several months of published fixes are missing. Check for a system update.")
            else -> HealthFact("patch", "Software", "Security patch", patch)
        }
        out += HealthFact("model", "Software", "Device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        out += HealthFact("build", "Software", "Build", Build.DISPLAY ?: Build.ID)

        // ── Network ─────────────────────────────────────────────────────────
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        out += HealthFact("vpn", "Network", "VPN", if (vpn) "Active" else "Not active")
        val link = runCatching { cm?.activeNetwork?.let { cm.getLinkProperties(it) } }.getOrNull()
        val privateDns = link?.isPrivateDnsActive == true
        out += HealthFact("pdns", "Network", "Private DNS", if (privateDns) (link?.privateDnsServerName ?: "Automatic") else "Off")
        val alwaysOnVpn = runCatching { Settings.Secure.getString(cr, "always_on_vpn_app") }.getOrNull()
        if (!alwaysOnVpn.isNullOrBlank()) out += HealthFact("vpn_always", "Network", "Always-on VPN", alwaysOnVpn)

        // ── Hardware ────────────────────────────────────────────────────────
        out += batteryFacts()
        out += cpuFacts()

        return out.distinctBy { it.id }
    }

    // ── Probes ───────────────────────────────────────────────────────────────

    /**
     * The label of a device admin through [DeviceAdminInfo], which resolves the
     * receiver component itself. Admin receivers answer DEVICE_ADMIN_ENABLED, and
     * that intent is declared in the manifest's <queries>, so the component is
     * visible even when the package's ApplicationInfo is not. Null when the
     * platform still refuses, and the caller falls back to the package name.
     */
    private fun adminLabel(admin: ComponentName): String? = runCatching {
        val pm = context.packageManager
        val receiver = pm.getReceiverInfo(admin, PackageManager.GET_META_DATA)
        val resolve = ResolveInfo().apply { activityInfo = receiver }
        DeviceAdminInfo(context, resolve).loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun settingOn(name: String): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, name, 0) != 0 }.getOrDefault(false)

    /** `getprop` is the only unprivileged route to boot properties; a missing key reads "". */
    private fun prop(key: String): String? = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor(1, TimeUnit.SECONDS)
        p.destroy()
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun rootEvidence(): List<String> {
        val evidence = ArrayList<String>(4)
        val su = SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        if (su.isNotEmpty()) evidence += "su binary at ${su.first()}"
        val magisk = MAGISK_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        if (magisk.isNotEmpty()) evidence += "Magisk files at ${magisk.first()}"
        if (prop("ro.debuggable") == "1") evidence += "ro.debuggable=1"
        if (prop("ro.secure") == "0") evidence += "ro.secure=0"
        val which = runCatching {
            val p = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(1, TimeUnit.SECONDS); p.destroy()
            out
        }.getOrNull()
        if (!which.isNullOrBlank() && which.startsWith("/") && su.isEmpty()) evidence += "su on PATH ($which)"
        for (pkg in ROOT_MANAGER_PACKAGES) {
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)) {
                evidence += "root manager $pkg"
                break
            }
        }
        return evidence
    }

    private fun selinuxMode(): String? {
        runCatching {
            val f = File("/sys/fs/selinux/enforce")
            if (f.canRead()) return if (f.readText().trim() == "1") "Enforcing" else "Permissive"
        }
        return runCatching {
            val p = ProcessBuilder("getenforce").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(1, TimeUnit.SECONDS); p.destroy()
            out.takeIf { it == "Enforcing" || it == "Permissive" || it == "Disabled" }
        }.getOrNull()
    }

    private fun patchAgeDays(patch: String?): Long? {
        if (patch.isNullOrBlank()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val date = runCatching { fmt.parse(patch) }.getOrNull() ?: return null
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - date.time)
    }

    private fun batteryFacts(): List<HealthFact> {
        val out = ArrayList<HealthFact>(3)
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val pct = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
            ?.takeIf { it in 0..100 }
            ?: sticky?.let { i ->
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) level * 100 / scale else null
            }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> " via USB"
            BatteryManager.BATTERY_PLUGGED_AC -> " via mains"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " wirelessly"
            else -> ""
        }
        val tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val mv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val levelText = buildString {
            append(pct?.let { "$it%" } ?: "Unknown")
            if (charging) append(" · charging$source")
            else if (plugged != 0) append(" · plugged in$source")
            tempC?.let { append(" · %.1f°C".format(Locale.US, it)) }
        }
        out += HealthFact("battery", "Hardware", "Battery", levelText)

        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        out += when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> HealthFact("battery_health", "Hardware", "Battery health", "Good" + (mv?.let { " · ${it} mV" } ?: ""))
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> HealthFact("battery_health", "Hardware", "Battery health", "Overheating", Severity.MEDIUM,
                "Sustained heat with the screen off can mean something is running the radios or CPU hard in the background.")
            BatteryManager.BATTERY_HEALTH_DEAD -> HealthFact("battery_health", "Hardware", "Battery health", "Dead", Severity.LOW)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> HealthFact("battery_health", "Hardware", "Battery health", "Over voltage", Severity.LOW)
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> HealthFact("battery_health", "Hardware", "Battery health", "Failure", Severity.LOW)
            BatteryManager.BATTERY_HEALTH_COLD -> HealthFact("battery_health", "Hardware", "Battery health", "Cold")
            else -> HealthFact("battery_health", "Hardware", "Battery health", "Unknown")
        }
        // USB debugging on and a cable attached is the moment a shell is actually reachable.
        if (plugged == BatteryManager.BATTERY_PLUGGED_USB && settingOn(Settings.Global.ADB_ENABLED)) {
            out += HealthFact("usb_now", "Access", "USB connection", "Connected with debugging on", Severity.MEDIUM,
                "A USB host is attached right now and ADB is enabled. If this is not your own computer, unplug.")
        }
        return out
    }

    private fun cpuFacts(): List<HealthFact> {
        val out = ArrayList<HealthFact>(2)
        val cores = Runtime.getRuntime().availableProcessors()
        val governor = readSys("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        val curKhz = readSys("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")?.toLongOrNull()
        val maxKhz = readSys("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
        val freq = when {
            curKhz != null && maxKhz != null -> " · %.2f / %.2f GHz".format(Locale.US, curKhz / 1e6, maxKhz / 1e6)
            maxKhz != null -> " · up to %.2f GHz".format(Locale.US, maxKhz / 1e6)
            else -> ""
        }
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
        out += HealthFact("cpu", "Hardware", "CPU", "$cores cores · $abi$freq")
        out += when (governor) {
            null -> HealthFact("governor", "Hardware", "CPU governor", "Not readable (vendor restricts /sys)")
            "performance" -> HealthFact("governor", "Hardware", "CPU governor", governor, Severity.LOW,
                "The CPU is pinned at full speed. Stock firmware does not ship this way; a tweak or a modified kernel set it.")
            else -> HealthFact("governor", "Hardware", "CPU governor", governor)
        }
        val kernel = System.getProperty("os.version")
        if (!kernel.isNullOrBlank()) out += HealthFact("kernel", "Software", "Kernel", kernel)
        return out
    }

    private fun readSys(path: String): String? = runCatching {
        val f = File(path)
        if (f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    /** Runs [scan] and [facts] and publishes both together with when they were taken. */
    fun scanAndPublish() {
        val findings = scan()
        val facts = runCatching { facts() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        // Only the findings: mic and camera state is pushed by the callbacks and
        // must not be overwritten here.
        Registry.updatePhoneHealth { it.copy(findings = findings, facts = facts, findingsScannedTs = now) }
    }

    companion object {
        /** The monitor currently registered for callbacks, if the service is running. */
        @Volatile
        private var active: PhoneHealthMonitor? = null

        /**
         * Audio session ids of recordings this app is making itself, so they are not
         * counted as another app listening. Recorders call [ownRecordingStarted] once
         * their capture is running and [ownRecordingStopped] when it ends.
         */
        private val ownSessions: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        fun ownRecordingStarted(sessionId: Int) {
            ownSessions.add(sessionId)
            // The platform's callback may already have fired with this session in the
            // list, counted as foreign; recount now that it is known to be ours.
            active?.republishRecordings()
        }

        fun ownRecordingStopped(sessionId: Int) {
            ownSessions.remove(sessionId)
            active?.republishRecordings()
        }

        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/odm/bin/su", "/vendor/bin/su",
            "/product/bin/su", "/system_ext/bin/su", "/apex/com.android.runtime/bin/su"
        )
        private val MAGISK_PATHS = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.db", "/data/adb/modules",
            "/cache/.disable_magisk", "/dev/.magisk.unblock", "/data/adb/ksu", "/data/adb/ap"
        )
        val ROOT_MANAGER_PACKAGES = listOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "me.bmax.apatch", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.noshufou.android.su", "com.thirdparty.superuser",
            "com.kingroot.kinguser", "com.kingo.root", "com.zachspong.temprootremovejb"
        )

        /**
         * Packages worth reporting when present. Each entry must also appear in the
         * manifest's <queries> block or the platform hides it from getPackageInfo.
         */
        val WATCHED_PACKAGES: Map<String, Triple<Severity, String, String>> = linkedMapOf(
            // Hooking / instrumentation frameworks: capable of altering any app's behaviour.
            "de.robv.android.xposed.installer" to Triple(Severity.HIGH, "Tampering", "Xposed hooks into every app's code at runtime."),
            "org.meowcat.edxposed.manager" to Triple(Severity.HIGH, "Tampering", "EdXposed hooks into every app's code at runtime."),
            "org.lsposed.manager" to Triple(Severity.HIGH, "Tampering", "LSPosed hooks into every app's code at runtime."),
            "com.saurik.substrate" to Triple(Severity.HIGH, "Tampering", "Cydia Substrate hooks into every app's code at runtime."),
            "re.frida.server" to Triple(Severity.HIGH, "Tampering", "Frida instruments running processes."),
            // Root managers are reported through the root row; listed here for the <queries> block.
            "com.topjohnwu.magisk" to Triple(Severity.MEDIUM, "Root", "Magisk manages root access on this phone."),
            "me.weishu.kernelsu" to Triple(Severity.MEDIUM, "Root", "KernelSU manages root access on this phone."),
            "me.bmax.apatch" to Triple(Severity.MEDIUM, "Root", "APatch manages root access on this phone."),
            "eu.chainfire.supersu" to Triple(Severity.MEDIUM, "Root", "SuperSU manages root access on this phone."),
            // Commercial stalkerware, by the package names they ship under.
            "com.mspy.lite" to Triple(Severity.CRITICAL, "Stalkerware", "mSpy records messages, calls, location and keystrokes and uploads them to whoever installed it."),
            "com.mspy.android" to Triple(Severity.CRITICAL, "Stalkerware", "mSpy records messages, calls, location and keystrokes and uploads them to whoever installed it."),
            "com.flexispy.android" to Triple(Severity.CRITICAL, "Stalkerware", "FlexiSPY intercepts calls, messages and the microphone."),
            "com.hellospy.system" to Triple(Severity.CRITICAL, "Stalkerware", "HelloSpy monitors messages, calls and location."),
            "com.spyzie.android" to Triple(Severity.CRITICAL, "Stalkerware", "Spyzie monitors messages, calls and location."),
            "com.cocospy.android" to Triple(Severity.CRITICAL, "Stalkerware", "Cocospy monitors messages, calls and location."),
            "com.spyic.android" to Triple(Severity.CRITICAL, "Stalkerware", "Spyic monitors messages, calls and location."),
            "com.hoverwatch.android" to Triple(Severity.CRITICAL, "Stalkerware", "Hoverwatch records calls, messages and the camera."),
            "com.thetruthspy.android" to Triple(Severity.CRITICAL, "Stalkerware", "TheTruthSpy monitors messages, calls and location."),
            "com.spyhuman.android" to Triple(Severity.CRITICAL, "Stalkerware", "SpyHuman monitors messages, calls and location."),
            "com.xnspy.android" to Triple(Severity.CRITICAL, "Stalkerware", "XNSPY monitors messages, calls and location."),
            "com.mobistealth.android" to Triple(Severity.CRITICAL, "Stalkerware", "Mobistealth monitors messages, calls and location."),
            "com.ikeymonitor.android" to Triple(Severity.CRITICAL, "Stalkerware", "iKeyMonitor logs keystrokes and screenshots."),
            "com.cerberusapp" to Triple(Severity.HIGH, "Remote control", "Cerberus is anti-theft software that also allows remote camera, microphone and location access."),
            "com.lsdroid.cerberus" to Triple(Severity.HIGH, "Remote control", "Cerberus is anti-theft software that also allows remote camera, microphone and location access."),
            "com.spy2mobile.light" to Triple(Severity.CRITICAL, "Stalkerware", "Spy2Mobile monitors messages, calls and location."),
            "com.mxspy" to Triple(Severity.CRITICAL, "Stalkerware", "mxspy monitors messages, calls and location."),
            "com.pcTattletale" to Triple(Severity.CRITICAL, "Stalkerware", "pcTattletale records the screen continuously."),
            "com.retinax.mobilespy" to Triple(Severity.CRITICAL, "Stalkerware", "Mobile Spy monitors messages, calls and location.")
        )
    }
}
