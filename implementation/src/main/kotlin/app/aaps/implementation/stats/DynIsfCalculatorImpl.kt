package app.aaps.implementation.stats

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.DynIsfCalculator
import app.aaps.core.interfaces.stats.DynIsfResult
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class DynIsfCalculatorImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val hardLimits: HardLimits,
    private val activePlugin: ActivePlugin,
    private val profileUtil: ProfileUtil
) : DynIsfCalculator {

    override fun calculate(profile: Profile): DynIsfResult {
        val profileMultiplier = if (preferences.get(BooleanKey.DynIsfProfilePercentage))
            100.0 / (profile as ProfileSealed.EPS).value.originalPercentage
        else
            1.0

        // without these values DynISF doesn't work properly
        val glucose = glucoseStatusProvider.glucoseStatusData?.let { capGlucose(it) }

        val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        var tdd7D: Double? = null
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            tdd7D = it.data.totalAmount
            tdd7DDataCarbs = it.data.carbs
            tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }
        var tddLast24H: Double? = null
        var tddLast24HCarbs = 0.0
        tddCalculator.calculateDaily(-24, 0)?.also {
            tddLast24H = it.totalAmount
            tddLast24HCarbs = it.carbs
        }
        val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount
        val insulinDivisor = getInsulinDivisor()

        val normalTarget = 100.0
        var baseSensitivity = profile.getProfileIsfMgdl()

        // Always calculate TDD, it's used not just in sensitivity calculation
        val useTDD = !preferences.get(BooleanKey.DynIsfUseProfileSens)
        var tdd: Double? = null
        val hasFullTDD = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null
        val hasQuickTDD = tddLast4H != null && tddLast8to4H != null

        if (hasFullTDD) {
            val tddWeightedFromLast8H = ((1.4 * tddLast4H!!) + (0.6 * tddLast8to4H!!)) * 3
            tdd = (tddWeightedFromLast8H * 0.33) + (tdd7D!! * 0.34) + (tdd1D!! * 0.33)
        } else if (hasQuickTDD) {
            aapsLogger.warn(LTag.APS, "Using quick TDD")
            tdd = ((1.4 * tddLast4H!!) + (0.6 * tddLast8to4H!!)) * 3
        }

        val tddRaw = tdd // raw weighted TDD (pre adjustment factor) for Dynamic CR
        val adjFactor = preferences.get(IntKey.DynIsfAdjustmentFactor) / 100.0
        if (tdd != null) tdd = tdd!! * adjFactor

        if (useTDD) {
            if (tdd == null) {
                aapsLogger.error(LTag.APS, "Using TDD-based DynISF, but got no TDD")
                return DynIsfResult(
                    tdd1D = tdd1D,
                    tdd7D = tdd7D,
                    tddLast24H = tddLast24H,
                    tddLast4H = tddLast4H,
                    tddLast8to4H = tddLast8to4H,
                    tdd = tdd,
                    tddRaw = tddRaw,
                    insulinDivisor = insulinDivisor,
                    tddLast24HCarbs = tddLast24HCarbs,
                    tdd7DDataCarbs = tdd7DDataCarbs,
                    tdd7DAllDaysHaveCarbs = tdd7DAllDaysHaveCarbs
                )
            }

            aapsLogger.debug(LTag.APS, "Using TDD base sensitivity")
            baseSensitivity = Round.roundTo(1800.0 / (tdd!! * (ln((normalTarget / insulinDivisor) + 1))), 0.1)
            if (preferences.get(BooleanKey.DynIsfProfilePercentage)) {
                baseSensitivity *= profileMultiplier
                aapsLogger.debug(LTag.APS, "Scaling TDD sensitivity by profile% - $profileMultiplier")
            }
        }

        if (glucose == null) {
            aapsLogger.error(LTag.APS, "Glucose is null")
            return DynIsfResult(
                tdd1D = tdd1D, tdd7D = tdd7D,
                tddLast24H = tddLast24H, tddLast4H = tddLast4H, tddLast8to4H = tddLast8to4H,
                tdd = tdd, tddRaw = tddRaw,
                insulinDivisor = insulinDivisor,
                tddLast24HCarbs = tddLast24HCarbs,
                tdd7DDataCarbs = tdd7DDataCarbs,
                tdd7DAllDaysHaveCarbs = tdd7DAllDaysHaveCarbs
            )
        }

        // Scale base sensitivity by TT if needed
        var isTempTarget = false
        var targetBg = profile.getTargetMgdl()
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            targetBg = hardLimits.verifyHardLimits(
                tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value,
                HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1]
            )
        }
        if (isTempTarget) {
            if ((preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens) && targetBg > normalTarget)
                || (preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && targetBg < normalTarget)) {
                val c = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget) - normalTarget
                if (c * (c + targetBg - normalTarget) > 0.0) {
                    val sensitivityRatio = Round.roundTo(
                        (c / (c + targetBg - normalTarget))
                            .coerceAtLeast(preferences.get(DoubleKey.AutosensMin))
                            .coerceAtMost(preferences.get(DoubleKey.AutosensMax)),
                        0.01
                    )
                    aapsLogger.debug(LTag.APS, "Scaling sensitivity by TT ratio: $sensitivityRatio")
                    baseSensitivity /= sensitivityRatio
                }
            }
        }

        // sensNormalTarget is the TDD-based sensitivity at normal target, after all adjustments
        // except velocity scaling. Used externally by isfAtBg() for per-BG-level predictions.
        val sensNormalTarget = baseSensitivity

        // Calculate variable sensitivity
        val velocity = preferences.get(IntKey.DynIsfVelocity) / 100.0
        val sbg = ln((glucose / insulinDivisor) + 1)
        val scaler = ln((normalTarget / insulinDivisor) + 1) / sbg
        val ratio = 1 - (1 - scaler) * velocity
        val variableSensitivity = baseSensitivity * ratio

        aapsLogger.debug(
            LTag.APS,
            "multiplier=$profileMultiplier gluc=$glucose tdd=$tdd (${adjFactor}x) baseSens=$baseSensitivity velocity=$velocity -> sensRatio=$ratio sens=$variableSensitivity"
        )

        return DynIsfResult(
            tdd1D = tdd1D, tdd7D = tdd7D,
            tddLast24H = tddLast24H, tddLast4H = tddLast4H, tddLast8to4H = tddLast8to4H,
            tdd = tdd, tddRaw = tddRaw,
            variableSensitivity = variableSensitivity,
            sensNormalTarget = sensNormalTarget,
            insulinDivisor = insulinDivisor,
            tddLast24HCarbs = tddLast24HCarbs,
            tdd7DDataCarbs = tdd7DDataCarbs,
            tdd7DAllDaysHaveCarbs = tdd7DAllDaysHaveCarbs
        )
    }

    override fun getIsfForBg(bg: Double, sensNormalTarget: Double, insulinDivisor: Int, useCap: Boolean): Double {
        val bgCapMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.DynIsfBgCap))
        val velocity = preferences.get(IntKey.DynIsfVelocity) / 100.0
        val normalTarget = 100.0
        val divisor = insulinDivisor

        var bgAdj = bg
        if (useCap && bgAdj > bgCapMgdl) {
            bgAdj = bgCapMgdl + (bgAdj - bgCapMgdl) / 3.0
        }

        val sbg = ln((bgAdj / divisor) + 1)
        val scaler = ln((normalTarget / divisor) + 1) / sbg
        return sensNormalTarget * (1 - (1 - scaler) * velocity)
    }

    private fun capGlucose(glucoseStatus: GlucoseStatus): Double {
        val bgCap = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.DynIsfBgCap))
        return if (glucoseStatus.glucose > bgCap)
            bgCap + ((glucoseStatus.glucose - bgCap) / 3)
        else
            glucoseStatus.glucose
    }

    private fun getInsulinDivisor(): Int {
        val insulin = activePlugin.activeInsulin
        return when {
            insulin.peak > 65 -> 55 // rapid peak: 75
            insulin.peak > 50 -> 65 // ultra rapid peak: 55
            else              -> 75 // lyumjev peak: 45
        }
    }
}
