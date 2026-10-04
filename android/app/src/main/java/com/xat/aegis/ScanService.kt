package com.xat.aegis

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import com.xat.aegis.analysis.AudioRouteMonitor
import com.xat.aegis.analysis.CellMonitor
import com.xat.aegis.analysis.EventLog
import com.xat.aegis.analysis.Fingerprint
import com.xat.aegis.analysis.Fix
import com.xat.aegis.analysis.IMSICatcher
import com.xat.aegis.analysis.IdentityResolver
import com.xat.aegis.analysis.LocationHistory
import com.xat.aegis.analysis.LocationTrack
import com.xat.aegis.analysis.Tracker
import com.xat.aegis.analysis.PhoneHealthMonitor
import com.xat.aegis.analysis.WifiScanner
import com.xat.aegis.analysis.toFix
import com.xat.aegis.detect.BleCanary
import com.xat.aegis.detect.BleNames
import com.xat.aegis.detect.CadenceFingerprinter
import com.xat.aegis.detect.DultInterrogator
import com.xat.aegis.detect.KarmaCanary
import com.xat.aegis.detect.Signatures
import com.xat.aegis.detect.VehicleTrips
import com.xat.aegis.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ScanService : LifecycleService() {

    private var scanner: BluetoothLeScanner? = null
    private lateinit var location: FusedLocationProviderClient
    private var running = false

    private lateinit var tracker: Tracker
    private val identities = IdentityResolver()
    private val track = LocationTrack()
    /** Links address rotations the payload cannot prove, by the rhythm of the radio. */
    private val cadence = CadenceFingerprinter()
    private lateinit var vehicleTrips: VehicleTrips

    /** Logical devices already asked over DULT — one exchange each, answered or refused. */
    private val dultAsked = HashSet<String>()
    /** One GATT exchange at a time; the stack handles parallel connections badly. */
    private val dultGate = Mutex()

    /** Wall time until which the scan runs at low latency: the first minutes of a drive. */
    @Volatile
    private var intensiveUntil = 0L
    private var intensiveJob: Job? = null
    /** Consecutive fixes at driving speed, and when the last of them arrived. */
    private var drivingFixes = 0
    @Volatile
    private var lastDrivingAt = 0L

    private lateinit var cellMonitor: CellMonitor
    private lateinit var imsiCatcher: IMSICatcher
    private lateinit var eventLog: EventLog
    private lateinit var cellStore: Store

    private val alerted = HashSet<String>()
    private val gpsTrail = ArrayList<LatLon>(500)
    private var publishCycle = 0
    private lateinit var phoneHealthMonitor: PhoneHealthMonitor

    /** Whether a scan is currently registered with the adapter. */
    @Volatile
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
            // The service stays alive after a failure, so without a retry it would sit
            // there forever with nothing registered. Transient stack errors are retried
            // after a pause; the throttle error gets a longer one.
            val retryMs = when (errorCode) {
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> RETRY_DELAY_MS
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> RETRY_THROTTLED_DELAY_MS
                else -> return
            }
            scheduleScanRetry(retryMs)
        }
    }

    /** Pending scan retry after an [onScanFailed]; only one is ever in flight. */
    private var retryJob: Job? = null

    private fun scheduleScanRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(delayMs)
            retryJob = null
            if (running && !scanActive) startScanning()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation?.toFix() ?: return
            LocationHistory.record(this@ScanService, fix)
            track.update(fix)
            updateVehicle(fix)
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
        vehicleTrips = VehicleTrips(Store(this, "vehicle_trips.json"))
        // Shared with the activity, which records NFC scans into the same log.
        eventLog = TimelineLog.get(this)
        Registry.publishTimeline(eventLog.snapshot())
        phoneHealthMonitor = PhoneHealthMonitor(this)
        phoneHealthMonitor.start()
        // A microphone attaching itself over Bluetooth or USB while nothing is
        // playing is watched for as long as the scanner runs; the unlock ledger
        // opens here too so the periodic refresh below has it.
        AudioRouteMonitor.start(this)
        UnlockLedger.init(this)
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

        // Already scanning: nothing to do. Running but not scanning means the last scan
        // failed, and a start from the UI is the user asking for another go.
        if (running && scanActive) return START_STICKY
        if (running) {
            retryJob?.cancel()
            retryJob = null
            startScanning()
            return START_STICKY
        }
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
        // The Karma canary is a hidden network suggestion the platform probes for;
        // registering it is idempotent, and a refusal only means no canary this run.
        runCatching { KarmaCanary.ensure(this) }

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
        // The first minutes of a drive get the intensive sweep — see [beginTrip].
        val mode = if (System.currentTimeMillis() < intensiveUntil) ScanSettings.SCAN_MODE_LOW_LATENCY
        else AppSettings.scanMode
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
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
                    val snapshot = cellMonitor.sampleFull()
                    if (snapshot == null) {
                        Registry.publishCell(
                            CellStatus(
                                available = false,
                                reason = "Waiting for cell data — the modem has not reported a serving cell yet",
                                radio = runCatching { cellMonitor.radioInfo() }.getOrNull(),
                                analysing = true
                            )
                        )
                    } else {
                        val cell = snapshot.cell
                        val fix = track.current
                        val findings = imsiCatcher.recordAndAnalyze(cell, fix)
                        val (score, level) = imsiCatcher.scoreAndLevel(findings)
                        val (knownCells, visits, maturity) = imsiCatcher.stats()
                        Registry.publishCell(
                            CellStatus(
                                available = true, cell = cell, findings = findings,
                                score = score, level = level,
                                mature = maturity >= 1f, maturity = maturity,
                                knownCells = knownCells, visits = visits,
                                radio = runCatching { cellMonitor.radioInfo() }.getOrNull(),
                                neighbors = snapshot.neighbors,
                                analysing = true
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
                // Fused location goes quiet once the car is parked (25 m minimum
                // displacement), so the end of a trip is noticed here, not in the
                // location callback.
                if (vehicleTrips.inTrip && now - lastDrivingAt > TRIP_END_MS) endTrip(now)
                // An identity behind a confirmed follower must outlive the identity
                // window, or the same device comes back as a stranger and the original
                // row is orphaned as a permanent "following" ghost.
                identities.prune(now, keep = tracker.followingKeys())
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
                // Ordered by key, not by the sighting-sorted `list`: the map only cares
                // where each device is, and sightings change on nearly every advert, so
                // the sighting order made every 1.5 s MapData compare unequal. Each one
                // then re-ran the map's overlay effect and invalidated the MapView, and
                // every redraw re-requests any expired tile from the tile server.
                // With a stable order the StateFlow drops publishes that change nothing.
                val deviceTrails = list.filter { it.points.isNotEmpty() }.map {
                    DeviceTrail(it.key, it.name, it.threat, it.following, it.points, it.lastHeardAt)
                }.sortedBy { it.key }
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
                    // Unlock ledger: pull the platform's keyguard events and judge
                    // the sleep window, so a night-time unlock is reported while
                    // the owner is still asleep rather than when they next open Aegis.
                    withContext(Dispatchers.IO) { runCatching { UnlockLedger.refresh(this@ScanService) } }
                }

                // WiFi anomaly scan: immediately on first cycle, then every ~60s
                if (publishCycle == 1 || publishCycle % 40 == 0) {
                    // The picture the WIFI tab shows, then the judgement on it. The
                    // snapshot requests a fresh scan and waits for its results, so both
                    // it and the anomaly scan below read this cycle's beacons rather than
                    // whatever the platform cached last time.
                    runCatching { WifiScanner.snapshot(this@ScanService) }.getOrNull()?.let { Registry.publishWifiStatus(it) }
                    val wifiAnomalies = WifiScanner.scan(this@ScanService)
                    // The same scan cache, checked for the one network name that exists
                    // nowhere but inside this phone. An answer has no benign explanation.
                    val karmaHits = KarmaCanary.scan(this@ScanService)
                    Registry.publishWifi(wifiAnomalies + karmaHits)
                    for (hit in karmaHits) {
                        Registry.publishAlert(
                            Alert(
                                id = "karma@${hit.bssid}@$now", ts = now, severity = Severity.CRITICAL,
                                title = "Rogue access point answered the canary probe",
                                detail = KarmaCanary.describe(hit),
                                kind = EventKind.WIFI_ANOMALY, dedupeKey = "karma@${hit.bssid}"
                            )
                        )
                    }
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
        // Every packet, unfiltered, for the finder and the self-test that follow the
        // stream; neither needs a scan of its own.
        Registry.publishScan(result)
        val record = result.scanRecord
        // Our own fake AirTag: the canary has just been told; it must not become a
        // detection or get interrogated.
        if (BleCanary.isCanary(record)) return
        if (Signatures.isBenign(record)) return

        // Skip devices the user has explicitly marked as their own.
        val now = System.currentTimeMillis()
        val device = result.device
        val address = device.address?.let { BleNames.formatMac(it) } ?: return
        if (Registry.trusted.value.contains(address)) return
        val fingerprint = Fingerprint.of(record)
        val resolution = identities.resolve(address, result.rssi, fingerprint, now)
        // The identity resolver links a rotation when the payload matches. When it does
        // not — a Find My key change rotates the whole payload — the radio's cadence can
        // still prove the new address is the same device; its sightings then move under
        // the id the device already had.
        val stitched = cadence.stitch(result)
        val key = if (stitched != null && stitched != resolution.identity.id) {
            tracker.merge(resolution.identity.id, stitched)
            stitched
        } else {
            cadence.assign(address, resolution.identity.id)
            resolution.identity.id
        }
        cadence.supersededKey(address)?.let { tracker.merge(it, key) }
        val tracked = Signatures.match(record)
        val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }

        // Identification is display-only and resolved here, once per packet, from the
        // SIG assigned numbers: who made it, what it calls itself, what it advertises.
        // The tracker keeps the best of these across packets and derives the row name.
        val observation = tracker.observe(
            key = key, address = address,
            rssi = result.rssi, tracker = tracked,
            approxMetres = Signatures.approximateMetres(result.rssi, txPower),
            rotations = resolution.identity.rotations,
            addresses = resolution.identity.addresses.size,
            fix = track.current, hasPosition = track.hasFix(), now = now,
            advertisedName = BleNames.advertisedName(this, record, device),
            manufacturer = BleNames.manufacturer(record),
            services = BleNames.services(record),
            radio = BleNames.radio(this, device),
            addressKind = BleNames.addressKind(device, address)
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

        val detection = observation.detection
        if (vehicleTrips.inTrip) {
            vehicleTrips.sight(key, address, detection.name, detection.tracker, fingerprint, now)
                ?.let(::publishVehicleFinding)
        }
        maybeInterrogate(key, device, detection)
    }

    /**
     * Asks a tracker who it is over DULT, once per logical device, the first time it is
     * recognised as a type whose maker ships the protocol or is confirmed as following.
     * Runs on the service scope, never on the scan callback; the exchange has its own
     * budget and the collector a slightly longer one in case the stack never answers.
     * Making the tracker sound is left to the user — [DultInterrogator.sound].
     */
    private fun maybeInterrogate(key: String, device: BluetoothDevice, detection: Detection) {
        val known = detection.tracker?.id?.let { it in DultInterrogator.SUPPORTED } == true
        if (!known && !detection.following) return
        synchronized(dultAsked) { if (key in dultAsked) return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) return
        synchronized(dultAsked) { if (!dultAsked.add(key)) return }
        lifecycleScope.launch(Dispatchers.IO) {
            dultGate.withLock {
                withTimeoutOrNull(DultInterrogator.TIMEOUT_MS + DULT_GRACE_MS) {
                    DultInterrogator.interrogate(device, this@ScanService).collect { tracker.attachDult(key, it) }
                }
            }
        }
    }

    /**
     * Driving is two consecutive fixes at road speed; the trip then runs until no fix
     * has shown that speed for [TRIP_END_MS]. Walking and cycling never reach it, and a
     * single spurious speed reading on its own starts nothing.
     */
    private fun updateVehicle(fix: Fix) {
        val speed = fix.speed ?: return
        if (speed < DRIVING_SPEED_MS) { drivingFixes = 0; return }
        lastDrivingAt = fix.time
        drivingFixes++
        if (drivingFixes >= 2 && !vehicleTrips.inTrip) beginTrip(fix)
    }

    /**
     * A tracker on a vehicle advertises from inches away, often only when moving. The
     * first minutes of a drive are therefore scanned at low latency — a full sweep at
     * the cost of some battery — before the scan drops back to the configured mode.
     */
    private fun beginTrip(fix: Fix) {
        vehicleTrips.beginTrip(fix.time, fix.lat, fix.lon)
        intensiveUntil = fix.time + INTENSIVE_SWEEP_MS
        restartScan()
        intensiveJob?.cancel()
        intensiveJob = lifecycleScope.launch {
            delay(INTENSIVE_SWEEP_MS)
            intensiveJob = null
            intensiveUntil = 0L
            if (running) restartScan()
        }
    }

    private fun endTrip(now: Long) {
        val findings = vehicleTrips.endTrip(now)
        drivingFixes = 0
        if (intensiveUntil != 0L) {
            intensiveUntil = 0L
            intensiveJob?.cancel()
            intensiveJob = null
            // Back to the configured mode on the main thread, where the scan is managed.
            lifecycleScope.launch { if (running) restartScan() }
        }
        findings.forEach(::publishVehicleFinding)
    }

    /** A device that has ridden along on enough separate drives, through the shared alert path. */
    private fun publishVehicleFinding(finding: VehicleTrips.Finding) {
        Registry.publishAlert(
            Alert(
                id = finding.id, ts = System.currentTimeMillis(), severity = Severity.HIGH,
                title = finding.title, detail = finding.detail,
                kind = EventKind.FOLLOWING, dedupeKey = finding.dedupeKey
            )
        )
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
        cadence.clear()
        vehicleTrips.clear()
        synchronized(dultAsked) { dultAsked.clear() }
        LocationHistory.clear(this)
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
        running && !scanActive -> "Scan paused — retrying"
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
            // A drive still under way is closed and stored, or the trip count loses it.
            if (vehicleTrips.inTrip) vehicleTrips.endTrip(System.currentTimeMillis()).forEach(::publishVehicleFinding)
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
        // The last reading stays on the Cell tab, but nothing is judging it any more;
        // the tab says so instead of showing a frozen "RISK: NONE" as if it were live.
        Registry.updateCell { it.copy(analysing = false, findings = emptyList(), score = 0, level = Threat.NONE) }
        cellStore.flush()
        TimelineLog.flush()
        LocationHistory.flush()
        phoneHealthMonitor.stop()
        AudioRouteMonitor.stop()
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

        // Not exposed as constants on ScanCallback before API 33 / 34, so spelled out.
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
        private const val RETRY_DELAY_MS = 30_000L
        private const val RETRY_THROTTLED_DELAY_MS = 60_000L

        /** Road speed: about 29 km/h, above anything on foot or a bicycle sustains. */
        private const val DRIVING_SPEED_MS = 8f
        /** No fix at road speed for this long and the car is parked. */
        private const val TRIP_END_MS = 5 * 60_000L
        /** How long the start of a drive is scanned at low latency. */
        private const val INTENSIVE_SWEEP_MS = 5 * 60_000L
        /** Allowance past the interrogator's own budget before its collector is cancelled. */
        private const val DULT_GRACE_MS = 5_000L
    }
}
