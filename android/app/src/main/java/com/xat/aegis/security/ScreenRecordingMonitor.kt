package com.xat.aegis.security

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.WindowManager
import com.xat.aegis.Alert
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.ScreenRecordingEvent
import com.xat.aegis.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Notices when an Aegis screen is being recorded or cast.
 *
 * Two sources, neither of which names the recorder to a third-party app:
 *
 * - Android 15's [WindowManager] screen-recording callback, which tells an app
 *   when one of its own windows is inside a recording. It is reached by
 *   reflection: the method exists only from API 35 and needs the (normal)
 *   DETECT_SCREEN_RECORDING permission, and a phone without either simply gets
 *   no callback rather than a crash.
 * - [MediaProjectionManager]'s active-projection query, polled while the
 *   monitor runs. That method is not in the public SDK and stock Android hides
 *   it from apps; the poll tries it once by reflection and stops quietly when
 *   the platform says no, so on a stock S26 Ultra the callback above is the
 *   source that fires. The poll is kept because an OEM or a future release may
 *   open it, and it is the only route that could name the recording package.
 *
 * FLAG_SECURE on the Activity window (set by [com.xat.aegis.MainActivity] when
 * the app lock is on) blanks Aegis out of a recording; this monitor is for
 * telling the owner that one was running at all — stalkerware records the screen
 * to read messages as they are typed.
 */
object ScreenRecordingMonitor {

    /** [WindowManager.SCREEN_RECORDING_STATE_NOT_VISIBLE] / [WindowManager.SCREEN_RECORDING_STATE_VISIBLE], spelled out for API < 35. */
    private const val STATE_NOT_VISIBLE = 0
    private const val STATE_VISIBLE = 1

    private const val PROJECTION_POLL_MS = 15_000L
    /** A recording that runs on is one condition, reported once per this window. */
    private const val DEDUPE_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var registeredOn: WindowManager? = null
    private var callback: Consumer<Int>? = null
    private var pollJob: Job? = null
    /** Whether the projection query answered at least once; false after the platform refused it. */
    @Volatile private var projectionQueryUsable = true
    @Volatile private var projectionSeen = false

    /**
     * Registers for the recording callback on [windowManager] (an Activity's, so
     * it reports on that Activity's windows) and starts the projection poll.
     * Idempotent for the same WindowManager; a different one replaces the
     * earlier registration.
     */
    fun start(context: Context, windowManager: WindowManager) {
        val app = context.applicationContext
        synchronized(lock) {
            if (registeredOn !== windowManager) {
                unregisterLocked()
                registerLocked(app, windowManager)
            }
            if (pollJob?.isActive != true) pollJob = scope.launch { poll(app) }
        }
    }

    /** Unregisters the callback and stops polling. Safe to call when not started. */
    fun stop() {
        synchronized(lock) {
            unregisterLocked()
            pollJob?.cancel()
            pollJob = null
        }
        Registry.setScreenRecordingActive(false)
    }

    private fun registerLocked(app: Context, windowManager: WindowManager) {
        if (Build.VERSION.SDK_INT < 35) return
        val consumer = Consumer<Int> { state -> onState(app, state, null) }
        val initial = try {
            // public int addScreenRecordingCallback(Executor, Consumer<Integer>) — API 35.
            val method = windowManager.javaClass.getMethod(
                "addScreenRecordingCallback", Executor::class.java, Consumer::class.java
            )
            method.invoke(windowManager, app.mainExecutor, consumer) as? Int
        } catch (e: Throwable) {
            // NoSuchMethod (older platform), SecurityException (permission not in
            // the manifest), or an InvocationTargetException wrapping either.
            null
        } ?: return
        registeredOn = windowManager
        callback = consumer
        // The platform reports the state as of registration; a recording already
        // running when the Activity opens counts.
        onState(app, initial, null)
    }

    private fun unregisterLocked() {
        val wm = registeredOn ?: return
        val cb = callback
        registeredOn = null
        callback = null
        if (cb == null) return
        runCatching {
            wm.javaClass.getMethod("removeScreenRecordingCallback", Consumer::class.java).invoke(wm, cb)
        }
    }

    private fun onState(app: Context, state: Int, packageName: String?) {
        when (state) {
            STATE_VISIBLE -> {
                val now = System.currentTimeMillis()
                Registry.setScreenRecordingActive(true)
                Registry.publishScreenRecording(ScreenRecordingEvent(timestamp = now, packageName = packageName))
                Registry.publishAlert(
                    Alert(
                        id = "screen_recording@$now",
                        ts = now,
                        severity = Severity.MEDIUM,
                        title = "The screen is being recorded",
                        detail = (if (packageName != null) "$packageName is capturing the screen" else "Another app is recording or casting the screen") +
                            " while Aegis is showing. If you did not start a recording or a cast, " +
                            "open Settings → Apps and look for anything with screen-capture access; " +
                            "stalkerware records the screen to read messages as they are typed.",
                        kind = EventKind.SCREEN_RECORDING,
                        dedupeKey = "screen_recording"
                    ),
                    dedupeWindowMs = DEDUPE_MS
                )
            }
            STATE_NOT_VISIBLE -> Registry.setScreenRecordingActive(false)
        }
    }

    /**
     * Asks the projection manager for the active projection every
     * [PROJECTION_POLL_MS]. `getActiveProjectionInfo()` is a system API; the
     * first refusal (no method, hidden-API block, SecurityException) ends the poll.
     */
    private suspend fun poll(app: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val mpm = app.getSystemService(MediaProjectionManager::class.java) ?: return
        while (scope.isActive && projectionQueryUsable) {
            val pkg = queryActiveProjection(mpm)
            if (projectionQueryUsable) {
                val active = pkg != null
                if (active && !projectionSeen) {
                    onState(app, STATE_VISIBLE, pkg?.takeIf { it.isNotBlank() })
                } else if (!active && projectionSeen) {
                    // Only clear what the poll itself raised: the window callback
                    // keeps its own say over the "active" flag.
                    if (callback == null) Registry.setScreenRecordingActive(false)
                }
                projectionSeen = active
            }
            delay(PROJECTION_POLL_MS)
        }
    }

    /**
     * The package that holds an active projection, "" when one is active but
     * unnamed, or null when none is. Sets [projectionQueryUsable] false when the
     * platform will not answer.
     */
    private fun queryActiveProjection(mpm: MediaProjectionManager): String? {
        return try {
            val info = mpm.javaClass.getMethod("getActiveProjectionInfo").invoke(mpm) ?: return null
            runCatching { info.javaClass.getMethod("getPackageName").invoke(info) as? String }.getOrNull() ?: ""
        } catch (e: Throwable) {
            projectionQueryUsable = false
            null
        }
    }
}
