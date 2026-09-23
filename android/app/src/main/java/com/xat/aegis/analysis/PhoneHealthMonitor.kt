package com.xat.aegis.analysis

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.xat.aegis.CameraInUse
import com.xat.aegis.PhoneHealthFinding
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import java.util.concurrent.ConcurrentHashMap

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
 *   there is to show — no package name is guessed.
 * - Camera: CameraManager's availability callback. A camera becomes unavailable
 *   when some client opens it; Aegis never opens a camera, so any unavailable
 *   camera is held by another app.
 *
 * Changes are pushed straight into [Registry] as they happen rather than waiting
 * for the service's periodic scan.
 */
class PhoneHealthMonitor(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    /** Camera id → facing, for every camera currently unavailable. */
    private val camerasInUse = ConcurrentHashMap<String, String>()

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            publishRecordings(configs?.size ?: 0)
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse[cameraId] = facingOf(cameraId)
            publishCameras()
        }

        override fun onCameraAvailable(cameraId: String) {
            camerasInUse.remove(cameraId)
            publishCameras()
        }
    }

    fun start() {
        runCatching {
            audioManager?.registerAudioRecordingCallback(recordingCallback, null)
            // The callback only fires on change; pick up anything already recording.
            publishRecordings(audioManager?.activeRecordingConfigurations?.size ?: 0)
        }
        runCatching {
            // Registration immediately reports the current state of every camera.
            cameraManager?.registerAvailabilityCallback(context.mainExecutor, cameraCallback)
        }
    }

    fun stop() {
        runCatching { audioManager?.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
        camerasInUse.clear()
        Registry.updatePhoneHealth { it.copy(activeRecordings = 0, camerasInUse = emptyList()) }
    }

    private fun publishRecordings(count: Int) {
        Registry.updatePhoneHealth { it.copy(activeRecordings = count) }
    }

    private fun publishCameras() {
        val list = camerasInUse.entries
            .map { (id, facing) -> CameraInUse(id, facing.ifEmpty { null }) }
            .sortedWith(compareBy({ it.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.id }))
        Registry.updatePhoneHealth { it.copy(camerasInUse = list) }
    }

    /** "front" / "back" / "external", or "" when the camera does not report it. */
    private fun facingOf(cameraId: String): String = runCatching {
        when (cameraManager?.getCameraCharacteristics(cameraId)?.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> ""
        }
    }.getOrDefault("")

    /**
     * The static findings — accessibility services, device admins, debug settings.
     * Microphone and camera state is published separately as it changes.
     */
    fun scan(): List<PhoneHealthFinding> {
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

        return findings
    }
}
