package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-07-03 — sustained-score early confirm ([CONFIRM_MIN_OBSERVING_AGE_SCORE_READY]).
 *
 * OBSERVING → CONFIRMED may fire ONE cycle before the standard age gate when the instantaneous
 * score has been ≥ CONFIRM_SCORE on BOTH the current and the immediately preceding cycle
 * (`scoreReadyStreak` — computed by the caller from last cycle's score, cross-cycle-input pattern
 * shared with `deltaDeclining`). Replay-validated 2026-07-03: 53% of confirm latency was
 * mechanical (score ready ≥2 cycles pre-confirm); shifting the same shot 1 cycle earlier measured
 * 0.0pp additional pre-low exposure. All other confirm conditions unchanged; pure-function tests
 * on step().
 */
class MealHypothesisEarlyConfirmTest {

    // OBSERVING one cycle BEFORE the standard age gate (age = CONFIRM_MIN_OBSERVING_AGE - 1),
    // score + eventualBG-offset peaks already satisfied.
    private fun observingOneCycleEarly(committed: Boolean = false) = MealHypothesisState(
        MealHypothesis.OBSERVING,
        ageCycles = CONFIRM_MIN_OBSERVING_AGE - 1,
        maxScoreInObserving = 0.60,
        maxEventualBgOffsetInObserving = 40.0,
        committedInSession = committed,
    )

    private val readyScore = 0.60          // ≥ CONFIRM_SCORE (0.55)
    private val eventualBg = 150.0
    private val targetBg = 100.0

    @Test fun `score ready two consecutive cycles - confirms one cycle earlier than the old gate`() {
        val r = step(observingOneCycleEarly(), readyScore, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, scoreReadyStreak = true)
        assertThat(r.state).isEqualTo(MealHypothesis.CONFIRMED)
    }

    @Test fun `score ready only on the current cycle (no streak) - old timing preserved`() {
        // Same cycle, same score, but the PREVIOUS cycle wasn't ready → the early path must not
        // open; it holds in OBSERVING and confirms on the next cycle via the standard age gate.
        val held = step(observingOneCycleEarly(), readyScore, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, scoreReadyStreak = false)
        assertThat(held.state).isEqualTo(MealHypothesis.OBSERVING)
        assertThat(held.ageCycles).isEqualTo(CONFIRM_MIN_OBSERVING_AGE)

        val confirmedNextCycle = step(held, readyScore, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, scoreReadyStreak = false)
        assertThat(confirmedNextCycle.state).isEqualTo(MealHypothesis.CONFIRMED)
    }

    @Test fun `streak with a current score below threshold does NOT open the early path`() {
        // Peak-tracked max (0.60) is ready but the CURRENT score dipped below CONFIRM_SCORE —
        // the early path requires a sustained-ready CURRENT score, not just the tracked max.
        val r = step(observingOneCycleEarly(), score = 0.50, eventualBg = eventualBg, targetBg = targetBg,
            delta = 6.0, deltaAccl = 2.0, deltaDeclining = false, scoreReadyStreak = true)
        assertThat(r.state).isEqualTo(MealHypothesis.OBSERVING)
    }

    @Test fun `early path still blocked by the dose-adequacy gate`() {
        val r = step(observingOneCycleEarly(), readyScore, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, confirmDoseAdequate = false, scoreReadyStreak = true)
        assertThat(r.state).isEqualTo(MealHypothesis.OBSERVING)
    }

    @Test fun `early path still blocked by the single-confirm-per-session guard`() {
        val r = step(observingOneCycleEarly(committed = true), readyScore, eventualBg, targetBg,
            delta = 6.0, deltaAccl = 2.0, deltaDeclining = false, scoreReadyStreak = true)
        assertThat(r.state).isNotEqualTo(MealHypothesis.CONFIRMED)
    }

    @Test fun `default scoreReadyStreak=false - existing callers keep legacy behaviour`() {
        val r = step(observingOneCycleEarly(), readyScore, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false)
        assertThat(r.state).isEqualTo(MealHypothesis.OBSERVING)
    }
}
