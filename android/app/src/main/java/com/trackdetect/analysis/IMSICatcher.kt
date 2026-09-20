package com.trackdetect.analysis

import com.trackdetect.CatcherFinding
import com.trackdetect.Severity
import com.trackdetect.ServingCell
import com.trackdetect.Store
import com.trackdetect.Threat
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
    private data class Ephemeral(val cell: ServingCell, val registeredAt: Long, var gone: Boolean = false, var reported: Boolean = false)

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
        val cells = data.optJSONObject("cells") ?: JSONObject()
        val tacs = data.optJSONObject("tacs") ?: JSONObject()
        val cellTacs = data.optJSONObject("cellTacs") ?: JSONObject()

        val tKey = tacKey(cell)
        val tacRec = tacs.optJSONObject(tKey)
        val tacMaturity = tacRec?.optInt("maturity", 0) ?: 0

        // 1 — RAT downgrade
        val maxRatRank = tacRec?.optInt("maxRatRank", 0) ?: 0
        if (tacMaturity >= 5 && cell.rat.rank in 1 until maxRatRank) {
            findings += CatcherFinding(
                id = "rat_downgrade", severity = Severity.HIGH,
                title = "Radio downgrade detected",
                detail = "Currently ${cell.rat.label} but this area previously used rank-$maxRatRank technology. Forced downgrade is the primary method used to intercept modern devices."
            )
        }

        // 2 — Cell ID / TAC mismatch
        val knownTac = cellTacs.optString(ctKey(cell), null)
        if (knownTac != null && knownTac != (cell.tac ?: "?")) {
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
        if (dbm != null && tacMaturity >= 5 && dbm > maxDbm + 15) {
            findings += CatcherFinding(
                id = "signal_outlier", severity = Severity.MEDIUM,
                title = "Unusually strong signal",
                detail = "Signal is $dbm dBm — ${dbm - maxDbm} dB above the previous maximum for this area ($maxDbm dBm). A transmitter close beside you looks exactly like this."
            )
        }

        // 5 — Unknown cell at familiar area
        if (tacMaturity >= 20) {
            val known = tacRec?.optJSONArray("cells") ?: JSONArray()
            var found = false
            for (i in 0 until known.length()) if (known.optString(i) == cell.cellId) { found = true; break }
            if (!found) {
                findings += CatcherFinding(
                    id = "unknown_cell", severity = Severity.MEDIUM,
                    title = "Unknown cell at familiar location",
                    detail = "Cell ${cell.cellId} has never appeared in area ${cell.tac}, but you have been here $tacMaturity times. New permanent towers are rare; portable ones are not."
                )
            }
        }

        // 6 — Ephemeral cell
        for ((key, e) in ephemeral) {
            if (e.gone && key != cell.key) {
                val lifetime = now - e.registeredAt
                if (lifetime < 5 * 60_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell", severity = Severity.MEDIUM,
                        title = "Ephemeral tower",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) appeared as serving cell then vanished within ${lifetime / 1000}s. Portable equipment is driven in and away."
                    )
                    e.gone = false
                }
            }
        }

        // 7 — Cell flapping while stationary
        val stationary = fix?.speed?.let { it < MOVING_SPEED_MS } ?: true
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

        // 8 — No neighbours
        if (cell.neighbors != null && cell.neighbors == 0 && tacMaturity >= 5) {
            findings += CatcherFinding(
                id = "no_neighbors", severity = Severity.LOW,
                title = "No visible neighbours",
                detail = "Serving cell reports zero neighbours. Real base stations always have overlapping coverage; a lone portable transmitter does not."
            )
        }

        // 9 — Timing advance zero on LTE (within ~78m of transmitter)
        // A TA of 0 on LTE means the modem is ≤78m from the transmitter.
        // Real macro cells are never this close; a catcher in a vehicle or building is.
        val ta = cell.timingAdvance
        if (ta != null && ta == 0 && cell.rat.name == "LTE" && tacMaturity >= 5) {
            findings += CatcherFinding(
                id = "timing_advance_zero", severity = Severity.HIGH,
                title = "Transmitter within 78m (LTE timing advance = 0)",
                detail = "LTE timing advance of 0 means the transmitter is less than 78m away. Macro cell towers are never this close. A portable IMSI catcher in a vehicle or nearby building would produce exactly this."
            )
        }

        // 10 — Signal spike between consecutive readings
        val prevDbm = prev?.signalDbm
        val curDbm = cell.signalDbm
        if (prevDbm != null && curDbm != null && prev?.key == cell.key) {
            val spike = curDbm - prevDbm
            if (spike >= 20) {
                findings += CatcherFinding(
                    id = "signal_spike", severity = Severity.MEDIUM,
                    title = "Sudden signal spike on same cell",
                    detail = "Signal on ${cell.cellId} jumped by ${spike}dB in one reading cycle. Stationary macro cells have stable signal; a vehicle-borne transmitter moving closer produces sudden spikes."
                )
            }
        }

        // 11 — Rapid RAT oscillation (forced downgrade/upgrade cycle indicates IMSI paging)
        val recentRats = recent.takeLast(8).map { it.cell.rat }.distinct()
        if (recentRats.size >= 3) {
            findings += CatcherFinding(
                id = "rat_oscillation", severity = Severity.HIGH,
                title = "Rapid radio technology switching",
                detail = "Device switched between ${recentRats.joinToString { it.label }} technologies in the last 2 min. IMSI catchers force devices through technology cycles to capture authentication events."
            )
        }

        // 12 — Cell active for < 90s then replaced (ephemeral, stricter threshold)
        for ((key, e) in ephemeral) {
            if (e.gone && key != cell.key && !e.reported) {
                val lifetime = now - e.registeredAt
                if (lifetime in 30_000L..90_000L) {
                    findings += CatcherFinding(
                        id = "ephemeral_cell_strict", severity = Severity.HIGH,
                        title = "Very brief serving cell (${lifetime / 1000}s)",
                        detail = "Cell ${e.cell.cellId} (${e.cell.rat.label}) served as primary cell for only ${lifetime / 1000}s. Real base stations serve continuously for hours; portable catchers connect briefly then move on."
                    )
                    e.reported = true
                }
            }
        }

        return findings
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
        tacRec.put("maturity", tacRec.optInt("maturity", 0) + 1)
        if (cell.rat.rank > tacRec.optInt("maxRatRank", 0)) tacRec.put("maxRatRank", cell.rat.rank)
        val dbm = cell.signalDbm
        if (dbm != null && dbm > tacRec.optInt("maxDbm", -160)) tacRec.put("maxDbm", dbm)

        val knownCells = tacRec.optJSONArray("cells") ?: JSONArray().also { tacRec.put("cells", it) }
        var alreadyKnown = false
        for (i in 0 until knownCells.length()) if (knownCells.optString(i) == cell.cellId) { alreadyKnown = true; break }
        if (!alreadyKnown) knownCells.put(cell.cellId)

        if (!cellTacs.has(ctKey)) cellTacs.put(ctKey, cell.tac ?: "?")

        store.touch()
    }

    fun scoreAndLevel(findings: List<CatcherFinding>): Pair<Int, Threat> {
        val score = findings.sumOf {
            when (it.id) {
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
