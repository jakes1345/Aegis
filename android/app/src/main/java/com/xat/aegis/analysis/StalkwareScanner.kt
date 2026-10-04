package com.xat.aegis.analysis

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.xat.aegis.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Finds apps on this phone that look like stalkerware: installed from outside a
 * store, hiding without a launcher icon, holding the permissions a monitoring
 * app needs (accessibility, overlays, SMS, call log), or running a foreground
 * service without ever showing a screen. Each signal adds to a score; the
 * score, not any one signal, decides whether an app is reported.
 *
 * Also lists the special-access grants that are not runtime permissions and so
 * escape a permission audit: notification listeners (every notification,
 * including one-time codes, before the owner sees it), enabled accessibility
 * services (every tap and every screen), and apps that can act as the VPN
 * (every byte of traffic).
 *
 * Everything here is public API on stock Android. Two grants widen the view:
 * QUERY_ALL_PACKAGES (manifest; otherwise Android 11+ hides most apps) and
 * usage access (Settings → Usage access; otherwise the hidden-service signal
 * is unavailable and reported as such).
 */
object StalkwareScanner {

    data class SuspiciousApp(
        val packageName: String,
        val appLabel: String,
        val score: Int,
        val reasons: List<String>,
        val installTs: Long,
        val lastUpdateTs: Long,
        val sideloaded: Boolean,
        val hasLauncherIcon: Boolean,
        val vpnCapable: Boolean,
        val notificationListener: Boolean,
        val accessibilityEnabled: Boolean,
        val installerPackage: String?
    ) {
        val severity: Severity
            get() = when {
                score >= HIGH_SCORE -> Severity.HIGH
                score >= MEDIUM_SCORE -> Severity.MEDIUM
                else -> Severity.LOW
            }
    }

    data class Result(
        val apps: List<SuspiciousApp>,
        /** Every app granted notification access, known-good ones included. */
        val notificationListeners: List<String>,
        /** Every enabled accessibility service, known-good ones included. */
        val accessibilityServices: List<String>,
        /** Every app that can act as the device VPN. */
        val vpnApps: List<String>,
        val usageAccessGranted: Boolean,
        val scannedAt: Long
    ) {
        val high: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.HIGH }
        val medium: List<SuspiciousApp> get() = apps.filter { it.severity == Severity.MEDIUM }
    }

    private const val HIGH_SCORE = 5
    private const val MEDIUM_SCORE = 3
    private const val USAGE_WINDOW_MS = 7 * 24 * 3600_000L

    private val _results = MutableStateFlow<Result?>(null)
    /** The last scan, for the Device tab. */
    val results: StateFlow<Result?> = _results.asStateFlow()

    /** Installers that are stores; anything else (or nothing) is a sideload. */
    private val STORES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.samsung.android.galaxystore",
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.aurora.store",
        "com.amazon.venezia",
        "com.huawei.appmarket"
    )

    /** Notification listeners that belong on a stock Samsung or Pixel. */
    private val KNOWN_LISTENERS = setOf(
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.wearables.maestro.companion",
        "com.google.android.projection.gearhead",
        "com.google.android.wearable.app",
        "com.google.android.as",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.wearable.watchmanager",
        "com.samsung.android.app.routines",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.app.reminder",
        "com.samsung.android.mdx",
        "com.samsung.android.oneconnect",
        "com.samsung.android.app.spage",
        "com.samsung.android.smartmirroring",
        "com.sec.android.app.shealth",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.lool",
        "com.samsung.accessibility",
        "com.sec.android.app.launcher",
        "com.samsung.android.forest",
        "com.android.car.notification",
        "org.thoughtcrime.securesms",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.discord",
        "com.slack",
        "com.microsoft.teams",
        "com.fitbit.FitbitMobile",
        "com.garmin.android.apps.connectmobile",
        "com.wunderkinder.wunderlistandroid",
        "com.google.android.apps.messaging",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.chromecast.app",
        "com.google.android.apps.nbu.files",
        "com.google.android.deskclock",
        "com.sonos.acr",
        "com.spotify.music",
        "com.plexapp.android",
        "com.gopro.smarty",
        "com.joaomgcd.autonotification",
        "net.dinglisch.android.taskerm",
        "com.oneplus.gamespace",
        "com.xat.aegis"
    )

    /** Accessibility services that belong on a stock Samsung or Pixel. */
    private val KNOWN_ACCESSIBILITY = setOf(
        "com.samsung.accessibility",
        "com.samsung.android.accessibility.talkback",
        "com.google.android.marvin.talkback",
        "com.google.android.accessibility.soundamplifier",
        "com.google.android.apps.accessibility.voiceaccess",
        "com.google.android.accessibility.switchaccess",
        "com.google.android.apps.accessibility.reveal",
        "com.samsung.android.app.talkback",
        "com.samsung.android.universalswitch",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.app.routines",
        "com.android.switchaccess",
        "com.samsung.android.smartmirroring",
        "com.samsung.android.app.smartcapture",
        "com.sec.android.app.launcher",
        "com.bitwarden",
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.agilebits.onepassword",
        "com.onepassword.android",
        "keepass2android.keepass2android",
        "org.mozilla.firefox",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "net.dinglisch.android.taskerm",
        "com.joaomgcd.autoinput",
        "com.greenify",
        "com.xat.aegis"
    )

    private val SUSPECT_PERMISSIONS = linkedMapOf(
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE to (2 to "declares an accessibility service (reads every screen, every tap)"),
        Manifest.permission.READ_SMS to (2 to "can read SMS (including one-time codes)"),
        Manifest.permission.RECEIVE_SMS to (1 to "receives SMS as they arrive"),
        Manifest.permission.READ_CALL_LOG to (2 to "can read the call log"),
        Manifest.permission.PROCESS_OUTGOING_CALLS to (1 to "sees outgoing calls as they are placed"),
        Manifest.permission.SYSTEM_ALERT_WINDOW to (1 to "can draw over other apps"),
        Manifest.permission.RECORD_AUDIO to (1 to "can record audio"),
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to (1 to "can read location in the background"),
        Manifest.permission.BIND_DEVICE_ADMIN to (1 to "declares a device admin (can resist uninstall)"),
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE to (1 to "declares a notification listener"),
        "android.permission.BIND_VPN_SERVICE" to (0 to "declares a VPN service")
    )

    /** Whether the hidden-service signal is available: Settings → Usage access → Aegis. */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Opens the system's own details page for [packageName], where Uninstall lives. */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))

    /** Runs the scan as a flow; the single emission is the ranked list. */
    fun scanFlow(context: Context): Flow<List<SuspiciousApp>> = flow { emit(scan(context).apps) }

    /**
     * Enumerates every installed package and scores it. Some hundreds of
     * milliseconds on a phone with a few hundred apps; call off the main thread.
     */
    fun scan(context: Context): Result {
        val app = context.applicationContext
        val pm = app.packageManager
        val self = app.packageName

        val listeners = enabledComponents(app, "enabled_notification_listeners")
        val accessibility = enabledComponents(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listenerPkgs = listeners.map { it.packageName }.toSet()
        val accessibilityPkgs = accessibility.map { it.packageName }.toSet()
        val vpnPkgs = vpnCapablePackages(pm)
        val launcherPkgs = launcherPackages(pm)

        val usageAccess = hasUsageAccess(app)
        val (fgsPkgs, resumedPkgs) = if (usageAccess) usageSignals(app) else emptySet<String>() to emptySet()

        val flags = PackageManager.GET_PERMISSIONS
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        val found = ArrayList<SuspiciousApp>()
        for (pkg in packages) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            if (name == self) continue
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val listener = name in listenerPkgs
            val a11y = name in accessibilityPkgs
            val vpn = name in vpnPkgs
            // A preloaded app is not something an abuser installed; it is only
            // interesting when it holds a special-access grant that is not
            // part of the stock image.
            if (system) {
                val oddListener = listener && name !in KNOWN_LISTENERS
                val oddA11y = a11y && name !in KNOWN_ACCESSIBILITY
                if (!oddListener && !oddA11y) continue
            }

            val reasons = ArrayList<String>()
            var score = 0

            val installer = installerOf(pm, name)
            val sideloaded = !system && (installer == null || installer !in STORES)
            if (sideloaded) {
                score += 3
                reasons += when (installer) {
                    null, "com.android.shell" -> "installed from a computer (adb) or an unknown source"
                    "com.android.packageinstaller", "com.google.android.packageinstaller",
                    "com.samsung.android.packageinstaller" -> "installed from an APK file, not a store"
                    else -> "installed by $installer, not a store"
                }
            }

            val hasLauncher = name in launcherPkgs
            if (!hasLauncher) {
                score += 2
                reasons += "has no icon in the app drawer"
            }

            val requested = pkg.requestedPermissions?.toSet() ?: emptySet()
            for ((perm, weight) in SUSPECT_PERMISSIONS) {
                if (perm in requested) {
                    score += weight.first
                    reasons += weight.second
                }
            }

            if (a11y) {
                if (name !in KNOWN_ACCESSIBILITY) {
                    score += 3
                    reasons += "accessibility service is switched ON"
                } else {
                    reasons += "accessibility service is on (a known app)"
                }
            }
            if (listener) {
                if (name !in KNOWN_LISTENERS) {
                    score += 3
                    reasons += "notification access is switched ON (reads every notification)"
                } else {
                    reasons += "notification access is on (a known app)"
                }
            }
            if (vpn) {
                score += if (sideloaded || !hasLauncher) 2 else 1
                reasons += "can act as the VPN and see all traffic"
            }

            if (usageAccess && name in fgsPkgs && name !in resumedPkgs) {
                score += 2
                reasons += "ran a background service this week without ever showing a screen"
            }

            if (score < MEDIUM_SCORE) continue
            found += SuspiciousApp(
                packageName = name,
                appLabel = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(name),
                score = score,
                reasons = reasons,
                installTs = pkg.firstInstallTime,
                lastUpdateTs = pkg.lastUpdateTime,
                sideloaded = sideloaded,
                hasLauncherIcon = hasLauncher,
                vpnCapable = vpn,
                notificationListener = listener,
                accessibilityEnabled = a11y,
                installerPackage = installer
            )
        }

        val result = Result(
            apps = found.sortedWith(compareByDescending<SuspiciousApp> { it.score }.thenByDescending { it.installTs }),
            notificationListeners = listenerPkgs.sorted(),
            accessibilityServices = accessibilityPkgs.sorted(),
            vpnApps = vpnPkgs.sorted(),
            usageAccessGranted = usageAccess,
            scannedAt = System.currentTimeMillis()
        )
        _results.value = result
        return result
    }

    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val src = pm.getInstallSourceInfo(packageName)
            src.installingPackageName ?: src.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    /** Packages with at least one activity in the launcher. */
    private fun launcherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return list.mapTo(HashSet()) { it.activityInfo.packageName }
    }

    /** Packages declaring a service that binds as android.net.VpnService. */
    private fun vpnCapablePackages(pm: PackageManager): Set<String> {
        val intent = Intent("android.net.VpnService")
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
        return list.mapTo(HashSet()) { it.serviceInfo.packageName }
    }

    /** The ':'-separated component list under a Settings.Secure key, as components. */
    private fun enabledComponents(context: Context, key: String): List<ComponentName> {
        val raw = Settings.Secure.getString(context.contentResolver, key) ?: return emptyList()
        return raw.split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) }
    }

    /**
     * Over the last week: packages that started a foreground service, and
     * packages that brought an activity to the front. A member of the first set
     * and not the second runs without ever being seen.
     */
    private fun usageSignals(context: Context): Pair<Set<String>, Set<String>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - USAGE_WINDOW_MS, now)
        val fgs = HashSet<String>()
        val resumed = HashSet<String>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> fgs += pkg
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed += pkg
            }
        }
        return fgs to resumed
    }
}
