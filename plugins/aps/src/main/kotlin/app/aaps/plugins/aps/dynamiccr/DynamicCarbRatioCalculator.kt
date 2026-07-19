package app.aaps.plugins.aps.dynamiccr

import app.aaps.core.interfaces.utils.Round
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * Glucose-modulated carb ratio, ported from iAPS/Trio (`detbasal_iaps.js`).
 *
 * Both formulas produce a dynamic `ratio` (the same shape Trio uses for dynamic ISF); the carb ratio is then
 * scaled by it: `dynamicCR = baseCR / ratio`, clamped to half..double the profile carb ratio for safety.
 *
 * - LOGARITHMIC: `ratio = sensitivity * adjustmentFactor * tdd * ln(glucose / insulinFactor + 1) / 1800`
 * - SIGMOID:     `ratio = interval / (1 + e^-(bgDev * adjustmentFactor * tddFactor + offset)) + autosensMin`
 */
@Singleton
class DynamicCarbRatioCalculator @Inject constructor() {

    enum class Formula { LOGARITHMIC, SIGMOID }

    /**
     * @param carbRatio the resulting (clamped) carb ratio.
     * @param ratio the raw dynamic ratio from the formula.
     * @param effectiveRatio the ratio actually used to scale CR after the autosens clamp (Trio-style).
     * @param note non-null only when a safety fallback fired (degenerate config / non-finite ratio); carbRatio is then baseCR.
     */
    data class Result(val carbRatio: Double, val ratio: Double, val effectiveRatio: Double, val note: String? = null)

    /** A computed carb ratio together with the human-readable "Script debug" line describing how it was derived. */
    data class ReasonedResult(val carbRatio: Double, val reason: String)

    /**
     * Full formula-based Dynamic CR for the plugins: blends the TDD (recent 24h vs 7-day average by [weightPercentage]),
     * runs [calculateDynamicCR] and assembles the script-debug reason line. Shared by OpenAPS SMB and AutoISF.
     */
    fun computeDynamicCarbRatio(
        baseCR: Double,
        currentGlucose: Double,
        targetGlucose: Double,
        tddRaw: Double,
        tddLast24H: Double?,
        tdd7D: Double?,
        weightPercentage: Double,
        formula: Formula,
        adjustmentFactor: Double,
        insulinPeakTime: Int,
        useCustomPeakTime: Boolean,
        autosensMin: Double,
        autosensMax: Double,
        sensitivity: Double
    ): ReasonedResult {
        // TDD used by the formula, in order of preference:
        //  1) both 24h and 7d present -> blend them by the TDD-weight-percentage slider (recent vs long-term), like Trio
        //  2) only 24h present         -> use the accurate last-24h TDD (the slider can't blend without a long-term value)
        //  3) neither present          -> last resort: the 8h-extrapolated quick TDD (tddRaw), which can overshoot on fresh boluses
        val haveRatio = tddLast24H != null && tdd7D != null && tdd7D > 0.0
        val weightedTdd = when {
            haveRatio          -> weightPercentage * tddLast24H + (1.0 - weightPercentage) * tdd7D
            tddLast24H != null -> tddLast24H
            else               -> tddRaw
        }
        // sigmoid's tddFactor (recent vs long-term ratio) only makes sense when a long-term 7d average exists
        val tddFactor = if (haveRatio) weightedTdd / tdd7D else 1.0
        val result = calculateDynamicCR(
            baseCR = baseCR,
            currentGlucose = currentGlucose,
            targetGlucose = targetGlucose,
            adjustmentFactor = adjustmentFactor,
            formula = formula,
            tdd = weightedTdd,
            insulinPeakTime = insulinPeakTime,
            useCustomPeakTime = useCustomPeakTime,
            tddFactor = tddFactor,
            autosensMin = autosensMin,
            autosensMax = autosensMax,
            sensitivity = sensitivity
        )
        // Safety fallback fired (degenerate config / non-finite ratio): make it explicit in Script debug.
        if (result.note != null)
            return ReasonedResult(result.carbRatio, "Dynamic CR: ${result.note} -> using profile CR ${Round.roundTo(baseCR, 0.01)} g/U (unchanged)")
        val ratioStr =
            if (Round.roundTo(result.ratio, 0.001) != Round.roundTo(result.effectiveRatio, 0.001))
                "ratio=${Round.roundTo(result.ratio, 0.001)}->${Round.roundTo(result.effectiveRatio, 0.001)} (autosens [${Round.roundTo(autosensMin, 0.01)}..${Round.roundTo(autosensMax, 0.01)}])"
            else
                "ratio=${Round.roundTo(result.ratio, 0.001)}"
        val reason =
            "Dynamic CR: formula=${formula.name.lowercase()}, $ratioStr, " +
                "weightedTDD=${Round.roundTo(weightedTdd, 0.1)}U (24h=${tddLast24H?.let { Round.roundTo(it, 0.1) }}, 7d=${tdd7D?.let { Round.roundTo(it, 0.1) }}, raw=${Round.roundTo(tddRaw, 0.1)}, w=$weightPercentage), " +
                "tddFactor=${Round.roundTo(tddFactor, 0.001)}, CR ${Round.roundTo(baseCR, 0.01)} -> ${Round.roundTo(result.carbRatio, 0.01)} g/U"
        return ReasonedResult(result.carbRatio, reason)
    }

    /** AutoCR for the plugins: scales the carb ratio by the AutoISF/DynISF sensitivity ratio and builds the reason line. */
    fun computeAutoCarbRatio(baseCR: Double, profileIsf: Double, adjustedIsf: Double): ReasonedResult {
        // No valid dynamic ISF yet -> keep the profile CR, and say so explicitly (instead of a silent baseCR).
        if (adjustedIsf <= 0.0)
            return ReasonedResult(baseCR, "Auto CR: no valid dynamic ISF (ISF=${Round.roundTo(adjustedIsf, 0.1)}) -> using profile CR ${Round.roundTo(baseCR, 0.01)} g/U (unchanged)")
        val autoISFRatio = profileIsf / adjustedIsf
        // AutoCR deliberately does NOT apply the autosens-range clamp that formula-based Dynamic CR uses: it mirrors
        // the actually-applied dynamic ISF ratio so CSF (ISF/CR) stays at the profile baseline. The upstream
        // variableSensitivity is already autosens-bounded, so re-clamping here would break that property.
        val carbRatio = calculateAutoCR(baseCR, autoISFRatio)
        val reason =
            "Auto CR: autoISFRatio=${Round.roundTo(autoISFRatio, 0.001)} (profileISF=${Round.roundTo(profileIsf, 0.1)} / ISF=${Round.roundTo(adjustedIsf, 0.1)}), " +
                "CR ${Round.roundTo(baseCR, 0.01)} -> ${Round.roundTo(carbRatio, 0.01)} g/U"
        return ReasonedResult(carbRatio, reason)
    }

    private fun calculateLogarithmicRatio(glucose: Double, insulinFactor: Int, adjustmentFactor: Double, tdd: Double, sensitivity: Double): Double =
        sensitivity * adjustmentFactor * tdd * ln((glucose / insulinFactor) + 1.0) / 1800.0

    private fun calculateSigmoidRatio(glucose: Double, targetGlucose: Double, adjustmentFactor: Double, tddFactor: Double, autosensMin: Double, autosensMax: Double): Double {
        val interval = autosensMax - autosensMin
        val bgDev = (glucose - targetGlucose) * 0.0555
        // avoid division by zero when autosensMax == 1
        val maxMinusOne = if (autosensMax == 1.0) autosensMax + 0.01 - 1.0 else autosensMax - 1.0
        // makes the sigmoid factor == 1 when the BG deviation is 0
        val fixOffset = ln((1.0 / maxMinusOne) - (autosensMin / maxMinusOne))
        // Defense-in-depth: calculateDynamicCR already intercepts degenerate sigmoid configs before calling this,
        // but keep a local guard so calculateSigmoidRatio is safe if ever called directly (autosensMin >= 1.0 ->
        // ln of <= 0 = -Infinity -> neutral ratio 1.0 instead of -Infinity propagation).
        if (!fixOffset.isFinite()) return 1.0
        val exponent = bgDev * adjustmentFactor * tddFactor + fixOffset
        return interval / (exp(-exponent) + 1.0) + autosensMin
    }

    /** Reuse an externally computed dynamic-ISF ratio to scale the carb ratio (the "AutoCR" mode). */
    fun calculateAutoCR(baseCR: Double, autoISFRatio: Double): Double =
        if (autoISFRatio <= 0.0) baseCR else (baseCR / autoISFRatio).coerceIn(baseCR * 0.5, baseCR * 2.0)

    fun calculateDynamicCR(
        baseCR: Double,
        currentGlucose: Double,
        targetGlucose: Double,
        adjustmentFactor: Double,
        formula: Formula,
        tdd: Double,
        insulinPeakTime: Int,
        useCustomPeakTime: Boolean,
        tddFactor: Double,
        autosensMin: Double,
        autosensMax: Double,
        sensitivity: Double
    ): Result {
        // The sigmoid can only be anchored when the autosens range is non-empty and autosensMin < 1 (otherwise
        // fixOffset = ln of <= 0 is undefined). Outside that, fall back to the profile CR with an explicit note
        // instead of a silent neutral, so the Script-debug line shows WHY Dynamic CR did nothing.
        if (formula == Formula.SIGMOID && (autosensMax <= autosensMin || autosensMin >= 1.0))
            return Result(baseCR, 1.0, 1.0, "degenerate autosens range (min=$autosensMin, max=$autosensMax)")
        // Clamp peak to the IntKey range [35,110] (and floor insulinFactor at 1) so a stale stored value > 110,
        // saved before the cap was lowered, can't shrink insulinFactor into the degenerate near-zero zone.
        val insulinFactor = (if (useCustomPeakTime) 120 - insulinPeakTime.coerceIn(35, 110) else 55).coerceAtLeast(1)
        val ratio = when (formula) {
            Formula.LOGARITHMIC -> calculateLogarithmicRatio(currentGlucose, insulinFactor, adjustmentFactor, tdd, sensitivity)
            Formula.SIGMOID     -> calculateSigmoidRatio(currentGlucose, targetGlucose, adjustmentFactor, tddFactor, autosensMin, autosensMax)
        }
        // safety: a non-finite / non-positive ratio would invert or blow up the carb ratio — fall back to the profile value
        if (!ratio.isFinite() || ratio <= 0.0) return Result(baseCR, ratio, ratio, "non-finite ratio ($ratio)")
        // Trio: clamp the dynamic ratio to the autosens range BEFORE scaling CR, so the carb ratio stays within
        // ~[baseCR/autosensMax, baseCR/autosensMin] instead of swinging out to the wide 0.5x..2x result clamp.
        val effectiveRatio = ratio.coerceIn(minOf(autosensMin, autosensMax), maxOf(autosensMin, autosensMax))
        return Result((baseCR / effectiveRatio).coerceIn(baseCR * 0.5, baseCR * 2.0), ratio, effectiveRatio)
    }
}
