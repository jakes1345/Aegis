package com.xat.aegis

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.xat.aegis.analysis.CellMonitor
import com.xat.aegis.analysis.EventLog
import com.xat.aegis.analysis.Fingerprint
import com.xat.aegis.analysis.IMSICatcher
import com.xat.aegis.analysis.IdentityResolver
import com.xat.aegis.analysis.LocationTrack
import com.xat.aegis.analysis.Tracker
import com.xat.aegis.analysis.PhoneHealthMonitor
import com.xat.aegis.analysis.WifiScanner
import com.xat.aegis.analysis.toFix
import com.xat.aegis.detect.Signatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScanService : LifecycleService() {

    private var scanner: BluetoothLeScanner? = null
    private lateinit var location: FusedLocationProviderClient
    private var running = false

    private lateinit var tracker: Tracker
    private val identities = IdentityResolver()
    private val track = LocationTrack()

    private lateinit var cellMonitor: CellMonitor
    private lateinit var imsiCatcher: IMSICatcher
    private lateinit var eventLog: EventLog
    private lateinit var cellStore: Store

    private val alerted = HashSet<String>()
    private val gpsTrail = ArrayList<LatLon>(500)
    private var publishCycle = 0
    private lateinit var phoneHealthMonitor: PhoneHealthMonitor

    /** Whether a scan is currently registered with the adapter. */
    private var scanActive = false

    /** Screen state decides which filter strategy the scan can use — see [startScanning]. */
    @Volatile
    private var screenOn = true

    /** Last ongoing-notification text, so it is only re-posted when it actually changes. */
    private var lastOngoingText: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handle(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
        override fun onScanFailed(errorCode: Int) {
            // A scan that is already running is not an error worth showing.
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return
            scanActive = false
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

    /**
     * Two things outside this service change whether it can see anything: the screen
     * turning off (which changes what filters the scan needs) and Bluetooth being
     * switched on or off. Without watching for the latter, turning Bluetooth on after
     * starting a scan left the app sitting on "Bluetooth is off" forever.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; restartScan() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; restartScan() }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> restartScan()
                        BluetoothAdapter.STATE_OFF -> {
                            scanActive = false
                            Registry.update {
                                it.copy(scanning = false, bluetoothOn = false, error = "Bluetooth is off")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The service can be started at boot without the activity ever running, so it
        // loads settings and the trusted list itself rather than assuming they are up.
        AppSettings.load(this)
        Registry.bindTrustStore(this)

        tracker = Tracker(
            persistMs = { AppSettings.persistenceThresholdMs },
            followM = { AppSettings.followThresholdM.toDouble() }
        )

        createChannels()
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner.also { scanner = it }
        location = LocationServices.getFusedLocationProviderClient(this)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true

        cellStore = Store(this, "imsi_baseline.json")
        cellMonitor = CellMonitor(this)
        imsiCatcher = IMSICatcher(cellStore)
        // Shared with the activity, which records NFC scans into the same log.
        eventLog = TimelineLog.get(this)
        Registry.publishTimeline(eventLog.snapshot())
        phoneHealthMonitor = PhoneHealthMonitor(this)
        phoneHealthMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                AppSettings.setScanEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLEAR -> {
                // Wiping the files on disk is not enough while this service is alive:
                // it holds the baseline and the event log in memory and would write
                // them straight back out on the next flush.
                clearAllData()
                if (!running) { stopSelf(); return START_NOT_STICKY }
                return START_STICKY
            }
        }

        if (running) return START_STICKY
        running = true

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildOngoing(0, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Each refusal has its own remedy, so each gets its own message. They all
            // used to read "Location permission required", which sent someone whose
            // permissions were fine off to the settings page after a background start
            // was simply refused.
            val message = when (e) {
                // A location-typed foreground service is refused without location access.
                is SecurityException -> "Location permission required to scan"
                // Android 12+ refuses to start a foreground service from the background
                // (boot, a stale notification tap); the user has to bring Aegis to the front.
                is ForegroundServiceStartNotAllowedException ->
                    "Android blocked starting the scanner in the background — open Aegis and tap Start"
                else -> "The scanner could not start (${e.javaClass.simpleName})"
            }
            running = false
            Registry.update { it.copy(scanning = false, error = message) }
            stopSelf()
            return START_NOT_STICKY
        }

        AppSettings.setScanEnabled(this, true)
        registerSystemReceiver()

        startScanning()
        startLocation()
        startCellPolling()
        startPublishing()

        recordEvent(
            TimelineEvent(
                id = "scan_start@${System.currentTimeMillis()}", kind = EventKind.SCAN_START,
                ts = System.currentTimeMillis(), title = "Scanning started", detail = "",
                severity = Severity.LOW
            )
        )

        return START_STICKY
    }

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun restartScan() {
        if (!running) return
        stopScanning()
        startScanning()
    }

    private fun stopScanning() {
        if (!scanActive) return
        try {
            if (granted(Manifest.permission.BLUETOOTH_SCAN)) scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Adapter turned off underneath us; nothing left to stop.
        }
        scanActive = false
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
            .setScanMode(AppSettings.scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false).setReportDelay(0).build()

        // Android 8.1+ suspends an *unfiltered* scan once the screen goes off, so a
        // filter list is the price of scanning with the phone in a pocket. But any
        // filter list is also a whitelist, and the whole point of this app is the
        // hardwired GPS/LTE box that advertises nothing anyone has catalogued —
        // scanning only for Apple, Samsung and Tile made the headline case
        // undetectable. So: no filters while the screen is on, which is when unknown
        // devices can be discovered, and the known-tracker filters while it is off,
        // which is when the scan would otherwise stop entirely.
        val filters = if (screenOn) emptyList() else buildScanFilters()
        try {
            scanner?.startScan(filters, settings, scanCallback)
            scanActive = true
            Registry.update { it.copy(scanning = true, bluetoothOn = true, error = null) }
        } catch (e: SecurityException) {
            Registry.update { it.copy(scanning = false, error = "Scan denied: ${e.message}") }
        } catch (e: IllegalStateException) {
            Registry.update { it.copy(scanning = false, error = "Bluetooth unavailable: ${e.message}") }
        }
    }

    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Registry.update { it.copy(hasFix = false) }
            return
        }
        // High accuracy, not balanced: balanced power returns Wi-Fi/cell fixes of
        // ~100 m or worse, and ObservationArea discards anything over 50 m, so places
        // never accumulated and nothing could ever be confirmed as following. This
        // is already a location foreground service; GPS is what it is for.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L).setMinUpdateDistanceMeters(25f).build()
        try {
            location.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Registry.update { it.copy(hasFix = false, error = "Location denied: ${e.message}") }
        }
    }

    // Cell sampling reads the modem and writes the baseline to disk. Both belong off
    // the main thread — on it, every poll janked the UI of whatever was on screen.
    private fun startCellPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val reason = cellMonitor.unavailableReason()
                if (reason != null) {
                    Registry.publishCell(CellStatus(available = false, reason = reason))
                } else {
                    val cell = cellMonitor.sample()
                    if (cell == null) {
                        Registry.publishCell(CellStatus(available = false, reason = "Waiting for cell data — ensure location permission is granted"))
                    } else {
                        val fix = track.current
                        val findings = imsiCatcher.recordAndAnalyze(cell, fix)
                        val (score, level) = imsiCatcher.scoreAndLevel(findings)
                        val (knownCells, visits, maturity) = imsiCatcher.stats()
                        Registry.publishCell(
                            CellStatus(
                                available = true, cell = cell, findings = findings,
                                score = score, level = level,
                                mature = maturity >= 1f, maturity = maturity,
                                knownCells = knownCells, visits = visits
                            )
                        )
                        if (level.ordinal >= Threat.MEDIUM.ordinal && findings.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            val evt = TimelineEvent(
                                id = "catcher@${cell.tac ?: cell.cellId}@$now", kind = EventKind.CATCHER,
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
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val now = System.currentTimeMillis()
                tracker.prune(now)
                identities.prune(now)
                val all = tracker.snapshot(now)
                val trusted = Registry.trusted.value
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
                // The ongoing notification counts what the user would act on, which
                // excludes anything they have already marked as their own.
                updateOngoing(list.filter { it.address !in trusted })

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

                // Phone health: immediately on first cycle, then every ~30s
                publishCycle++
                if (publishCycle == 1 || publishCycle % 20 == 0) {
                    phoneHealthMonitor.scanAndPublish()
                }

                // WiFi anomaly scan: immediately on first cycle, then every ~60s
                if (publishCycle == 1 || publishCycle % 40 == 0) {
                    val wifiAnomalies = WifiScanner.scan(this@ScanService)
                    Registry.publishWifi(wifiAnomalies)
                    if (wifiAnomalies.isNotEmpty()) {
                        val top = wifiAnomalies.maxByOrNull { it.threat.ordinal }!!
                        val evt = TimelineEvent(
                            id = "wifi@${top.bssid}@$now",
                            kind = EventKind.WIFI_ANOMALY,
                            ts = now, title = "Wi-Fi anomaly: ${top.ssid}",
                            detail = "${wifiAnomalies.size} suspicious network(s) — ${top.reason}",
                            severity = when (top.threat) {
                                Threat.CRITICAL -> Severity.CRITICAL
                                Threat.HIGH -> Severity.HIGH
                                else -> Severity.MEDIUM
                            },
                            lat = track.current?.lat, lon = track.current?.lon
                        )
                        if (eventLog.record(evt, "wifi@${top.bssid}")) {
                            Registry.publishTimeline(eventLog.snapshot())
                        }
                    }

                    // Correlated surveillance: persistent BLE tracker + active cell anomaly + wifi bait
                    if (Registry.cell.value.level.ordinal >= Threat.HIGH.ordinal) {
                        val correlatedBle = list.filter {
                            it.persistent && !it.following && it.address !in trusted
                        }
                        if (correlatedBle.isNotEmpty() && wifiAnomalies.isNotEmpty()) {
                            val evt2 = TimelineEvent(
                                id = "corr@${correlatedBle.first().key}@$now",
                                kind = EventKind.CORRELATED_SURVEILLANCE,
                                ts = now,
                                title = "Correlated surveillance indicators",
                                detail = "${correlatedBle.size} persistent BLE device(s) + active cell anomaly + WiFi bait",
                                severity = Severity.CRITICAL,
                                lat = track.current?.lat, lon = track.current?.lon
                            )
                            if (eventLog.record(evt2, "corr@${correlatedBle.first().key}")) {
                                Registry.publishTimeline(eventLog.snapshot())
                            }
                        }
                    }
                }

                delay(1500)
            }
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        if (Signatures.isBenign(record)) return

        // Skip devices the user has explicitly marked as their own.
        val now = System.currentTimeMillis()
        val address = result.device.address ?: return
        if (Registry.trusted.value.contains(address)) return
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
            recordEvent(
                TimelineEvent(
                    id = "following@${observation.detection.key}@$now",
                    kind = EventKind.FOLLOWING,
                    ts = now,
                    title = "${observation.detection.name} is following you",
                    detail = "Confirmed over ${observation.detection.displacementM.toInt()} m, ${observation.detection.sightings} sightings",
                    severity = Severity.CRITICAL,
                    lat = fix?.lat, lon = fix?.lon
                ),
                dedupeKey = "following@${observation.detection.key}"
            )
        }
    }

    /** Records an event off the calling thread — [EventLog.record] writes to disk. */
    private fun recordEvent(event: TimelineEvent, dedupeKey: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (eventLog.record(event, dedupeKey)) Registry.publishTimeline(eventLog.snapshot())
        }
    }

    /**
     * Runs synchronously and deliberately so: when the service is not otherwise
     * running this is followed immediately by stopSelf(), and work posted to
     * lifecycleScope would be cancelled before it reached the disk. It is two small
     * JSON writes on an explicit, one-off user action.
     */
    private fun clearAllData() {
        eventLog.clear()
        imsiCatcher.resetBaseline()
        tracker.clear()
        identities.clear()
        alerted.clear()
        synchronized(gpsTrail) { gpsTrail.clear() }
        publishCycle = 0
        lastOngoingText = null

        Registry.reset()
        Registry.clearNfc()
        Registry.publishTimeline(emptyList())
        Registry.publishWifi(emptyList())
        // Registry.reset() puts the status back to its defaults, which would otherwise
        // leave a live scan reporting itself as idle.
        Registry.update { it.copy(scanning = running && scanActive, bluetoothOn = true) }
    }

    /**
     * Filters covering the tracker manufacturer IDs and service UUIDs worth keeping a
     * scan alive for while the screen is off. These are a whitelist — anything not
     * listed is invisible — which is why they are only used in that one case, and the
     * screen-on scan runs unfiltered.
     */
    private fun buildScanFilters(): List<ScanFilter> {
        val manufacturerIds = listOf(
            0x004C, // Apple — AirTag, Find My, iBeacon
            0x0075, // Samsung — SmartTag
            0x00D7, // Tile
            0x005E, // Tile (legacy)
        )
        val serviceUuids = listOf(
            // Tile 0xFEED, Samsung SmartTag 0xFD5A/0xFD70, Chipolo 0xFEBE/0xFE2B,
            // Nut 0xAA01/0xAA02, Eddystone 0xFEAA, Invoxia 0xFE85,
            // Orbit/KeySmart 0xFFE0/0xFFF3 (generic HM-10 UART, paired check in Signatures)
            "0000feed-0000-1000-8000-00805f9b34fb",
            "0000fd5a-0000-1000-8000-00805f9b34fb",
            "0000fd70-0000-1000-8000-00805f9b34fb",
            "0000febe-0000-1000-8000-00805f9b34fb",
            "0000fe2b-0000-1000-8000-00805f9b34fb",
            "0000aa01-0000-1000-8000-00805f9b34fb",
            "0000aa02-0000-1000-8000-00805f9b34fb",
            "0000feaa-0000-1000-8000-00805f9b34fb",
            "0000fe85-0000-1000-8000-00805f9b34fb",
            "0000fe9f-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff3-0000-1000-8000-00805f9b34fb",
        )
        val filters = ArrayList<ScanFilter>()
        manufacturerIds.forEach { id ->
            filters.add(ScanFilter.Builder().setManufacturerData(id, byteArrayOf()).build())
        }
        serviceUuids.forEach { uuid ->
            filters.add(ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid.fromString(uuid)).build())
        }
        return filters
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

    private fun ongoingText(watching: Int, following: Int) = when {
        following > 0 -> "$following confirmed following you"
        watching > 0 -> "Watching $watching device${if (watching == 1) "" else "s"}"
        else -> "Scanning for trackers"
    }

    private fun buildOngoing(watching: Int, following: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("Aegis").setContentText(ongoingText(watching, following))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()

    /**
     * The publish loop runs every 1.5 s; re-posting the notification that often gets
     * the app rate-limited by the system and costs battery for no visible change. Post
     * only when the text actually differs.
     */
    private fun updateOngoing(list: List<Detection>) {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val following = list.count { it.following }
        val text = ongoingText(list.size, following)
        if (text == lastOngoingText) return
        lastOngoingText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildOngoing(list.size, following))
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
        stopScanning()
        if (running) {
            try { unregisterReceiver(systemReceiver) } catch (_: IllegalArgumentException) {}
            location.removeLocationUpdates(locationCallback)
            // Recorded synchronously: the process may not outlive this callback.
            eventLog.record(
                TimelineEvent(
                    id = "scan_stop@${System.currentTimeMillis()}", kind = EventKind.SCAN_STOP,
                    ts = System.currentTimeMillis(), title = "Scanning stopped", detail = "",
                    severity = Severity.LOW
                )
            )
        }
        Registry.update { it.copy(scanning = false) }
        cellStore.flush()
        TimelineLog.flush()
        phoneHealthMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    companion object {
        const val ACTION_STOP = "com.xat.aegis.STOP"
        const val ACTION_CLEAR = "com.xat.aegis.CLEAR"
        private const val CHANNEL_ONGOING = "scanning"
        private const val CHANNEL_ALERT = "alerts"
        private const val CHANNEL_CELL = "cell_alerts"
        private const val NOTIFICATION_ID = 1
        private const val NOTIF_CELL = 2
    }
}
