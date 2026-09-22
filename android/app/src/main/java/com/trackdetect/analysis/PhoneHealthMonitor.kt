package com.trackdetect.analysis

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.trackdetect.PhoneHealth
import com.trackdetect.PhoneHealthFinding
import com.trackdetect.Severity
import java.util.concurrent.ConcurrentHashMap

class PhoneHealthMonitor(private val context: Context) {

    private val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val micActive = ConcurrentHashMap<String, Unit>()
    private val cameraActive = ConcurrentHashMap<String, Unit>()

    private val opListener = AppOpsManager.OnOpActiveChangedListener { op, _, packageName, active ->
        when (op) {
            AppOpsManager.OPSTR_RECORD_AUDIO ->
                if (active) micActive[packageName] = Unit else micActive.remove(packageName)
            AppOpsManager.OPSTR_CAMERA ->
                if (active) cameraActive[packageName] = Unit else cameraActive.remove(packageName)
        }
    }

    fun start() {
        runCatching {
            appOps.startWatchingActive(
                arrayOf(AppOpsManager.OPSTR_RECORD_AUDIO, AppOpsManager.OPSTR_CAMERA),
                context.mainExecutor,
                opListener
            )
        }
    }

    fun stop() {
        runCatching { appOps.stopWatchingActive(opListener) }
    }

    fun scan(): PhoneHealth {
        val findings = mutableListOf<PhoneHealthFinding>()
        val pm = context.packageManager

        fun label(pkg: String) = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        // Accessibility services — spyware heavily abuses these
        runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { svc ->
                    val pkg = svc.resolveInfo.serviceInfo.packageName
                    if (pkg == context.packageName) return@forEach
                    findings += PhoneHealthFinding(
                        id = "a11y_$pkg",
                        severity = Severity.HIGH,
                        category = "Accessibility",
                        title = "${label(pkg)} — accessibility service active",
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
                findings += PhoneHealthFinding(
                    id = "admin_${admin.packageName}",
                    severity = Severity.HIGH,
                    category = "Device Admin",
                    title = "${label(admin.packageName)} — device admin",
                    detail = "Device admin apps can lock or wipe the device, enforce password policies, " +
                            "and block uninstallation. MDM agents and stalkerware both use this. " +
                            "Revoke from Settings → Security → Device admin apps."
                )
            }
        }

        // USB debugging (ADB)
        runCatching {
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0) {
                findings += PhoneHealthFinding(
                    id = "adb_enabled",
                    severity = Severity.MEDIUM,
                    category = "Debug",
                    title = "USB debugging (ADB) is on",
                    detail = "Any computer connected via USB gets a full shell. Turn off when you are not developing: " +
                            "Settings → Developer options → USB debugging."
                )
            }
        }

        // Developer options
        runCatching {
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
                findings += PhoneHealthFinding(
                    id = "dev_options",
                    severity = Severity.LOW,
                    category = "Debug",
                    title = "Developer options enabled",
                    detail = "Exposes interfaces that weaken normal security boundaries (mock locations, " +
                            "process stats, layout inspection). Turn off from Settings → Developer options."
                )
            }
        }

        return PhoneHealth(
            findings = findings,
            activeMic = micActive.keys.toList(),
            activeCamera = cameraActive.keys.toList()
        )
    }
}
