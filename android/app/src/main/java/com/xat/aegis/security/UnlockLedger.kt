package com.xat.aegis.security

import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import com.xat.aegis.Alert
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import com.xat.aegis.UnlockEvent
import com.xat.aegis.UnlockKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A durable record of every time the phone was unlocked, failed to unlock, or had
 * its lock changed, and the "used while you slept" judgement on top of it.
 *
 * Two sources write the same table:
 *
 * - [UnlockAdminReceiver], a device admin with only the watch-login policy. The
 *   platform calls it on every lock-screen success, failure and credential
 *   change, in real time and whether or not Aegis is running. Enabling it is a
 *   one-time step the owner takes from the Device tab ([requestAdminIntent]).
 * - [UsageStatsManager]'s event stream, which records KEYGUARD_HIDDEN /
 *   KEYGUARD_SHOWN and SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE for every
 *   app with usage access. It fills in history from before the admin was
 *   enabled, and covers the admin being switched off: a stalker who finds the
 *   admin and revokes it (which the ledger records too) does not get the
 *   unlocks it would have caught out of the usage log. Needs the owner to grant
 *   usage access ([usageAccessIntent]).
 *
 * Rows are unique on (ts, event): a backfill never duplicates what it has read
 * before, and the two sources do not describe the same moment twice.
 *
 * Writes go through a single-thread executor so the admin receiver — which is
 * called on the main thread — never blocks on the disk.
 */
object UnlockLedger {

    internal const val DB_NAME = "unlock_ledger.db"
    private const val DB_VERSION = 1
    private const val TABLE = "unlock_events"

    internal const val PREFS_NAME = "unlock_ledger"
    private const val K_SLEEP_START = "sleep_start_min"
    private const val K_SLEEP_END = "sleep_end_min"
    private const val K_BACKFILL_TS = "backfill_ts"
    private const val K_SLEEP_CHECK_TS = "sleep_check_ts"

    /** Minutes after midnight: the sleep window, 23:00 to 07:00 unless the owner changes it. */
    const val DEFAULT_SLEEP_START_MIN = 23 * 60
    const val DEFAULT_SLEEP_END_MIN = 7 * 60

    /** How far back the first backfill reaches; the platform itself keeps a few days at most. */
    private val BACKFILL_HORIZON_MS = TimeUnit.DAYS.toMillis(7)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "unlock-ledger") }

    private val _recent = MutableStateFlow<UnlockEvent?>(null)
    /** The newest row, as it lands. Null until the first row exists. */
    val recentFlow: StateFlow<UnlockEvent?> = _recent.asStateFlow()
    /** The newest row, or null. */
    val latest: UnlockEvent? get() = _recent.value

    @Volatile private var appContext: Context? = null
    @Volatile private var helper: Db? = null

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // The CHECK is built from the enum so the two cannot drift apart.
            val kinds = UnlockKind.values().joinToString(",") { "'${it.name}'" }
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ts INTEGER NOT NULL, " +
                    "event TEXT NOT NULL CHECK(event IN ($kinds)), " +
                    "extra TEXT, " +
                    "UNIQUE(ts, event) ON CONFLICT IGNORE)"
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE(ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Opens the ledger and publishes its newest row. Idempotent; cheap after the first call. */
    fun init(context: Context) {
        if (helper != null) return
        synchronized(this) {
            if (helper != null) return
            val app = context.applicationContext
            appContext = app
            helper = Db(app)
        }
        io.execute { runCatching { newest()?.let { publish(it) } } }
    }

    private fun db(): SQLiteDatabase? = helper?.writableDatabase

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Records [kind] at [ts]. Asynchronous: the row is written on the ledger's
     * thread and published once it is in. An unlock inside the sleep window is
     * judged immediately, not left for the next [refresh].
     */
    fun record(context: Context, kind: UnlockKind, extra: String? = null, ts: Long = System.currentTimeMillis()) {
        init(context)
        io.execute {
            val event = insert(ts, kind, extra) ?: return@execute
            publish(event)
            if (kind.isUnlock && inSleepWindow(ts)) alertSleepUnlock(context.applicationContext, listOf(event))
        }
    }

    /** Inserts one row; null when it was a duplicate or the database is unavailable. */
    private fun insert(ts: Long, kind: UnlockKind, extra: String?): UnlockEvent? = runCatching {
        val db = db() ?: return null
        val values = ContentValues(3).apply {
            put("ts", ts)
            put("event", kind.name)
            put("extra", extra)
        }
        val id = db.insert(TABLE, null, values)
        if (id < 0) null else UnlockEvent(id, ts, kind, extra)
    }.getOrNull()

    private fun publish(event: UnlockEvent) {
        // Rows arrive from the receiver and from a backfill in either order; only a newer one moves the pointer.
        val current = _recent.value
        if (current == null || event.ts >= current.ts) _recent.value = event
        Registry.publishUnlock(event)
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Rows at or after [since], newest first. Blocking; empty before [init] or without a database. */
    fun events(since: Long): List<UnlockEvent> = runCatching {
        val db = db() ?: return emptyList()
        val out = ArrayList<UnlockEvent>()
        db.query(TABLE, arrayOf("id", "ts", "event", "extra"), "ts >= ?", arrayOf(since.toString()), null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                val kind = runCatching { UnlockKind.valueOf(c.getString(2)) }.getOrNull() ?: continue
                out += UnlockEvent(c.getLong(0), c.getLong(1), kind, if (c.isNull(3)) null else c.getString(3))
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun newest(): UnlockEvent? = runCatching {
        val db = db() ?: return null
        db.query(TABLE, arrayOf("id", "ts", "event", "extra"), null, null, null, null, "ts DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            val kind = runCatching { UnlockKind.valueOf(c.getString(2)) }.getOrNull() ?: return null
            UnlockEvent(c.getLong(0), c.getLong(1), kind, if (c.isNull(3)) null else c.getString(3))
        }
    }.getOrNull()

    /** Unlocks since [since] that fell inside the sleep window, newest first. */
    fun sleepAnomalies(since: Long): List<UnlockEvent> =
        events(since).filter { it.kind.isUnlock && inSleepWindow(it.ts) }

    // ── Device admin ────────────────────────────────────────────────────────

    fun adminComponent(context: Context): ComponentName = ComponentName(context, UnlockAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true
    }.getOrDefault(false)

    /** The system's "activate device admin" screen for Aegis's receiver. */
    fun requestAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Aegis keep a record of every time this phone is unlocked, fails to unlock, or has its " +
                    "PIN changed — so you can see whether it was used while you slept. Aegis never locks, " +
                    "wipes or sets a password policy with this."
            )

    // ── Usage access ────────────────────────────────────────────────────────

    /** Whether the owner has granted usage access, which [backfill] needs. */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * Reads the platform's keyguard and screen events since the last backfill
     * (or the horizon, the first time) into the ledger. Blocking; returns how
     * many new rows were written, 0 without usage access.
     */
    fun backfill(context: Context): Int {
        init(context)
        val app = context.applicationContext
        if (!hasUsageAccess(app)) return 0
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return 0
        val p = prefs(app)
        val now = System.currentTimeMillis()
        // Overlap the previous read by a minute: the platform's event stream is
        // written a little behind real time, and UNIQUE(ts, event) absorbs repeats.
        val from = p.getLong(K_BACKFILL_TS, 0L).let { if (it == 0L) now - BACKFILL_HORIZON_MS else it - 60_000L }
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return 0
        val e = UsageEvents.Event()
        var written = 0
        var newest: UnlockEvent? = null
        val db = db() ?: return 0
        db.beginTransaction()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val kind = when (e.eventType) {
                    UsageEvents.Event.KEYGUARD_HIDDEN -> UnlockKind.KEYGUARD_HIDDEN
                    UsageEvents.Event.KEYGUARD_SHOWN -> UnlockKind.KEYGUARD_SHOWN
                    UsageEvents.Event.SCREEN_INTERACTIVE -> UnlockKind.SCREEN_INTERACTIVE
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UnlockKind.SCREEN_NON_INTERACTIVE
                    else -> continue
                }
                val row = insert(e.timeStamp, kind, "usage_stats") ?: continue
                written++
                if (newest == null || row.ts > newest.ts) newest = row
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        p.edit().putLong(K_BACKFILL_TS, now).apply()
        newest?.let { publish(it) }
        return written
    }

    // ── Sleep window ────────────────────────────────────────────────────────

    fun sleepStartMinutes(context: Context): Int = prefs(context).getInt(K_SLEEP_START, DEFAULT_SLEEP_START_MIN)
    fun sleepEndMinutes(context: Context): Int = prefs(context).getInt(K_SLEEP_END, DEFAULT_SLEEP_END_MIN)

    /** Sets the window as minutes after midnight; it may cross midnight (23:00 to 07:00 is 1380 to 420). */
    fun setSleepWindow(context: Context, startMinutes: Int, endMinutes: Int) {
        require(startMinutes in 0 until 24 * 60 && endMinutes in 0 until 24 * 60) { "minutes out of range" }
        prefs(context).edit().putInt(K_SLEEP_START, startMinutes).putInt(K_SLEEP_END, endMinutes).apply()
    }

    /** Whether [ts], in the phone's local time, falls inside the sleep window. */
    fun inSleepWindow(ts: Long): Boolean {
        val ctx = appContext ?: return false
        val start = sleepStartMinutes(ctx)
        val end = sleepEndMinutes(ctx)
        if (start == end) return false
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    /**
     * Raises an alert for any unlock inside the sleep window that has not been
     * judged yet. Blocking. Returns the anomalies found this time.
     */
    fun checkSleepWindow(context: Context): List<UnlockEvent> {
        init(context)
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val since = p.getLong(K_SLEEP_CHECK_TS, 0L).let { if (it == 0L) now - BACKFILL_HORIZON_MS else it }
        val found = sleepAnomalies(since)
        p.edit().putLong(K_SLEEP_CHECK_TS, now).apply()
        if (found.isNotEmpty()) alertSleepUnlock(app, found)
        return found
    }

    /** Backfills from the usage log, then judges the sleep window. Blocking; for a periodic refresh. */
    fun refresh(context: Context) {
        runCatching { backfill(context) }
        runCatching { checkSleepWindow(context) }
    }

    /** One alert per night: the dedupe key names the night the unlock belongs to. */
    private fun alertSleepUnlock(app: Context, anomalies: List<UnlockEvent>) {
        val byNight = anomalies.groupBy { nightOf(it.ts, sleepEndMinutes(app)) }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        for ((night, rows) in byNight) {
            val first = rows.minByOrNull { it.ts } ?: continue
            val times = rows.sortedBy { it.ts }.take(5).joinToString(", ") { time.format(Date(it.ts)) }
            Registry.publishAlert(
                Alert(
                    id = "unlock_sleep@$night@${first.ts}",
                    ts = first.ts,
                    severity = Severity.HIGH,
                    title = "Your phone was unlocked while you slept",
                    detail = "${rows.size} unlock${if (rows.size == 1) "" else "s"} between ${minutesText(sleepStartMinutes(app))} and " +
                        "${minutesText(sleepEndMinutes(app))}: at $times" + (if (rows.size > 5) " and more" else "") +
                        ". If that was not you, someone had your PIN or your finger; check Settings → Security → " +
                        "Biometrics, change your PIN, and look at what was opened.",
                    kind = EventKind.UNLOCK_ANOMALY,
                    dedupeKey = "unlock_sleep@$night"
                ),
                dedupeWindowMs = TimeUnit.DAYS.toMillis(1)
            )
        }
    }

    /**
     * The date the night ending at [endMinutes] belongs to, as yyyy-MM-dd: an
     * unlock at 01:00 and one at 23:30 the evening before are the same night.
     */
    private fun nightOf(ts: Long, endMinutes: Int): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minute >= endMinutes) cal.add(Calendar.DAY_OF_YEAR, 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    private fun minutesText(minutes: Int): String = "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * The device admin that feeds [UnlockLedger]. Declared in the manifest with the
 * watch-login policy only (res/xml/unlock_admin.xml). Every callback runs on the
 * main thread and hands off to the ledger's own thread at once.
 */
class UnlockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        UnlockLedger.record(context, UnlockKind.ADMIN_ENABLED)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off stops Aegis recording when your phone is unlocked. Aegis will note that it was turned off."

    override fun onDisabled(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        UnlockLedger.record(context, UnlockKind.ADMIN_DISABLED, ts = now)
        Registry.publishAlert(
            Alert(
                id = "unlock_admin_off@$now",
                ts = now,
                severity = Severity.MEDIUM,
                title = "Unlock recording was switched off",
                detail = "Aegis's device admin was deactivated, so lock-screen events are no longer recorded " +
                    "in real time. If you did not do this, someone with access to the phone did. " +
                    "Re-enable it from the Device tab.",
                kind = EventKind.PLATFORM,
                dedupeKey = "unlock_admin_off"
            ),
            dedupeWindowMs = 0L
        )
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        UnlockLedger.record(context, UnlockKind.PASSWORD_SUCCEEDED, extra = "device_admin")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        UnlockLedger.record(context, UnlockKind.PASSWORD_FAILED, extra = "device_admin")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: UserHandle) {
        val now = System.currentTimeMillis()
        UnlockLedger.record(context, UnlockKind.PASSWORD_CHANGED, extra = "device_admin", ts = now)
        Registry.publishAlert(
            Alert(
                id = "pin_changed@$now",
                ts = now,
                severity = Severity.HIGH,
                title = "The screen lock was changed",
                detail = "The phone's PIN, pattern or password was changed just now. If you did not change it, " +
                    "someone else knows the old one and has set a new one.",
                kind = EventKind.UNLOCK_ANOMALY,
                dedupeKey = "pin_changed"
            ),
            dedupeWindowMs = 0L
        )
    }
}
