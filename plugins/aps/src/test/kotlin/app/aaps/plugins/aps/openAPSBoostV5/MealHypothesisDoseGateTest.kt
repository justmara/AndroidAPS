package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-07-02 — OBSERVING→CONFIRMED dose-adequacy gate (committedCap anchor).
 *
 * The single per-session CONFIRMED commit-shot must be worth more than one routine COMMITTED hold
 * (committedCapU). The caller (DetermineBasalBoostV5.decide) computes
 *   confirmDoseAdequate = (budget × CONFIRMED mult) > min(committedCapU, 0.8 × confirmedCapU)
 * and threads it into step(). These are pure-function tests on step(): the gate applies to the NORMAL
 * path only; the fast-carb fast-path is intentionally exempt.
 */
class MealHypothesisDoseGateTest {

    // An OBSERVING run that already satisfies the score + eventualBG-offset peaks and the age gate, so
    // the ONLY remaining variable is confirmDoseAdequate.
    private fun observedReady() =
        MealHypothesisState(MealHypothesis.OBSERVING, ageCycles = 2, maxScoreInObserving = 0.60, maxEventualBgOffsetInObserving = 40.0, committedInSession = false)

    private val score = 0.60
    private val eventualBg = 150.0
    private val targetBg = 100.0

    @Test fun `confirms when the shot is adequate`() {
        val r = step(observedReady(), score, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, confirmDoseAdequate = true)
        assertThat(r.state).isEqualTo(MealHypothesis.CONFIRMED)
    }

    @Test fun `does NOT confirm when the shot is inadequate - holds in OBSERVING`() {
        val r = step(observedReady(), score, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false, confirmDoseAdequate = false)
        // All other confirm predicates pass; only the dose floor blocks it. Score is above the
        // fall-back threshold, so it holds in OBSERVING rather than dropping to IDLE.
        assertThat(r.state).isEqualTo(MealHypothesis.OBSERVING)
    }

    @Test fun `default arg preserves legacy behaviour (adequate)`() {
        val r = step(observedReady(), score, eventualBg, targetBg, delta = 6.0, deltaAccl = 2.0,
            deltaDeclining = false)
        assertThat(r.state).isEqualTo(MealHypothesis.CONFIRMED)
    }

    @Test fun `fast-carb path is NOT gated by dose adequacy`() {
        // Sharp, corroborated rise with the toggle on still confirms in one cycle even when
        // confirmDoseAdequate=false — the fast-path is intentionally exempt.
        val r = step(MealHypothesisState(MealHypothesis.OBSERVING, 0, 0.5, 10.0, false),
            score = 0.7, eventualBg = eventualBg, targetBg = targetBg, delta = 12.0, deltaAccl = 30.0,
            deltaDeclining = false, asleep = false, exerciseActive = false,
            fastConfirmEnabled = true, confirmDoseAdequate = false)
        assertThat(r.state).isEqualTo(MealHypothesis.CONFIRMED)
    }
}
