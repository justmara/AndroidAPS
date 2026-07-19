package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSBoost.DailyStepHistoryTracker.DailyTotal
import kotlin.math.max

/**
 * WearStepSource — Boost activity-load (2026-06-24). Bridges AAPS Wear step data into Boost.
 *
 * AAPS Wear's StepCountListener sends `ActionStepsRate` (steps over rolling 5/15/30/60/180-min
 * windows) to the phone, persisted to the `stepsCount` (SC) table. Worn on the wrist, this is a
 * more faithful step source than the phone pedometer (which only counts when carried) and entirely
 * independent of Garmin Connect / Health Connect. These pure helpers turn the SC rows into the two
 * things Boost's activity-load shadow needs: a freshness check and a cumulative-today total.
 *
 * SHADOW: the activity-load that consumes this is logged-only; nothing here touches dosing.
 */
object WearStepSource {

    private const val FIVE_MIN_MS = 300_000L
    /** A wear feed is "live" if it produced a row within this window. ~2–3 sampling intervals. */
    const val FRESH_MS = 12 * 60_000L

    /** A single 5-min bucket above this is a glitching counter (raw cumulative-since-boot leaked in);
     *  such buckets are skipped so one garbage spike can't inflate the today / daily-baseline totals.
     *  Mirrors MetricsSampler's per-bucket cap. */
    private const val MAX_PLAUSIBLE_BUCKET_STEPS = 1500

    fun latest(scList: List<SC>): SC? = scList.maxByOrNull { it.timestamp }

    /** The 5/15/30/60-min step windows the Boost engine reacts to. */
    data class RecentWindows(val m5: Int, val m15: Int, val m30: Int, val m60: Int)

    /** All rolling-window step fields (5/10/15/30/60/180 min) from the phone-sensor sampler rows —
     *  the superset AutoISF needs on top of the four the Boost engine uses. */
    data class PhoneWindows(val m5: Int, val m10: Int, val m15: Int, val m30: Int, val m60: Int, val m180: Int)

    /** MetricsSampler tags its own phone-pedometer rows with this suffix (see MetricsSamplerImpl
     *  `phoneSensorDevice = "<manufacturer> <model> sensor"`). Such rows are EXCLUDED below so that
     *  "no worn source" resolves to the more accurate, continuous live phone pedometer ([StepService],
     *  the caller's fallback) rather than the sampler's coarser SC rows, which drop deltas after a
     *  sampling gap and can under-report the 60-min window. */
    private const val PHONE_SAMPLER_SUFFIX = " sensor"

    /**
     * Recent step windows from a WORN source (AAPS Wear, or a Health-Connect writer such as Garmin)
     * via the SC table, or null when none is fresh — in which case the caller falls back to the live
     * phone pedometer. Restores the pre-Boost-V6 "watch first, else phone" behaviour.
     *
     * Only rows within [FRESH_MS], NOT written by the phone-sensor sampler ([PHONE_SAMPLER_SUFFIX]) and
     * with a plausible 5-min bucket ([MAX_PLAUSIBLE_BUCKET_STEPS], skipping a glitching-counter spike that
     * inflates every window field at once) qualify. The feed writes 6 rows per timestamp with
     * progressively-filled window fields (the 5-min
     * row has only steps5min, the 180-min row has them all), inserted one at a time; we take the MAX of
     * each field across all rows at the newest qualifying timestamp, so a partially written timestamp —
     * or a read landing mid-insert — still yields the full window set.
     */
    fun recentWindows(scList: List<SC>, nowMs: Long): RecentWindows? {
        val worn = scList.filter {
            nowMs - it.timestamp <= FRESH_MS && !it.device.endsWith(PHONE_SAMPLER_SUFFIX) &&
                it.steps5min in 0..MAX_PLAUSIBLE_BUCKET_STEPS // drop a glitching-counter row (all its window fields are inflated together)
        }
        val newestTs = worn.maxByOrNull { it.timestamp }?.timestamp ?: return null
        val rows = worn.filter { it.timestamp == newestTs }
        return RecentWindows(
            m5 = rows.maxOf { it.steps5min },
            m15 = rows.maxOf { it.steps15min },
            m30 = rows.maxOf { it.steps30min },
            m60 = rows.maxOf { it.steps60min }
        )
    }

    /**
     * Recent step windows from the newest fresh phone-sensor sampler ([PHONE_SAMPLER_SUFFIX]) SC row —
     * the SAME rows the overview graph shows — or null when none is fresh. Used when the user forces
     * "Phone steps only": the sampler rows survive OEM background limits (ColorOS/MIUI) better than the
     * continuously-registered [StepService], which such devices starve to a constant 0. Same
     * partial-write-tolerant MAX-across-rows-at-newest-timestamp read as [recentWindows], and the same
     * [MAX_PLAUSIBLE_BUCKET_STEPS] skip so a stale pre-guard garbage row can't leak into the APS debug line.
     */
    fun phoneWindows(scList: List<SC>, nowMs: Long): PhoneWindows? {
        val phone = scList.filter {
            nowMs - it.timestamp <= FRESH_MS && it.device.endsWith(PHONE_SAMPLER_SUFFIX) &&
                it.steps5min in 0..MAX_PLAUSIBLE_BUCKET_STEPS // drop a glitching-counter row (all its window fields are inflated together)
        }
        val newestTs = phone.maxByOrNull { it.timestamp }?.timestamp ?: return null
        val rows = phone.filter { it.timestamp == newestTs }
        return PhoneWindows(
            m5 = rows.maxOf { it.steps5min },
            m10 = rows.maxOf { it.steps10min },
            m15 = rows.maxOf { it.steps15min },
            m30 = rows.maxOf { it.steps30min },
            m60 = rows.maxOf { it.steps60min },
            m180 = rows.maxOf { it.steps180min }
        )
    }

    /** Live phone-pedometer buckets from [StepService] — the last-resort fallback shared by both paths. */
    private fun liveFallback() = RecentWindows(
        StepService.getRecentStepCount5Min(),
        StepService.getRecentStepCount15Min(),
        StepService.getRecentStepCount30Min(),
        StepService.getRecentStepCount60Min()
    )

    /**
     * [recentWindows] with the SC read and phone-pedometer fallback folded in, so both Boost plugins
     * share one implementation. Never throws — a DB failure degrades to the phone pedometer.
     *
     * When [phoneOnly] (the "Phone steps only" setting) is set, a worn source is ignored entirely and
     * the phone-sensor sampler rows are used ([phoneWindows]), falling back to the live [StepService].
     * Otherwise: a worn source's windows when fresh, else the live [StepService] buckets.
     */
    fun recentWindowsOrFallback(persistenceLayer: PersistenceLayer, nowMs: Long, phoneOnly: Boolean = false): RecentWindows {
        val scList = try { persistenceLayer.getStepsCountFromTime(nowMs - FRESH_MS) } catch (_: Throwable) { emptyList() }
        if (phoneOnly) {
            return phoneWindows(scList, nowMs)
                ?.let { RecentWindows(it.m5, it.m15, it.m30, it.m60) }
                ?: liveFallback()
        }
        return recentWindows(scList, nowMs) ?: liveFallback()
    }

    /** True when the watch has reported steps recently — i.e. it's worn and sampling. */
    fun isFresh(scList: List<SC>, nowMs: Long): Boolean =
        latest(scList)?.let { nowMs - it.timestamp <= FRESH_MS } ?: false

    /**
     * Cumulative steps since [dayStartMs] reconstructed from the rolling 5-min windows: take one
     * `steps5min` value per non-overlapping 5-min slot (the max seen in that slot, since the watch
     * samples more often than every 5 min and windows overlap) and sum. Approximate but stable, and
     * free of the double-counting that summing overlapping windows would cause.
     */
    fun stepsToday(scList: List<SC>, dayStartMs: Long, nowMs: Long): Int {
        val perSlot = HashMap<Long, Int>()
        for (sc in scList) {
            if (sc.timestamp < dayStartMs || sc.timestamp > nowMs) continue
            if (sc.steps5min !in 0..MAX_PLAUSIBLE_BUCKET_STEPS) continue // skip a glitching-counter spike
            val slot = sc.timestamp / FIVE_MIN_MS
            perSlot[slot] = max(perSlot.getOrDefault(slot, 0), sc.steps5min)
        }
        return perSlot.values.sum()
    }

    /**
     * Per-COMPLETED-local-day step totals from the SC table, for the multi-source baseline history
     * (tagged source "wear"). Same per-5-min-slot-max dedup as [stepsToday], applied within each
     * local day; today (dayIndex ≥ [todayIndex]) is excluded as it's still partial.
     */
    fun dailyTotals(scList: List<SC>, todayIndex: Long, offsetMs: Long): List<DailyTotal> {
        val perDaySlot = HashMap<Long, HashMap<Long, Int>>()
        for (sc in scList) {
            val day = DailyStepHistoryTracker.dayIndex(sc.timestamp, offsetMs)
            if (day >= todayIndex) continue
            if (sc.steps5min !in 0..MAX_PLAUSIBLE_BUCKET_STEPS) continue // skip a glitching-counter spike
            val slot = sc.timestamp / FIVE_MIN_MS
            perDaySlot.getOrPut(day) { HashMap() }.let { m -> m[slot] = max(m.getOrDefault(slot, 0), sc.steps5min) }
        }
        return perDaySlot.map { (day, slots) -> DailyTotal(day, slots.values.sum(), StepSourceResolver.WEAR) }
            .sortedBy { it.dayIndex }
    }
}
