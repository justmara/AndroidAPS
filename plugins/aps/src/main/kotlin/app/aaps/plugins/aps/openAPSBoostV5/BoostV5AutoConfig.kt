package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.math.max
import kotlin.math.min

/**
 * Boost V5 auto-configuration — derive sensible initial V5 knobs from the user's own V1 dosing
 * history (default: last 14 days) when they first switch to the Boost V5 plugin.
 *
 * Rationale (see SHADOW_EQUIVALENCE_REPORT): V5's dose calibration is a *heuristic co-adapted with
 * the user*, so the safe onboarding is to **start where their V1 left off**, not on cohort defaults.
 * This computes a conservative, transparent suggestion from their proven dosing + glycaemia.
 *
 * PURE function — no Android / no I/O. The plugin gathers the [V1Profile] and applies + logs the
 * result. It is **suggestion** logic: the caller only writes knobs still at their factory default
 * (never overrides a user who has already tuned them) and surfaces what it set.
 *
 * Design principles:
 *  - **Conservative.** Never auto-RAISE aggression above neutral (1.0); only ease it down for a
 *    hypo-prone history. Safety knobs (HypoCaution, caps) are derived to bound, not to embolden.
 *  - **Carry proven constraints.** maxIOB / bolus cap mirror the user's existing AAPS values.
 *  - **Refine later.** Aggression can only be matched precisely once shadow data exists (paired
 *    V1/V5 cycles); the day-1 value is a gentle starting point, intentionally on the cautious side.
 */
object BoostV5AutoConfig {

    // Minimum data before we'll auto-configure at all (else leave factory defaults).
    const val MIN_DAYS = 7
    const val MIN_BG_READINGS = 1500          // ~7 days of 5-min CGM minus gaps

    // Glycaemic thresholds that trigger extra caution (international consensus targets).
    private const val TBR70_TARGET = 4.0      // % time <70 mg/dL
    private const val SEV54_TARGET = 1.0      // % time <54 mg/dL

    /** What the plugin gathers from the user's last-N-day V1 history. */
    data class V1Profile(
        val daysWithData: Int,
        val bgReadingCount: Int,
        val tddMedianU: Double,
        val manualBolusesU: List<Double>,     // NORMAL (meal/manual) boluses
        val smbAmountsU: List<Double>,        // SMB micro-boluses
        val tbrBelow70Pct: Double,
        val timeBelow54Pct: Double,
        val meanGlucoseMgdl: Double,
        val currentMaxIobU: Double,           // the user's existing AAPS maxIOB
        val currentMaxBolusU: Double          // the user's existing AAPS max bolus
    )

    /** Suggested V5 knobs (each already clamped to its preference range) + human-readable reasons. */
    data class V5Suggestion(
        val aggression: Double,
        val hypoCaution: Double,
        val confirmedCapU: Double,
        val committedCapU: Double,
        val cumulativeSmbCap60MinU: Double,
        val maxIobU: Double,
        val bolusCapU: Double,
        val fastCarbConfirm: Boolean,
        val rationale: List<String>
    )

    /** Returns null when there isn't enough data to responsibly auto-configure. */
    fun compute(p: V1Profile): V5Suggestion? {
        if (p.daysWithData < MIN_DAYS || p.bgReadingCount < MIN_BG_READINGS) return null

        val reasons = mutableListOf<String>()
        val hypoProne = p.timeBelow54Pct > 1.5 || p.tbrBelow70Pct > 6.0

        // HypoCaution [1.0..2.0]: scale up with time-below-range above target.
        val cautionRaw = 1.0 +
            max(0.0, p.tbrBelow70Pct - TBR70_TARGET) / 4.0 +       // +1.0 per +4% TBR over target
            max(0.0, p.timeBelow54Pct - SEV54_TARGET) * 0.5         // +0.5 per +1% severe over target
        val hypoCaution = round1(cautionRaw.coerceIn(1.0, 2.0))
        reasons += "HypoCaution $hypoCaution (TBR<70 ${pct(p.tbrBelow70Pct)}, <54 ${pct(p.timeBelow54Pct)} vs targets 4%/1%)"

        // Aggression [0.7..1.3]: NEVER auto-raise above 1.0. Ease down for a hypo-prone history.
        val aggression = round2(
            when {
                p.timeBelow54Pct > 1.5 || p.tbrBelow70Pct > 6.0 -> 0.85
                p.tbrBelow70Pct > TBR70_TARGET                  -> 0.92
                else                                            -> 1.0
            }
        )
        reasons += "Aggression $aggression (start ${if (aggression < 1.0) "gentle — hypo history" else "neutral"}; refines after shadow period)"

        // Confirmed cap [1.5..7.5]: cover their biggest typical single dose (meal bolus p90 or SMB p95).
        val confirmedCapU = round2(
            max(percentile(p.manualBolusesU, 90.0), percentile(p.smbAmountsU, 95.0)).coerceIn(1.5, 7.5)
        )
        reasons += "Confirmed cap ${confirmedCapU}U (≈ your biggest typical single dose)"

        // Committed cap [0.25..2.5]: routine per-cycle hold ≈ typical SMB (p75), floored.
        val committedCapU = round2(
            max(percentile(p.smbAmountsU, 75.0), p.tddMedianU / 40.0).coerceIn(0.25, 2.5)
        )
        reasons += "Committed cap ${committedCapU}U (≈ your routine SMB size)"

        // Rolling-60-min cumulative SMB cap: bounds dose *frequency* (the per-shot caps only bound
        // magnitude). Allow ~one confirm shot plus a couple of holds per hour. Upper bound is at
        // least confirmedCapU so the hourly budget can never sit BELOW a single confirmed shot for a
        // big-meal user (confirmedCap up to 7.5). (Review 2026-06-26, LOW correctness.)
        val cumulativeSmbCap60MinU = round1((confirmedCapU + 2.0 * committedCapU).coerceIn(1.0, max(5.0, confirmedCapU)))
        reasons += "Cumulative SMB cap/60min ${cumulativeSmbCap60MinU}U (limits dose frequency)"

        // Carry proven constraints.
        val maxIobU = round1(p.currentMaxIobU.coerceIn(0.1, 12.0))
        val bolusCapU = round1(p.currentMaxBolusU.coerceIn(0.1, 10.0))
        reasons += "maxIOB ${maxIobU}U / bolus cap ${bolusCapU}U carried from your AAPS settings"

        // Fast-carb confirm: keep on unless markedly hypo-prone (then off for caution).
        val fastCarbConfirm = !hypoProne
        if (hypoProne) reasons += "Fast-carb confirm OFF (cautious start — notable hypo history)"

        return V5Suggestion(
            aggression = aggression, hypoCaution = hypoCaution,
            confirmedCapU = confirmedCapU, committedCapU = committedCapU,
            cumulativeSmbCap60MinU = cumulativeSmbCap60MinU,
            maxIobU = maxIobU, bolusCapU = bolusCapU,
            fastCarbConfirm = fastCarbConfirm, rationale = reasons
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────
    /** Linear-interpolated percentile (0..100) of a value list; 0.0 if empty. */
    fun percentile(values: List<Double>, p: Double): Double {
        val v = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (v.isEmpty()) return 0.0
        if (v.size == 1) return v[0]
        val rank = (p / 100.0) * (v.size - 1)
        val lo = rank.toInt()
        val hi = min(lo + 1, v.size - 1)
        val frac = rank - lo
        return v[lo] + (v[hi] - v[lo]) * frac
    }

    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
    private fun pct(x: Double) = "${Math.round(x * 10.0) / 10.0}%"
}
