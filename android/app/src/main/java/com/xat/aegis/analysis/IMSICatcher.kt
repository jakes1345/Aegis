package com.xat.aegis.analysis

import com.xat.aegis.CatcherFinding
import com.xat.aegis.Rat
import com.xat.aegis.Severity
import com.xat.aegis.ServingCell
import com.xat.aegis.Store
import com.xat.aegis.Threat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches the serving cell over time and scores each observation against a
 * locally-built baseline. No internet connection, no account, no tower
 * database — just your own history of what "normal" looks like here.
 *
 * Eight heuristics, each with a score weight. The scores accumulate until
 * they cross a Threat threshold. A single indicator is noise; three at once
 * is a problem.
 */
class IMSICatcher(private val store: Store) {

    private data class HistEntry(val cell: ServingCell, val ts: Long)
    private data class Ephemeral(
        val cell: ServingCell,
        val registeredAt: Long,
        var gone: Boolean = false,
        var reported: Boolean = false,
        var reportedLoose: Boolean = false
    )

    private val recent = ArrayDeque<HistEntry>()
    private val ephemeral = LinkedHashMap<String, Ephemeral>()

    private var prev: ServingCell? = null
    private var prevFix: Fix? = null

    init {
        store.load {
            JSONObject()
                .put("cells", JSONObject())
                .put("tacs", JSONObject())
                .put("cellTacs", JSONObject())
        }
    }

    private fun tacKey(c: ServingCell) = "${c.mcc ?: "?"}-${c.mnc ?: "?"}-${c.tac ?: "?"}"
    private fun ctKey(c: ServingCell) = "${c.mcc ?: "?"}-${c.mnc ?: "?"}-${c.cellId}"

    @Synchronized
    fun recordAndAnalyze(cell: ServingCell, fix: Fix?): List<CatcherFinding> {
        val now = System.currentTimeMillis()
        pruneRecent(now)
        updateEphemeral(cell, now)
        recent.addLast(HistEntry(cell, now))

        val findings = runChecks(cell, fix, now)
        updateBaseline(cell, now)

        prev = cell
        prevFix = fix
        return findings
    }

    private fun pruneRecent(now: Long) {
        val cutoff = now - 5 * 60_000L
        while (recent.isNotEmpty() && recent.first().ts < cutoff) recent.removeFirst()
    }

    private fun updateEphemeral(current: ServingCell, now: Long) {
        val activeKeys = recent.map { it.cell.key }.toSet()
        for (e in ephemeral.values) {
            if (!e.gone && e.cell.key != current.key && !activeKeys.contains(e.cell.key) &&
                now - e.registeredAt > 60_000L
            ) {
                e.gone = true
            }
        }
        ephemeral.getOrPut(current.key) { Ephemeral(current, now) }
        if (ephemeral.size > 200) {
            val iter = ephemeral.iterator()
            var n = 0
            while (iter.hasNext() && n < 50) {
                if (iter.next().value.gone) { iter.remove(); n++ }
            }
        }
    }

    private fun runChecks(cell: ServingCell, fix: Fix?, now: Long): List<CatcherFinding> {
        val findings = mutableListOf<CatcherFinding>()
        val data = store.json
        val tacs = data.optJSONObject("tacs") ?: JSONObject()
        val cellTacs = data.optJSONObject("cellTacs") ?: JSONObject()

        val tKey = tacKey(cell)
        val tacRec = tacs.optJSONObject(tKey)
        // Separate visits to this area, not polls. The old "maturity" key counted
        // every 15-second poll, so "5 visits" arrived after 75 s and "20 visits" after
        // five minutes; it is ignored rather than trusted, and areas re-learn.
        val tacVisits = tacRec?.optInt("visits", 0) ?: 0

        // Several heuristics only mean anything while you are standing still: crossing
        // cells, swapping radio technology and signal jumps are all what travelling
        // normally looks like. Speed is authoritative when the fix reports it;
        // otherwise fall back to how far the last two fixes are apart.
        val speed = fix?.speed
        val previousFix = prevFix
        val stationary = when {
            speed != null -> speed < MOVING_SPEED_MS
            previousFix != null && fix != null -> haversine(previousFix, fix) < 200.0
            else -> true
        }

        // 1 — RAT downgrade, to 2G/3G in an area known to have LTE or better. That is
        // what the explainer describes and what interception equipment forces. A 5G
        // to LTE fallback is routine — NR coverage is patchy indoors and at the edge
        // of every cell — and used to raise this on its own.
        val maxRatRank = tacRec?.optInt("maxRatRank", 0) ?: 0
        if (tacVisits >= 5 && cell.rat.rank in 1..Rat.UMTS.rank && maxRatRank >= Rat.LTE.rank) {
            findings += CatcherFinding(
                id = "rat_downgrade", severity = Severity.HIGH,
                title = "Radio downgrade detected",
                detail = "Currently ${cell.rat.label} but this area previously used rank-$maxRatRank technology. Forced downgrade is the primary method used to intercept modern devices."
            )
        }

        // 2 — Cell ID / TAC mismatch.
        // Only compare real tracking areas against real tracking areas. A modem that
        // reported no TAC on the first sighting used to record "?" and then flag every
        // later reading as a mismatch, permanently, on the strength of its own gap.
        val knownTac = cellTacs.optString(ctKey(cell), null)?.takeIf { it != "?" }
        if (knownTac != null && cell.tac != null && knownTac != cell.tac) {
            findings += CatcherFinding(
                id = "cellid_tac_mismatch", severity = Severity.HIGH,
                title = "Cell ID in wrong area",
                detail = "Cell ${cell.cellId} was previously in area $knownTac; it now claims area ${cell.tac}. Real base stations do not change their tracking area code."
            )
        }

        // 3 — TAC change while stationary
        val p = prev
        if (p != null && p.tac != null && cell.tac != null && p.tac != cell.tac) {
            val moved = if (prevFix != null && fix != null) haversine(prevFix!!, fix) > 200.0 else true
            if (!moved) {
                findings += CatcherFinding(
                    id = "tac_change_stationary", severity = Severity.MEDIUM,
                    title = "Area change while stationary",
                    detail = "Tracking area changed from ${p.tac} to ${cell.tac} without movement. You cross cell area boundaries at speed; this happened while you were still."
                )
            }
        }

        // 4 — Signal outlier
        val maxDbm = tacRec?.optInt("maxDbm", -160) ?: -160
        val dbm = cell.signalDbm
        if (dbm != null && tacVisits >= 5 && dbm > maxDbm + 15) {
            findings += CatcherFinding(
                id = "signal_outlier", severity = Severity.MEDIUM,
                title = "Unusually strong signal",
                detail = "Signal is $dbm dBm — ${dbm - maxDbm} dB above the previous maximum for this area ($maxDbm dBm). A transmitter close beside you looks exactly like this."
            )
        }

        // 5 — Unknown cell at familiar area
        if (tacVisits >= 20) {
            val known = tacRec?.optJSONArray("cells") ?: JSONArray()
            var found = false
            for (i in 0 until known.length()) if (known.optString(i) == cell.cellId) { found = true; break }
            if (!found) {
                findings += CatcherFinding(
                    id = "unknown_cell", severity = Severity.MEDIUM,
                    title = "Unknown cell at familiar location",
                    detail = "Cell ${cell.cellId} has never appeared in area ${cell.tac}, but you have visited this area $tacVisits times. New permanent towers are rare; portable ones are not."
                )
            }
        }

        // 6 — Ephemeral cell.
        // The id carries the cell so two vanished towers stay two findings; the UI
        // lists findings by id and duplicates used to take the Cell tab down.
        for ((key, e) in ephemeral) {
            if (e.gone && !e.reportedLoose && key != cell.key) {
                val lifetime = now - e.registeredAt
                if (lifetime < 5 * 60_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell:${e.cell.cellId}", severity = Severity.MEDIUM,
                        title = "Ephemeral tower",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) appeared as serving cell then vanished within ${lifetime / 1000}s. Portable equipment is driven in and away."
                    )
                    e.reportedLoose = true
                }
            }
        }

        // 7 — Cell flapping while stationary
        if (stationary) {
            val uniqueRecent = recent.map { it.cell.key }.toSet().size
            if (uniqueRecent >= 4) {
                findings += CatcherFinding(
                    id = "cell_flapping", severity = Severity.MEDIUM,
                    title = "Rapid cell switching",
                    detail = "Registered on $uniqueRecent different cells in 5 min while stationary. Normal network handover doesn't look like this; active redirection does."
                )
            }
        }

        // 8 — No neighbours.
        // Plenty of modems never populate neighbouring cells at all, and on those the
        // count is zero forever. Treating that as an indicator pinned a permanent
        // finding on the device, so it only counts once this phone has proved it can
        // report neighbours at least once.
        if (cell.neighbors != null && cell.neighbors == 0 && tacVisits >= 5 &&
            data.optBoolean("neighborsEverSeen", false)
        ) {
            findings += CatcherFinding(
                id = "no_neighbors", severity = Severity.LOW,
                title = "No visible neighbours",
                detail = "Serving cell reports zero neighbours. Real base stations always have overlapping coverage; a lone portable transmitter does not."
            )
        }

        // 9 — Timing advance zero on LTE (within ~78m of transmitter).
        // A TA of 0 on LTE means the modem is ≤78m from the transmitter. Real macro
        // cells are never this close; a catcher in a vehicle or building is. Same
        // caveat as neighbours: a modem that always reports 0 is not reporting at all,
        // so this waits until a real non-zero advance has been seen on this device.
        val ta = cell.timingAdvance
        if (ta != null && ta == 0 && cell.rat == Rat.LTE && tacVisits >= 5 &&
            data.optBoolean("taEverNonZero", false)
        ) {
            findings += CatcherFinding(
                id = "timing_advance_zero", severity = Severity.HIGH,
                title = "Transmitter within 78m (LTE timing advance = 0)",
                detail = "LTE timing advance of 0 means the transmitter is less than 78m away. Macro cell towers are never this close. A portable IMSI catcher in a vehicle or nearby building would produce exactly this."
            )
        }

        // 10 — Signal spike between consecutive readings, while stationary.
        // Walking out of a building swings the signal by more than this, so a spike
        // only says something when you have not moved.
        val prevDbm = prev?.signalDbm
        val curDbm = cell.signalDbm
        if (stationary && prevDbm != null && curDbm != null && prev?.key == cell.key) {
            val spike = curDbm - prevDbm
            if (spike >= 20) {
                findings += CatcherFinding(
                    id = "signal_spike", severity = Severity.MEDIUM,
                    title = "Sudden signal spike on same cell",
                    detail = "Signal on ${cell.cellId} jumped by ${spike}dB in one reading cycle. Stationary macro cells have stable signal; a vehicle-borne transmitter moving closer produces sudden spikes."
                )
            }
        }

        // 11 — Rapid RAT oscillation (forced downgrade/upgrade cycle indicates IMSI paging).
        // Only while stationary: a drive across town legitimately walks 5G → LTE → 3G
        // and back, which is not evidence of anything.
        val recentRats = recent.takeLast(8).map { it.cell.rat }.distinct()
        if (stationary && recentRats.size >= 3) {
            findings += CatcherFinding(
                id = "rat_oscillation", severity = Severity.HIGH,
                title = "Rapid radio technology switching",
                detail = "Device switched between ${recentRats.joinToString { it.label }} technologies in the last 2 min while stationary. IMSI catchers force devices through technology cycles to capture authentication events."
            )
        }

        // 12 — Cell active for < 90s then replaced (ephemeral, stricter threshold)
        for ((key, e) in ephemeral) {
            if (e.gone && key != cell.key && !e.reported) {
                val lifetime = now - e.registeredAt
                if (lifetime in 30_000L..90_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell_strict:${e.cell.cellId}", severity = Severity.HIGH,
                        title = "Very brief serving cell (${lifetime / 1000}s)",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) served as primary cell for only ${lifetime / 1000}s. Real base stations serve continuously for hours; portable catchers connect briefly then move on."
                    )
                    e.reported = true
                }
            }
        }

        // The Cell tab keys its list on the finding id, so a duplicate id is a crash
        // rather than a cosmetic problem. Guarantee uniqueness here.
        return findings.distinctBy { it.id }
    }

    private fun updateBaseline(cell: ServingCell, now: Long) {
        val data = store.json
        val cells = data.optJSONObject("cells") ?: JSONObject().also { data.put("cells", it) }
        val tacs = data.optJSONObject("tacs") ?: JSONObject().also { data.put("tacs", it) }
        val cellTacs = data.optJSONObject("cellTacs") ?: JSONObject().also { data.put("cellTacs", it) }

        val tKey = tacKey(cell)
        val ctKey = ctKey(cell)

        val cellRec = cells.optJSONObject(cell.key) ?: JSONObject().also { cells.put(cell.key, it) }
        if (!cellRec.has("firstSeen")) cellRec.put("firstSeen", now)
        cellRec.put("lastSeen", now)
        cellRec.put("count", cellRec.optInt("count", 0) + 1)
        cellRec.put("rat", cell.rat.name)

        val tacRec = tacs.optJSONObject(tKey) ?: JSONObject().also { tacs.put(tKey, it) }
        // A visit is a first sighting, or a return after more than VISIT_GAP_MS away.
        // Polls in between only move lastSeenTs forward, so a long stay is one visit.
        val lastSeenTs = tacRec.optLong("lastSeenTs", 0L)
        if (lastSeenTs == 0L || now - lastSeenTs > VISIT_GAP_MS) {
            tacRec.put("visits", tacRec.optInt("visits", 0) + 1)
        }
        tacRec.put("lastSeenTs", now)
        // Written by earlier versions as a per-poll count; meaningless as a visit count.
        tacRec.remove("maturity")
        if (cell.rat.rank > tacRec.optInt("maxRatRank", 0)) tacRec.put("maxRatRank", cell.rat.rank)
        val dbm = cell.signalDbm
        if (dbm != null && dbm > tacRec.optInt("maxDbm", -160)) tacRec.put("maxDbm", dbm)

        val knownCells = tacRec.optJSONArray("cells") ?: JSONArray().also { tacRec.put("cells", it) }
        var alreadyKnown = false
        for (i in 0 until knownCells.length()) if (knownCells.optString(i) == cell.cellId) { alreadyKnown = true; break }
        if (!alreadyKnown) knownCells.put(cell.cellId)

        // Only remember a tracking area we actually read. Recording "?" for a modem
        // that withheld it turns every later reading into a false mismatch.
        if (cell.tac != null && !cellTacs.has(ctKey)) cellTacs.put(ctKey, cell.tac)

        // Remember whether this handset reports neighbour counts and timing advance at
        // all. The heuristics that key off "zero" are meaningless on a modem that
        // never reports anything else, and have to stay quiet there.
        if ((cell.neighbors ?: 0) > 0) data.put("neighborsEverSeen", true)
        if ((cell.timingAdvance ?: 0) > 0) data.put("taEverNonZero", true)

        store.touch()
    }

    fun scoreAndLevel(findings: List<CatcherFinding>): Pair<Int, Threat> {
        // Ephemeral findings carry the cell in their id to stay unique; score on the
        // heuristic name in front of the colon.
        val score = findings.sumOf {
            when (it.id.substringBefore(':')) {
                "rat_downgrade" -> 35; "cellid_tac_mismatch" -> 30
                "tac_change_stationary" -> 25; "signal_outlier" -> 22
                "unknown_cell" -> 20; "ephemeral_cell" -> 20
                "cell_flapping" -> 18; "no_neighbors" -> 15
                "timing_advance_zero" -> 28; "signal_spike" -> 18
                "rat_oscillation" -> 30; "ephemeral_cell_strict" -> 32
                else -> 10
            }.toInt()
        }.coerceIn(0, 100)
        val level = when {
            score >= 80 -> Threat.CRITICAL; score >= 50 -> Threat.HIGH
            score >= 30 -> Threat.MEDIUM; score > 0 -> Threat.LOW; else -> Threat.NONE
        }
        return score to level
    }

    /** Wipes the learned baseline and the in-memory history behind it. */
    @Synchronized
    fun resetBaseline() {
        recent.clear()
        ephemeral.clear()
        prev = null
        prevFix = null
        store.replace(
            JSONObject()
                .put("cells", JSONObject())
                .put("tacs", JSONObject())
                .put("cellTacs", JSONObject())
        )
    }

    private companion object {
        /** Time away from an area after which being back there counts as a new visit. */
        const val VISIT_GAP_MS = 30 * 60_000L
    }

    fun stats(): Triple<Int, Int, Float> {
        val data = store.json
        val cellsObj = data.optJSONObject("cells")
        val uniqueCells = cellsObj?.length() ?: 0
        var totalObs = 0
        if (cellsObj != null) {
            val keys = cellsObj.keys()
            while (keys.hasNext()) totalObs += cellsObj.optJSONObject(keys.next())?.optInt("count", 0) ?: 0
        }
        val maturity = (totalObs.coerceIn(0, 50) / 50f)
        return Triple(uniqueCells, totalObs, maturity)
    }
}
