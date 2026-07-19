package app.aaps.plugins.aps.openAPSBoost

import org.json.JSONArray
import org.json.JSONObject

/**
 * BoostMlFeatureBuilder — builds the 53-feature vector v12 expects from on-cycle
 * algorithm state, and maintains a 6-cycle ring buffer for the 6 lookback features.
 *
 * v12 feature schema (order matters — must match assets/boost/hypo_risk_model.json):
 *   0..7   v9 base features: cgm_mgdl, iob_iob, iob_basaliob, bg_above_target,
 *          direction_num, hour, iob_activity, sug_insulinReq
 *   8..16  v10 extended features: sug_COB, sug_eventualBG, sug_expectedDelta,
 *          sug_minDelta, sug_TDD, iob_bolusiob, iob_netbasalinsulin,
 *          recent_smb_units_60m, time_since_last_smb_min
 *   17..52 v12 windowed lookback (lag0..lag5) for: cgm_mgdl, iob_iob,
 *          iob_activity, sug_eventualBG, recent_smb_units_60m, sug_minDelta
 *
 * The ring buffer holds the last 6 cycles' values for the 6 windowed features and
 * is persisted across plugin restarts via [serializeBuffer]/[deserializeBuffer] into
 * StringKey.ApsBoostMlRingBuffer (orchestrated by the plugin, not this object). On cold
 * start (empty buffer), lag values default to
 * the current cycle's value (zero-imputed in Python's median-fill is roughly
 * equivalent to "no change since last cycle").
 */
object BoostMlFeatureBuilder {

    const val LOOKBACK = 6
    val LOOKBACK_FEATURES = listOf(
        "cgm_mgdl", "iob_iob", "iob_activity",
        "sug_eventualBG", "recent_smb_units_60m", "sug_minDelta"
    )

    /**
     * One row in the ring buffer — current-cycle values for the 6 lookback features.
     * Stored as JSON for portability across schema changes.
     */
    data class CycleSnapshot(
        val ts: Long,
        val cgmMgdl: Double,
        val iobIob: Double,
        val iobActivity: Double,
        val sugEventualBG: Double,
        val recentSmbUnits60m: Double,
        val sugMinDelta: Double
    ) {
        fun valueOf(name: String): Double = when (name) {
            "cgm_mgdl"             -> cgmMgdl
            "iob_iob"              -> iobIob
            "iob_activity"         -> iobActivity
            "sug_eventualBG"       -> sugEventualBG
            "recent_smb_units_60m" -> recentSmbUnits60m
            "sug_minDelta"         -> sugMinDelta
            else                   -> 0.0
        }
        fun toJson(): JSONObject = JSONObject()
            .put("ts", ts)
            .put("cgm_mgdl", cgmMgdl)
            .put("iob_iob", iobIob)
            .put("iob_activity", iobActivity)
            .put("sug_eventualBG", sugEventualBG)
            .put("recent_smb_units_60m", recentSmbUnits60m)
            .put("sug_minDelta", sugMinDelta)
    }

    data class RingBuffer(val snapshots: MutableList<CycleSnapshot> = mutableListOf()) {
        fun push(s: CycleSnapshot) {
            snapshots.add(s)
            while (snapshots.size > LOOKBACK) snapshots.removeAt(0)
        }
        /** Get the snapshot `lag` cycles ago (0 = most recent). Returns null if buffer too short. */
        fun lagged(lag: Int): CycleSnapshot? {
            val idx = snapshots.size - 1 - lag
            return if (idx in 0..snapshots.lastIndex) snapshots[idx] else null
        }
    }

    fun serializeBuffer(b: RingBuffer): String {
        val arr = JSONArray()
        for (s in b.snapshots) arr.put(s.toJson())
        return arr.toString()
    }

    fun deserializeBuffer(raw: String): RingBuffer {
        val b = RingBuffer()
        if (raw.isBlank()) return b
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                b.push(CycleSnapshot(
                    ts = o.optLong("ts", 0L),
                    cgmMgdl = o.optDouble("cgm_mgdl", 0.0),
                    iobIob = o.optDouble("iob_iob", 0.0),
                    iobActivity = o.optDouble("iob_activity", 0.0),
                    sugEventualBG = o.optDouble("sug_eventualBG", 0.0),
                    recentSmbUnits60m = o.optDouble("recent_smb_units_60m", 0.0),
                    sugMinDelta = o.optDouble("sug_minDelta", 0.0)
                ))
            }
            b
        } catch (e: Exception) { RingBuffer() }
    }

    /**
     * Build the full feature vector ordered to match the model's declared
     * `featureNames`. If the model isn't loaded the caller should fall back to
     * the legacy 8-feature path.
     *
     * @param featureNames model.featureNames in declared order (length 53 for v12).
     * @param current      current-cycle snapshot (already pushed to ring before this).
     * @param ring         ring buffer holding the last 6 cycles (current included).
     * @param staticValues map of static (non-windowed) feature name → value.
     * @return DoubleArray sized to match `featureNames`.
     */
    fun build(
        featureNames: List<String>,
        current: CycleSnapshot,
        ring: RingBuffer,
        staticValues: Map<String, Double>
    ): DoubleArray {
        val out = DoubleArray(featureNames.size)
        for (i in featureNames.indices) {
            val name = featureNames[i]
            val lagMarker = name.indexOf("_lag")
            if (lagMarker > 0) {
                val baseName = name.substring(0, lagMarker)
                val lag = name.substring(lagMarker + 4).toIntOrNull() ?: 0
                val snap = ring.lagged(lag) ?: current
                out[i] = snap.valueOf(baseName)
            } else {
                out[i] = staticValues[name] ?: 0.0
            }
        }
        // Defensive: never feed NaN/Inf to the tree model — a non-finite feature would force a
        // deterministic (wrong) branch instead of the median-fill the model was trained on. Replace
        // with 0.0 so a degenerate upstream value degrades gracefully rather than skewing risk.
        for (i in out.indices) if (!out[i].isFinite()) out[i] = 0.0
        return out
    }
}
