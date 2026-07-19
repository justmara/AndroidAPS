package app.aaps.core.interfaces.metrics

/**
 * Samples activity metrics (step count, heart rate) on demand and stores them in the database.
 *
 * It is invoked from the calculation workflow (triggered by a new BG value) so that steps and
 * heart rate are refreshed only when the other metrics (IOB/COB) are recomputed. This avoids
 * continuous sensor listening and keeps battery/memory usage low.
 *
 * Source priority chain (per metric):
 *  1. The standard mechanism (AAPS Wear / Garmin) — if a recent value is already present it wins
 *     and nothing is overwritten.
 *  2. Health Connect — aggregated steps / average heart rate (captures e.g. a Garmin watch synced
 *     through Garmin Connect).
 *  3. The phone's own step-counter sensor (steps only) as a last-resort fallback.
 */
interface MetricsSampler {

    /** Sample steps and heart rate now and persist them, honouring the source priority chain. */
    fun sampleNow()
}
