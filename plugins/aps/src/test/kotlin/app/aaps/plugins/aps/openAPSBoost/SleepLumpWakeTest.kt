package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.State
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.StepSample
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Lump-tolerant genuine-wake detection (2026-07-03 incident): wear-bridge steps arrive in LUMPS
 * (stepsToday 0 → 1326 by 06:02) that never co-fire with an HR rise inside a single 15-min bucket,
 * so the detector held SLEEPING until the 07:00 boundary and the wake learner (genuine wakes only)
 * starved — sleepLearnedWakeMin NULL after 48 sessions. Wake evidence is now cumulative stepsToday
 * growth over a trailing lookback AND HR above the wake floor for ≥2 consecutive cycles.
 *
 * Guarded invariants: BG alone NEVER wakes (not an input); a single HR spike never wakes (REM);
 * HR-without-steps never wakes (REM); the hard boundary exit is unchanged.
 */
class SleepLumpWakeTest {

    private val T = SleepStateDetector
    private val FIVE_MIN = 5 * 60_000L
    private val T0 = 1_000_000_000L

    private fun hr(t: Long, bpm: Double) = HR(duration = 60_000, timestamp = t, beatsPerMinute = bpm, device = "test")

    /** One 5-min cycle. hrResting 60 → sleepCap 69, wakeFloor 75. Night window 22:00–07:00. */
    private fun cycle(prev: State, nowMs: Long, minuteOfDay: Int, bpm: Double?, stepsToday: Int, steps15: Int = 0) =
        T.evaluate(
            prev,
            SleepStateDetector.Inputs(
                nowMs = nowMs, minuteOfDay = minuteOfDay,
                hrReadings = if (bpm != null) listOf(hr(nowMs - 60_000, bpm)) else emptyList(),
                hrResting = 60, stepsLast15Min = steps15, mlMealLikely = null,
                nightStartMin = 1320, nightEndMin = 420,
                stepsToday = stepsToday
            )
        )

    /** SLEEPING at 05:25 with a recent fresh HR sample (so the resume-wake path stays quiet). */
    private fun sleeping(atMs: Long) = State(
        state = SleepState.SLEEPING, enteredAtMs = atMs - 4 * 3_600_000L, lastFreshHrSampleMs = atMs - 60_000
    )

    @Test fun `step lump arriving before HR sustain does not wake`() {
        var s = sleeping(T0)
        var min = 325                                       // 05:25
        var t = T0
        // lump lands while HR is still asleep-low — 6 cycles, no wake, no candidacy
        val steps = listOf(0, 1326, 1326, 1330, 1330, 1330)
        for (st in steps) {
            val r = cycle(s, t, min, bpm = 55.0, stepsToday = st)
            assertThat(r.newState.state).isEqualTo(SleepState.SLEEPING)
            s = r.newState; t += FIVE_MIN; min += 5
        }
        assertThat(s.wakeCandidateSinceMs).isNull()
    }

    @Test fun `HR sustain plus cumulative lump wakes - the 2026-07-03 case`() {
        // 05:25 baseline asleep; 05:30 the wear lump (0→1326) lands as HR rises; the 15-min phone
        // bucket is 0 THROUGHOUT (phone on the nightstand) — the old logic could never fire here.
        var s = sleeping(T0)
        var t = T0
        var min = 325
        var wakeReason: String? = null
        val seq = listOf(55.0 to 0, 85.0 to 1326, 86.0 to 1326, 84.0 to 1330, 85.0 to 1340)
        for ((bpm, st) in seq) {
            val r = cycle(s, t, min, bpm = bpm, stepsToday = st)
            s = r.newState; wakeReason = r.wakeReason ?: wakeReason
            if (s.state == SleepState.AWAKE) break
            t += FIVE_MIN; min += 5
        }
        assertThat(s.state).isEqualTo(SleepState.AWAKE)
        assertThat(wakeReason).isEqualTo("hr_steps")        // genuine wake → trains the wake learner
        assertThat(min).isLessThan(360)                     // awake well before 06:00, not 07:00 boundary
    }

    @Test fun `single HR spike with no steps does not wake`() {
        var s = sleeping(T0)
        var t = T0
        var min = 180                                       // 03:00
        for (bpm in listOf(55.0, 85.0, 55.0, 55.0)) {       // one-cycle spike
            val r = cycle(s, t, min, bpm = bpm, stepsToday = 0)
            assertThat(r.newState.state).isEqualTo(SleepState.SLEEPING)
            s = r.newState; t += FIVE_MIN; min += 5
        }
    }

    @Test fun `REM - sustained HR rise with zero steps does not wake`() {
        var s = sleeping(T0)
        var t = T0
        var min = 200
        repeat(8) {                                         // 40 min of elevated HR, no movement
            val r = cycle(s, t, min, bpm = 85.0, stepsToday = 4000)   // stepsToday FLAT — no growth
            assertThat(r.newState.state).isEqualTo(SleepState.SLEEPING)
            s = r.newState; t += FIVE_MIN; min += 5
        }
    }

    @Test fun `legacy 15-min phone bucket still wakes when HR is sustained`() {
        // phone-only user: no cumulative feed (stepsToday = -1), steps in the 15-min bucket
        var s = sleeping(T0)
        var t = T0
        var min = 350
        var woke = false
        for (bpm in listOf(85.0, 86.0, 85.0, 85.0)) {
            val r = cycle(s, t, min, bpm = bpm, stepsToday = -1, steps15 = 150)
            s = r.newState
            if (s.state == SleepState.AWAKE) { woke = true; assertThat(r.wakeReason).isEqualTo("hr_steps"); break }
            t += FIVE_MIN; min += 5
        }
        assertThat(woke).isTrue()
    }

    @Test fun `boundary exit unchanged`() {
        val r = cycle(sleeping(T0), T0, minuteOfDay = 421, bpm = 55.0, stepsToday = 0)   // 07:01
        assertThat(r.newState.state).isEqualTo(SleepState.AWAKE)
        assertThat(r.wakeReason).isEqualTo("boundary")
    }

    @Test fun `midnight stepsToday reset is not step evidence`() {
        // 6000 → 0 (reset) → 30: only the +30 counts, never the negative jump
        val samples = listOf(
            StepSample(T0, 6000), StepSample(T0 + FIVE_MIN, 0), StepSample(T0 + 2 * FIVE_MIN, 30)
        )
        assertThat(T.stepGrowth(samples, T0 + 2 * FIVE_MIN, 60)).isEqualTo(30)
    }

    @Test fun `lump older than the lookback ages out`() {
        val old = StepSample(T0 - 70 * 60_000L, 0)
        val lump = StepSample(T0 - 65 * 60_000L, 1326)      // landed 65 min ago
        val now = StepSample(T0, 1326)
        assertThat(T.stepGrowth(listOf(old, lump, now), T0, 60)).isEqualTo(0)
    }

    @Test fun `new state fields round-trip through serialize`() {
        val s = State(
            state = SleepState.SLEEPING, enteredAtMs = 42L,
            stepSamples = mutableListOf(StepSample(T0, 100), StepSample(T0 + FIVE_MIN, 1426)),
            hrHighStreak = 2
        )
        val back = State.deserialize(s.serialize())
        assertThat(back.stepSamples).containsExactly(StepSample(T0, 100), StepSample(T0 + FIVE_MIN, 1426)).inOrder()
        assertThat(back.hrHighStreak).isEqualTo(2)
        // legacy blob (no new fields) still deserializes
        val legacy = State.deserialize("""{"state":"SLEEPING","enteredAtMs":7}""")
        assertThat(legacy.stepSamples).isEmpty()
        assertThat(legacy.hrHighStreak).isEqualTo(0)
    }
}
