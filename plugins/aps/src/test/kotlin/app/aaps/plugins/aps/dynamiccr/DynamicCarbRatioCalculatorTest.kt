package app.aaps.plugins.aps.dynamiccr

import app.aaps.plugins.aps.dynamiccr.DynamicCarbRatioCalculator.Formula
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-math tests for [DynamicCarbRatioCalculator] (no DI / mocks needed — the class has an empty constructor).
 * Covers: both formulas' direction & anchoring, the autosens + 0.5x..2x clamps, the AutoCR mode, and the
 * safety fallbacks (degenerate autosens range, non-positive ratio, no-valid-ISF).
 */
class DynamicCarbRatioCalculatorTest {

    private val sut = DynamicCarbRatioCalculator()

    private val baseCR = 10.0
    private val target = 100.0
    private val sens = 50.0
    private val autosensMin = 0.7
    private val autosensMax = 1.2

    private fun dyn(glucose: Double, formula: Formula, min: Double = autosensMin, max: Double = autosensMax) =
        sut.computeDynamicCarbRatio(
            baseCR = baseCR,
            currentGlucose = glucose,
            targetGlucose = target,
            tddRaw = 40.0,
            tddLast24H = 40.0,
            tdd7D = 40.0,
            weightPercentage = 0.65,
            formula = formula,
            adjustmentFactor = 1.0,
            insulinPeakTime = 75,
            useCustomPeakTime = false,
            autosensMin = min,
            autosensMax = max,
            sensitivity = sens
        )

    // ---- LOGARITHMIC -------------------------------------------------------------------------

    @Test fun logarithmic_isMoreAggressiveAtHighBg() {
        val low = dyn(70.0, Formula.LOGARITHMIC).carbRatio
        val mid = dyn(100.0, Formula.LOGARITHMIC).carbRatio
        val high = dyn(180.0, Formula.LOGARITHMIC).carbRatio
        // higher BG -> smaller CR (more insulin per carb)
        assertThat(low).isGreaterThan(mid)
        assertThat(mid).isGreaterThan(high)
    }

    @Test fun logarithmic_staysWithinHalfToDoubleClamp() {
        for (bg in listOf(40.0, 70.0, 100.0, 150.0, 250.0, 400.0)) {
            val cr = dyn(bg, Formula.LOGARITHMIC).carbRatio
            assertThat(cr).isAtLeast(baseCR * 0.5)
            assertThat(cr).isAtMost(baseCR * 2.0)
        }
    }

    // ---- SIGMOID -----------------------------------------------------------------------------

    @Test fun sigmoid_isNeutralAtTarget() {
        // At BG == target the sigmoid is anchored to ratio 1.0 -> CR == baseCR (within the autosens range).
        assertThat(dyn(target, Formula.SIGMOID).carbRatio).isWithin(1e-6).of(baseCR)
    }

    @Test fun sigmoid_isMoreAggressiveAtHighBg() {
        val low = dyn(70.0, Formula.SIGMOID).carbRatio
        val mid = dyn(100.0, Formula.SIGMOID).carbRatio
        val high = dyn(180.0, Formula.SIGMOID).carbRatio
        assertThat(low).isGreaterThan(mid)
        assertThat(mid).isGreaterThan(high)
    }

    @Test fun sigmoid_staysWithinHalfToDoubleClamp() {
        for (bg in listOf(40.0, 70.0, 100.0, 150.0, 250.0, 400.0)) {
            val cr = dyn(bg, Formula.SIGMOID).carbRatio
            assertThat(cr).isAtLeast(baseCR * 0.5)
            assertThat(cr).isAtMost(baseCR * 2.0)
        }
    }

    // ---- Safety fallbacks --------------------------------------------------------------------

    @Test fun sigmoid_degenerateWhenAutosensMinAtLeastOne_fallsBackToProfileCr() {
        val r = dyn(180.0, Formula.SIGMOID, min = 1.0, max = 1.2)
        assertThat(r.carbRatio).isWithin(1e-6).of(baseCR)
        assertThat(r.reason).contains("degenerate")
    }

    @Test fun sigmoid_degenerateWhenRangeEmpty_fallsBackToProfileCr() {
        val r = dyn(180.0, Formula.SIGMOID, min = 0.8, max = 0.8)
        assertThat(r.carbRatio).isWithin(1e-6).of(baseCR)
        assertThat(r.reason).contains("degenerate")
    }

    @Test fun calculateDynamicCR_setsNoteOnDegenerateSigmoid() {
        val result = sut.calculateDynamicCR(
            baseCR = baseCR, currentGlucose = 180.0, targetGlucose = target, adjustmentFactor = 1.0,
            formula = Formula.SIGMOID, tdd = 40.0, insulinPeakTime = 75, useCustomPeakTime = false,
            tddFactor = 1.0, autosensMin = 1.0, autosensMax = 1.2, sensitivity = sens
        )
        assertThat(result.note).isNotNull()
        assertThat(result.carbRatio).isWithin(1e-6).of(baseCR)
    }

    // ---- Custom insulin peak time (logarithmic only) -----------------------------------------

    @Test fun logarithmic_customPeakTime_capRespectedAndMonotonic() {
        fun cr(peak: Int, bg: Double) = sut.computeDynamicCarbRatio(
            baseCR = baseCR, currentGlucose = bg, targetGlucose = target, tddRaw = 40.0,
            tddLast24H = 40.0, tdd7D = 40.0, weightPercentage = 0.65, formula = Formula.LOGARITHMIC,
            adjustmentFactor = 1.0, insulinPeakTime = peak, useCustomPeakTime = true,
            autosensMin = autosensMin, autosensMax = autosensMax, sensitivity = sens
        ).carbRatio
        // still more aggressive at high BG with a custom peak time
        assertThat(cr(75, 70.0)).isGreaterThan(cr(75, 180.0))
        // a stale stored peak above the 110 cap must behave exactly like 110 (no degenerate insulinFactor)
        assertThat(cr(150, 120.0)).isWithin(1e-6).of(cr(110, 120.0))
        // and always within the 0.5x..2x safety clamp
        assertThat(cr(110, 250.0)).isAtLeast(baseCR * 0.5)
        assertThat(cr(110, 250.0)).isAtMost(baseCR * 2.0)
    }

    // ---- TDD fallbacks -----------------------------------------------------------------------

    @Test fun usesTddRaw_whenNoBlendAvailable() {
        // neither 24h nor 7d present -> tddRaw is used, and a finite CR within clamp is produced
        val cr = sut.computeDynamicCarbRatio(
            baseCR = baseCR, currentGlucose = 150.0, targetGlucose = target, tddRaw = 40.0,
            tddLast24H = null, tdd7D = null, weightPercentage = 0.65, formula = Formula.LOGARITHMIC,
            adjustmentFactor = 1.0, insulinPeakTime = 75, useCustomPeakTime = false,
            autosensMin = autosensMin, autosensMax = autosensMax, sensitivity = sens
        ).carbRatio
        assertThat(cr).isAtLeast(baseCR * 0.5)
        assertThat(cr).isAtMost(baseCR * 2.0)
    }

    // ---- AutoCR ------------------------------------------------------------------------------

    @Test fun autoCr_moreAggressiveWhenDynamicIsfWeaker() {
        // adjustedIsf < profileIsf (dynamic ISF weaker / less sensitive) -> autoISFRatio > 1 -> CR < baseCR
        val cr = sut.computeAutoCarbRatio(baseCR, profileIsf = 50.0, adjustedIsf = 40.0).carbRatio
        assertThat(cr).isLessThan(baseCR)
        assertThat(cr).isAtLeast(baseCR * 0.5)
    }

    @Test fun autoCr_lessAggressiveWhenDynamicIsfStronger() {
        // adjustedIsf > profileIsf (dynamic ISF stronger / more sensitive) -> autoISFRatio < 1 -> CR > baseCR
        val cr = sut.computeAutoCarbRatio(baseCR, profileIsf = 50.0, adjustedIsf = 70.0).carbRatio
        assertThat(cr).isGreaterThan(baseCR)
        assertThat(cr).isAtMost(baseCR * 2.0)
    }

    @Test fun autoCr_noValidIsf_fallsBackToProfileCr() {
        val r = sut.computeAutoCarbRatio(baseCR, profileIsf = 50.0, adjustedIsf = 0.0)
        assertThat(r.carbRatio).isWithin(1e-6).of(baseCR)
        assertThat(r.reason).contains("no valid dynamic ISF")
    }

    @Test fun calculateAutoCR_clampsToHalfAndDouble() {
        assertThat(sut.calculateAutoCR(baseCR, autoISFRatio = 5.0)).isWithin(1e-6).of(baseCR * 0.5)   // 10/5=2 -> clamp 5
        assertThat(sut.calculateAutoCR(baseCR, autoISFRatio = 0.1)).isWithin(1e-6).of(baseCR * 2.0)   // 10/0.1=100 -> clamp 20
        assertThat(sut.calculateAutoCR(baseCR, autoISFRatio = 0.0)).isWithin(1e-6).of(baseCR)         // non-positive -> profile
    }
}
