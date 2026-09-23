package com.xat.aegis.analysis

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.xat.aegis.PhoneHealthFinding
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import java.util.Collections

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
        runCatching { audioManager?.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
        unavailableCameras.clear()
        Registry.updatePhoneHealth { it.copy(activeRecordings = 0, cameraInUse = false) }
    }

    private fun publishRecordings(configs: List<AudioRecordingConfiguration>?) {
        // A silenced client is one Android has muted — a background app, or one that
        // lost the microphone to a higher-priority recorder. It hears nothing, so it
        // is not "an app recording audio".
        val live = configs?.count { !it.isClientSilenced } ?: 0
        Registry.updatePhoneHealth { it.copy(activeRecordings = live) }
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
                    // The id carries the service class: one app can register several
                    // services, and the Device tab keys its list on the id, so two
                    // findings sharing "a11y_<pkg>" crashed it.
                    findings += PhoneHealthFinding(
                        id = "a11y_$pkg/${info.name}",
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
                    id = "admin_${admin.packageName}/${admin.className}",
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

        // Guaranteed unique for the same reason IMSICatcher does it: a duplicate id
        // takes the Device tab down.
        return findings.distinctBy { it.id }
    }

    /** Runs [scan] and publishes the result together with when it was taken. */
    fun scanAndPublish() {
        val findings = scan()
        val now = System.currentTimeMillis()
        // Only the findings: mic and camera state is pushed by the callbacks and
        // must not be overwritten here.
        Registry.updatePhoneHealth { it.copy(findings = findings, findingsScannedTs = now) }
    }
}
