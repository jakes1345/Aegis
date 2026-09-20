package com.trackdetect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trackdetect.analysis.CellMonitor
import com.trackdetect.analysis.EventLog
import com.trackdetect.analysis.Fingerprint
import com.trackdetect.analysis.IMSICatcher
import com.trackdetect.analysis.IdentityResolver
import com.trackdetect.analysis.LocationTrack
import com.trackdetect.analysis.Tracker
import com.trackdetect.analysis.toFix
import com.trackdetect.detect.Signatures
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScanService : LifecycleService() {

    private var scanner: BluetoothLeScanner? = null
    private lateinit var location: FusedLocationProviderClient

    private val tracker = Tracker()
    private val identities = IdentityResolver()
    private val track = LocationTrack()

    private lateinit var cellMonitor: CellMonitor
    private lateinit var imsiCatcher: IMSICatcher
    private lateinit var eventLog: EventLog
    private lateinit var cellStore: Store
    private lateinit var timelineStore: Store

    private val alerted = HashSet<String>()
    private val gpsTrail = ArrayList<LatLon>(500)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handle(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
        override fun onScanFailed(errorCode: Int) {
            Registry.update { it.copy(scanning = false, error = "Bluetooth scan failed ($errorCode)") }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation?.toFix() ?: return
            track.update(fix)
            synchronized(gpsTrail) {
                gpsTrail.add(LatLon(fix.lat, fix.lon))
                if (gpsTrail.size > 500) gpsTrail.removeAt(0)
            }
            Registry.update {
                it.copy(
                    hasFix = true, moving = track.isMoving(),
                    lat = fix.lat, lon = fix.lon, travelledM = track.travelledM
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner.also { scanner = it }
        location = LocationServices.getFusedLocationProviderClient(this)

        cellStore = Store(this, "imsi_baseline.json")
        timelineStore = Store(this, "timeline.json")
        cellMonitor = CellMonitor(this)
        imsiCatcher = IMSICatcher(cellStore)
        eventLog = EventLog(timelineStore)
        Registry.publishTimeline(eventLog.snapshot())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildOngoing(0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        startScanning()
        startLocation()
        startCellPolling()
        startPublishing()

        val startEvt = TimelineEvent(
            id = "scan_start@${System.currentTimeMillis()}", kind = EventKind.SCAN_START,
            ts = System.currentTimeMillis(), title = "Scanning started", detail = "",
            severity = Severity.LOW
        )
        eventLog.record(startEvt)
        Registry.publishTimeline(eventLog.snapshot())

        return START_STICKY
    }

    private fun startScanning() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Registry.update { it.copy(scanning = false, bluetoothOn = false, error = "Bluetooth is off") }
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
            Registry.update { it.copy(scanning = false, error = "Nearby devices permission not granted") }
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false).setReportDelay(0).build()
        try {
            scanner?.startScan(null, settings, scanCallback)
            Registry.update { it.copy(scanning = true, bluetoothOn = true, error = null) }
        } catch (e: SecurityException) {
            Registry.update { it.copy(scanning = false, error = "Scan denied: ${e.message}") }
        }
    }

    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Registry.update { it.copy(hasFix = false) }
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L).setMinUpdateDistanceMeters(25f).build()
        try {
            location.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Registry.update { it.copy(hasFix = false, error = "Location denied: ${e.message}") }
        }
    }

    private fun startCellPolling() {
        lifecycleScope.launch {
            while (isActive) {
                val reason = cellMonitor.unavailableReason()
                if (reason != null) {
                    Registry.publishCell(CellStatus(available = false, reason = reason))
                } else {
                    val cell = cellMonitor.sample()
                    if (cell != null) {
                        val fix = track.current
                        val findings = imsiCatcher.recordAndAnalyze(cell, fix)
                        val (score, level) = imsiCatcher.scoreAndLevel(findings)
                        val (knownCells, observations, maturity) = imsiCatcher.stats()
                        Registry.publishCell(
                            CellStatus(
                                available = true, cell = cell, findings = findings,
                                score = score, level = level,
                                mature = maturity >= 1f, maturity = maturity,
                                knownCells = knownCells, observations = observations
                            )
                        )
                        if (level.ordinal >= Threat.MEDIUM.ordinal && findings.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            val evt = TimelineEvent(
                                id = "catcher@$now", kind = EventKind.CATCHER,
                                ts = now, title = findings.first().title,
                                detail = findings.joinToString("; ") { it.id },
                                severity = when (level) {
                                    Threat.CRITICAL -> Severity.CRITICAL
                                    Threat.HIGH -> Severity.HIGH
                                    else -> Severity.MEDIUM
                                },
                                lat = fix?.lat, lon = fix?.lon
                            )
                            if (eventLog.record(evt, "catcher@${cell.tac ?: cell.cellId}")) {
                                Registry.publishTimeline(eventLog.snapshot())
                                notifyCatcher(findings.first().title)
                            }
                        }
                    }
                }
                delay(15_000L)
            }
        }
    }

    private fun startPublishing() {
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                tracker.prune(now)
                val all = tracker.snapshot(now)
                // Only surface devices worth the user's attention; transient single-sighting
                // signals from people walking by are tracked internally but not listed.
                val list = all.filter { d ->
                    d.following || d.persistent ||
                        (d.tracker != null && d.sightings >= 3) ||
                        d.sightings >= 5
                }.sortedWith(
                    compareByDescending<Detection> { it.following }
                        .thenByDescending { it.persistent }
                        .thenByDescending { it.identified }
                        .thenByDescending { it.sightings }
                )
                Registry.update { it.copy(nearbyCount = all.size) }
                Registry.publish(list)
                updateOngoing(list)

                val trail = synchronized(gpsTrail) { ArrayList(gpsTrail) }
                val deviceTrails = list.filter { it.points.isNotEmpty() }.map {
                    DeviceTrail(it.key, it.name, it.threat, it.following, it.points)
                }
                val cellNow = Registry.cell.value
                val cellMarkers = if (cellNow.level.ordinal >= Threat.MEDIUM.ordinal && cellNow.cell != null) {
                    val fix = track.current
                    if (fix != null) listOf(
                        CellMarker("${cellNow.cell.rat.label} ${cellNow.cell.cellId}",
                            LatLon(fix.lat, fix.lon), cellNow.level)
                    ) else emptyList()
                } else emptyList()
                Registry.publishMap(MapData(trail, deviceTrails, cellMarkers))
                delay(1500)
            }
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        if (Signatures.isBenign(record)) return

        val now = System.currentTimeMillis()
        val address = result.device.address ?: return
        val fingerprint = Fingerprint.of(record)
        val resolution = identities.resolve(address, result.rssi, fingerprint, now)
        val tracked = Signatures.match(record)
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: tracked?.label
            ?: Signatures.companyLabel(record)
            ?: "Unknown device"
        val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }

        val observation = tracker.observe(
            key = resolution.identity.id, address = address, name = name,
            rssi = result.rssi, tracker = tracked,
            approxMetres = Signatures.approximateMetres(result.rssi, txPower),
            rotations = resolution.identity.rotations,
            addresses = resolution.identity.addresses.size,
            fix = track.current, hasPosition = track.hasFix(), now = now
        )

        if (observation.becameFollowing && alerted.add(observation.detection.key)) {
            notifyFollowing(observation.detection)
            val fix = track.current
            val evt = TimelineEvent(
                id = "following@${observation.detection.key}@$now",
                kind = EventKind.FOLLOWING,
                ts = now,
                title = "${observation.detection.name} is following you",
                detail = "Confirmed over ${observation.detection.displacementM.toInt()} m, ${observation.detection.sightings} sightings",
                severity = Severity.CRITICAL,
                lat = fix?.lat, lon = fix?.lon
            )
            if (eventLog.record(evt, "following@${observation.detection.key}")) {
                Registry.publishTimeline(eventLog.snapshot())
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Scanning", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the detector is running" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Tracker alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A device has been confirmed as following you" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CELL, "Cell alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Potential cellular anomaly detected" }
        )
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoing(watching: Int, following: Int): Notification {
        val text = when {
            following > 0 -> "$following confirmed following you"
            watching > 0 -> "Watching $watching device${if (watching == 1) "" else "s"}"
            else -> "Scanning for trackers"
        }
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("Track Detect").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    private fun updateOngoing(list: List<Detection>) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildOngoing(list.size, list.count { it.following }))
    }

    private fun notifyFollowing(detection: Detection) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val metres = detection.displacementM.toInt()
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("${detection.name} is following you")
            .setContentText("Heard $metres m apart. It is on you or your vehicle.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${detection.name} present at locations $metres m apart over " +
                    "${detection.sightings} sightings. Check wheel wells, bumper covers, " +
                    "OBD-II port, under seats, bag linings."
            ))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(contentIntent()).build()
        getSystemService(NotificationManager::class.java).notify(detection.key.hashCode(), n)
    }

    private fun notifyCatcher(title: String) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val n = NotificationCompat.Builder(this, CHANNEL_CELL)
            .setContentTitle("Cell anomaly detected")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(contentIntent()).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CELL, n)
    }

    override fun onDestroy() {
        try { if (granted(Manifest.permission.BLUETOOTH_SCAN)) scanner?.stopScan(scanCallback) }
        catch (_: SecurityException) {}
        location.removeLocationUpdates(locationCallback)
        Registry.update { it.copy(scanning = false) }

        val stopEvt = TimelineEvent(
            id = "scan_stop@${System.currentTimeMillis()}", kind = EventKind.SCAN_STOP,
            ts = System.currentTimeMillis(), title = "Scanning stopped", detail = "",
            severity = Severity.LOW
        )
        eventLog.record(stopEvt)
        cellStore.flush()
        timelineStore.flush()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    companion object {
        const val ACTION_STOP = "com.trackdetect.STOP"
        private const val CHANNEL_ONGOING = "scanning"
        private const val CHANNEL_ALERT = "alerts"
        private const val CHANNEL_CELL = "cell_alerts"
        private const val NOTIFICATION_ID = 1
        private const val NOTIF_CELL = 2
    }
}
