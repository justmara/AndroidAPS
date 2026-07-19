package app.aaps.plugins.aps.openAPSBoost

/**
 * HrSourceResolver — Boost HR source visibility (2026-06-28).
 *
 * HR (unlike steps) is ALREADY a unified multi-source feed: Garmin's Connect-IQ side-channel, an
 * AAPS Wear watch, and Health-Connect backfill all write the one `heartRate` table, consumers read
 * it source-agnostically, and [HealthConnectHrIngest] dedups HC against the live feed (live wins).
 * A BPM is a BPM, so — unlike step counts — sources need no calibration or single-source selection.
 *
 * What was missing is VISIBILITY: which device is actually feeding HR right now, and whether the feed
 * has silently died (the failure steps suffered). This resolver groups recent readings by device,
 * reports per-source freshness/recency/count for NS, and names the live "primary" — without changing
 * any consumer. Pure / no I/O.
 *
 * Device tags in the table: "Garmin" (side-channel), "<MANUFACTURER> <MODEL>" (AAPS Wear, e.g.
 * "OPPO OWWE261"), and "<manufacturer model>" or "HealthConnect" (HC ingest). So a worn watch is
 * tagged by its model, NOT the word "wear" — classification keys off that.
 */
object HrSourceResolver {

    const val GARMIN = "garmin"
    const val HC = "hc"
    /** A worn watch reporting via AAPS Wear realtime — keyed by its model tag. */
    const val WORN_PREFIX = "worn:"

    /** A feed is "live" if its newest reading is within this window (~2–3 Wear sampling intervals). */
    const val FRESH_MS = 12 * 60_000L

    fun canonical(device: String): String {
        val d = device.trim()
        return when {
            d.contains("garmin", ignoreCase = true) -> GARMIN
            d.equals("HealthConnect", ignoreCase = true) || d.isBlank() -> HC
            else -> WORN_PREFIX + d
        }
    }

    /** Trust tier (lower = better): a realtime worn feed beats Health-Connect backfill. */
    fun tier(canonicalSource: String): Int = when {
        canonicalSource == GARMIN -> 0
        canonicalSource.startsWith(WORN_PREFIX) -> 0
        canonicalSource == HC -> 1
        else -> 2
    }

    /** Minimal view of a persisted HR row (decoupled from the DB model for testability). */
    data class Reading(val device: String, val timestampMs: Long)

    data class SourceState(val source: String, val count: Int, val ageMs: Long) {
        fun fresh() = ageMs <= FRESH_MS
    }

    data class Resolution(
        /** The live HR source (highest-trust fresh); null when nothing is fresh (feed died/absent). */
        val active: String?,
        val anyFresh: Boolean,
        /** Per-source diagnostics, best-trust first: "src(fresh,count,ageMin)". */
        val note: String,
        val states: List<SourceState>
    )

    fun resolve(readings: List<Reading>, nowMs: Long): Resolution {
        if (readings.isEmpty()) return Resolution(null, false, "none", emptyList())
        val states = readings.groupBy { canonical(it.device) }
            .map { (src, rs) -> SourceState(src, rs.size, nowMs - rs.maxOf { it.timestampMs }) }
            .sortedWith(compareBy({ tier(it.source) }, { it.ageMs }))
        val active = states.filter { it.fresh() }
            .minWithOrNull(compareBy({ tier(it.source) }, { it.ageMs }))
        val note = states.joinToString(" ") {
            "${it.source}(${if (it.fresh()) "f" else "-"},${it.count},${it.ageMs / 60_000L}m)"
        }
        return Resolution(active?.source, active != null, note, states)
    }
}
