package app.aaps.plugins.sync.metrics

import android.os.Build
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.metrics.MetricsSampler
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand sampler honouring the source priority chain wear/Garmin -> Health Connect -> phone sensor.
 *
 * For steps it writes one [SC] row per time window (mirroring the AAPS Wear mechanism) so existing
 * consumers (overview graph, [app.aaps.plugins.automation] steps trigger, Nightscout) work unchanged.
 * Own writes are tagged via the [SC.device] / [HR.device] field so the standard mechanism is never
 * overwritten.
 */
@Singleton
class MetricsSamplerImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val healthConnectSource: HealthConnectSource,
    private val phoneStepSensor: PhoneStepSensor,
    private val preferences: Preferences
) : MetricsSampler {

    private companion object {

        const val DEVICE_HEALTH_CONNECT = "Health Connect"
        val WINDOWS_MIN = listOf(5L, 10L, 15L, 30L, 60L, 180L)
        val SKIP_WINDOW_MS = T.mins(6).msecs()
        val BUCKET_MS = T.mins(5).msecs()

        /** Upper sanity bound for one ~5-min bucket delta. A sprinter tops out near ~3 steps/s
         *  (~900 in 5 min); anything past this is a glitching hardware TYPE_STEP_COUNTER (seen reporting
         *  its raw cumulative-since-boot value, hundreds of thousands). Such deltas are dropped, and any
         *  garbage already stored is excluded from the window sums, so the graph and engine self-heal. */
        const val MAX_PLAUSIBLE_BUCKET_STEPS = 1500L
    }

    private val phoneSensorDevice = "${Build.MANUFACTURER} ${Build.MODEL} sensor"
    private val ownStepsDevices = setOf(DEVICE_HEALTH_CONNECT, phoneSensorDevice)

    /** Baseline cumulative step-counter value for delta computation in the phone-sensor fallback. */
    private var lastCumulative: Long? = null

    /** Timestamp of the previous phone-sensor sample, to detect sampling gaps. */
    private var lastSampleTime: Long? = null

    override fun sampleNow() {
        runCatching { sampleSteps() }.onFailure { aapsLogger.error(LTag.CORE, "sampleSteps failed", it) }
        runCatching { sampleHeartRate() }.onFailure { aapsLogger.error(LTag.CORE, "sampleHeartRate failed", it) }
    }

    private fun sampleSteps() {
        val now = dateUtil.now()
        // "Phone steps only": force the phone step-counter as the sole source, so keep sampling it even
        // when a worn source or Health Connect is present (they'd otherwise short-circuit the phone below).
        val phoneStepsOnly = preferences.get(BooleanKey.OverviewAlwaysUsePhoneSteps)
        // 1. Standard mechanism (AAPS Wear) already provided a recent value -> leave it alone.
        if (!phoneStepsOnly && persistenceLayer.getStepsCountFromTime(now - SKIP_WINDOW_MS).any { it.device !in ownStepsDevices }) {
            aapsLogger.debug(LTag.CORE, "Steps: standard mechanism present, sampler skips")
            return
        }
        // 2. Health Connect (captures e.g. a Garmin watch synced through Garmin Connect).
        if (!phoneStepsOnly) healthConnectSource.readSteps(Instant.ofEpochMilli(now), WINDOWS_MIN)?.let { hc ->
            storeSteps(now, hc, DEVICE_HEALTH_CONNECT)
            return
        }
        // 3. Phone step-counter fallback: delta of the cumulative counter since the previous sample.
        val cumulative = phoneStepSensor.readCumulativeSteps() ?: return
        val previous = lastCumulative
        val previousTime = lastSampleTime
        lastCumulative = cumulative
        lastSampleTime = now
        if (previous == null || cumulative < previous) return // first sample or reboot -> re-baseline only
        // Skip if we missed sampling for longer than one bucket: attributing the whole
        // accumulated delta to a single 5-min bar would spike the graph scale and hide
        // the rest of the data. Just re-baseline instead.
        if (previousTime == null || now - previousTime > SKIP_WINDOW_MS) return
        // Reject an implausible forward jump before it poisons the windows: a glitching hardware
        // TYPE_STEP_COUNTER can report its raw cumulative-since-boot value (hundreds of thousands),
        // which would land in a bucket and inflate the 60/180-min sums. Computed in Long so an
        // out-of-Int-range delta is caught, not silently wrapped negative.
        val deltaLong = cumulative - previous
        if (deltaLong > MAX_PLAUSIBLE_BUCKET_STEPS) {
            aapsLogger.debug(LTag.CORE, "Steps: implausible delta $deltaLong from $phoneSensorDevice, re-baselining")
            return
        }
        val delta = deltaLong.toInt()
        // Also drop any already-stored garbage bucket (from before this guard existed / a past glitch) so
        // it can't leak into the wider windows — the graph and engine then self-heal on the next sample
        // instead of carrying the spike for up to 180 min until it ages out.
        val ownBuckets = persistenceLayer.getStepsCountFromTime(now - T.mins(180).msecs())
            .filter { it.device == phoneSensorDevice && it.duration == BUCKET_MS && it.steps5min in 0..MAX_PLAUSIBLE_BUCKET_STEPS.toInt() }
        val windows = WINDOWS_MIN.associateWith { minutes ->
            delta + ownBuckets.filter { it.timestamp > now - minutes * 60_000 }.sumOf { it.steps5min }
        }
        storeSteps(now, windows, phoneSensorDevice)
    }

    /** Writes one [SC] row per window, mirroring the AAPS Wear [StepCountListener] layout. */
    private fun storeSteps(now: Long, w: Map<Long, Int>, device: String) {
        fun s(min: Long) = w[min] ?: 0
        val rows = listOf(
            SC(duration = T.mins(5).msecs(), timestamp = now, steps5min = s(5), steps10min = 0, steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0, device = device),
            SC(duration = T.mins(10).msecs(), timestamp = now, steps5min = s(5), steps10min = s(10), steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0, device = device),
            SC(duration = T.mins(15).msecs(), timestamp = now, steps5min = s(5), steps10min = s(10), steps15min = s(15), steps30min = 0, steps60min = 0, steps180min = 0, device = device),
            SC(duration = T.mins(30).msecs(), timestamp = now, steps5min = s(5), steps10min = s(10), steps15min = s(15), steps30min = s(30), steps60min = 0, steps180min = 0, device = device),
            SC(duration = T.mins(60).msecs(), timestamp = now, steps5min = s(5), steps10min = s(10), steps15min = s(15), steps30min = s(30), steps60min = s(60), steps180min = 0, device = device),
            SC(duration = T.mins(180).msecs(), timestamp = now, steps5min = s(5), steps10min = s(10), steps15min = s(15), steps30min = s(30), steps60min = s(60), steps180min = s(180), device = device)
        )
        rows.forEach { persistenceLayer.insertOrUpdateStepsCount(it).blockingGet() }
        aapsLogger.debug(LTag.CORE, "Stored steps from $device: 5m=${s(5)} 15m=${s(15)} 30m=${s(30)} 60m=${s(60)}")
    }

    private fun sampleHeartRate() {
        val now = dateUtil.now()
        // 1. Standard mechanism (Garmin / AAPS Wear) already provided a recent value -> leave it alone.
        if (persistenceLayer.getHeartRatesFromTime(now - SKIP_WINDOW_MS).any { it.device != DEVICE_HEALTH_CONNECT }) {
            aapsLogger.debug(LTag.CORE, "HR: standard mechanism present, sampler skips")
            return
        }
        // 2. Health Connect average over the last 5 minutes.
        val bpm = healthConnectSource.readAverageHeartRate(Instant.ofEpochMilli(now), 5L) ?: return
        if (bpm <= 0) return
        val hr = HR(
            timestamp = now - BUCKET_MS,
            duration = BUCKET_MS,
            beatsPerMinute = bpm.toDouble(),
            device = DEVICE_HEALTH_CONNECT
        )
        persistenceLayer.insertOrUpdateHeartRate(hr).blockingGet()
        aapsLogger.debug(LTag.CORE, "Stored HR from Health Connect: $bpm BPM")
    }
}
