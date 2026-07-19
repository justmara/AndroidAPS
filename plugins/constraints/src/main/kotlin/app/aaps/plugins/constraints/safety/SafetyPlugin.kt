package app.aaps.plugins.constraints.safety

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.constraints.Safety
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.pump.defs.determineCorrectExtendedBolusSize
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.constraints.R
import org.joda.time.LocalTime
import org.joda.time.format.ISODateTimeFormat
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SafetyPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val constraintChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val hardLimits: HardLimits,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val decimalFormatter: DecimalFormatter,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val profileUtil: ProfileUtil
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .neverVisible(true)
        .alwaysEnabled(true)
        .showInList { false }
        .pluginName(R.string.safety)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN),
    aapsLogger, rh
), PluginConstraints, Safety {

    /**
     * Constraints interface
     */
    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!activePlugin.activePump.pumpDescription.isTempBasalCapable) value.set(false, rh.gs(R.string.pumpisnottempbasalcapable), this)
        return value
    }

    override fun isClosedLoopAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!config.isEngineeringModeOrRelease()) {
            if (value.value()) {
                uiInteraction.addNotification(Notification.TOAST_ALARM, rh.gs(R.string.closed_loop_disabled_on_dev_branch), Notification.NORMAL)
            }
            value.set(false, rh.gs(R.string.closed_loop_disabled_on_dev_branch), this)
        }
        val pump = activePlugin.activePump
        if (!pump.isFakingTempsByExtendedBoluses && persistenceLayer.getExtendedBolusActiveAt(dateUtil.now()) != null) {
            value.set(false, rh.gs(R.string.closed_loop_disabled_with_eb), this)
        }
        return value
    }

    private fun checkNightMode(): String? {
        if (!preferences.get(BooleanKey.NightMode)) return "disabled in settings"

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData ?: return "no glucose data"
        val profile = profileFunction.getProfile() ?: return "no profile"
        val bg = glucoseStatus.glucose

        val currentTimeMillis = System.currentTimeMillis()
        val midnight = MidnightTime.calc(currentTimeMillis)
        val startStr = preferences.get(StringKey.NightModeBegin)
        val endStr = preferences.get(StringKey.NightModeEnd)
        val start = midnight + LocalTime.parse(startStr, ISODateTimeFormat.timeElementParser()).millisOfDay
        val end = midnight + LocalTime.parse(endStr, ISODateTimeFormat.timeElementParser()).millisOfDay
        val offset = profileUtil.convertToMgdl(preferences.get(DoubleKey.NightModeBgOffset), profile.units).toInt()

        aapsLogger.debug(LTag.CONSTRAINTS, "Night mode: start=$start, end=$end, offset=$offset mg/dl)")

        val active =
            if (end > start) currentTimeMillis in start..<end
            else (currentTimeMillis in (start - 86400000)..<end || currentTimeMillis in start..<(end + 86400000))
        if (!active) return "inactive period"

        val profileTarget = profile.getTargetMgdl()
        val cobInfo = iobCobCalculator.getCobInfo("SafetyPlugin_NightMode")
        val cob = cobInfo.displayCob
        if (preferences.get(BooleanKey.NightModeWithCOB) && cob != null) {
            if (cob > 0) return "COB > 0"
        }

        val tt = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        if (preferences.get(BooleanKey.NightModeLowTT) && tt != null) {
            // If low TT is detected
            if (tt.highTarget.roundToInt() < profileTarget) return "low TT"
        }

        val blockSMB = bg < (profileTarget + offset)
        return if (blockSMB)
            null
        else
            "not in range: $bg > $profileTarget + $offset (${profileTarget + offset})"
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val closedLoop = constraintChecker.isClosedLoopAllowed()
        if (!closedLoop.value()) value.set(false, rh.gs(R.string.smbnotallowedinopenloopmode), this)
        val nightModeResult = checkNightMode()

        aapsLogger.debug(LTag.CONSTRAINTS, "Night mode result: ${nightModeResult ?: "active"}")
        if (nightModeResult == null)
            value.set(false, rh.gs(R.string.night_mode_smbs_disabled), this)

        val bg = glucoseStatusProvider.glucoseStatusData?.glucose
        val th = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.SmbBgThreshold))
        if (bg != null && bg <= th && preferences.get(BooleanKey.EnableSmbBgThreshold)) {
            aapsLogger.debug(LTag.CONSTRAINTS, "SMBs are disabled cause of an active BG threshold: $bg < $th")
            value.set(false, rh.gs(R.string.bg_threshold_smbs_disabled, bg.toInt(), th.toInt()), this)
        }

        return value
    }

    override fun isAdvancedFilteringEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.AlwaysPromoteAdvancedFiltering)) return value

        val bgSource = activePlugin.activeBgSource
        if (!bgSource.advancedFilteringSupported()) value.set(false, rh.gs(R.string.smbalwaysdisabled), this)
        return value
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        absoluteRate.setIfGreater(0.0, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, 0.0, rh.gs(app.aaps.core.ui.R.string.itmustbepositivevalue)), this)
        absoluteRate.setIfSmaller(hardLimits.maxBasal(), rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, hardLimits.maxBasal(), rh.gs(R.string.hardlimit)), this)
        val pump = activePlugin.activePump
        // check for pump max
        if (pump.pumpDescription.tempBasalStyle == PumpDescription.ABSOLUTE) {
            val pumpLimit = pump.pumpDescription.pumpType.tbrSettings()?.maxDose ?: 0.0
            absoluteRate.setIfSmaller(pumpLimit, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, pumpLimit, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        }

        // do rounding
        if (pump.pumpDescription.tempBasalStyle == PumpDescription.ABSOLUTE) {
            absoluteRate.set(Round.roundTo(absoluteRate.value(), pump.pumpDescription.tempAbsoluteStep))
        }
        return absoluteRate
    }

    override fun applyBasalPercentConstraints(percentRate: Constraint<Int>, profile: Profile): Constraint<Int> {
        val currentBasal = profile.getBasal()
        val absoluteRate = currentBasal * (percentRate.originalValue().toDouble() / 100)
        percentRate.addReason(
            "Percent rate " + percentRate.originalValue() + "% recalculated to " + decimalFormatter.to2Decimal(absoluteRate) + " U/h with current basal " + decimalFormatter.to2Decimal(
                currentBasal
            ) + " U/h", this
        )
        val absoluteConstraint = ConstraintObject(absoluteRate, aapsLogger)
        applyBasalConstraints(absoluteConstraint, profile)
        percentRate.copyReasons(absoluteConstraint)
        val pump = activePlugin.activePump
        var percentRateAfterConst = java.lang.Double.valueOf(absoluteConstraint.value() / currentBasal * 100).toInt()
        percentRateAfterConst =
            if (percentRateAfterConst < 100) Round.ceilTo(percentRateAfterConst.toDouble(), pump.pumpDescription.tempPercentStep.toDouble())
                .toInt() else Round.floorTo(percentRateAfterConst.toDouble(), pump.pumpDescription.tempPercentStep.toDouble()).toInt()
        percentRate.set(percentRateAfterConst, rh.gs(app.aaps.core.ui.R.string.limitingpercentrate, percentRateAfterConst, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        if (pump.pumpDescription.tempBasalStyle == PumpDescription.PERCENT) {
            val pumpLimit = pump.pumpDescription.pumpType.tbrSettings()?.maxDose ?: 0.0
            percentRate.setIfSmaller(pumpLimit.toInt(), rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, pumpLimit, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        }
        return percentRate
    }

    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        insulin.setIfGreater(0.0, rh.gs(app.aaps.core.ui.R.string.limitingbolus, 0.0, rh.gs(app.aaps.core.ui.R.string.itmustbepositivevalue)), this)
        val maxBolus = preferences.get(DoubleKey.SafetyMaxBolus)
        insulin.setIfSmaller(maxBolus, rh.gs(app.aaps.core.ui.R.string.limitingbolus, maxBolus, rh.gs(R.string.maxvalueinpreferences)), this)
        insulin.setIfSmaller(hardLimits.maxBolus(), rh.gs(app.aaps.core.ui.R.string.limitingbolus, hardLimits.maxBolus(), rh.gs(R.string.hardlimit)), this)
        val pump = activePlugin.activePump
        val rounded = pump.pumpDescription.pumpType.determineCorrectBolusSize(insulin.value())
        insulin.setIfDifferent(rounded, rh.gs(app.aaps.core.ui.R.string.pumplimit), this)
        return insulin
    }

    override fun applyExtendedBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        insulin.setIfGreater(0.0, rh.gs(R.string.limitingextendedbolus, 0.0, rh.gs(app.aaps.core.ui.R.string.itmustbepositivevalue)), this)
        val maxBolus = preferences.get(DoubleKey.SafetyMaxBolus)
        insulin.setIfSmaller(maxBolus, rh.gs(R.string.limitingextendedbolus, maxBolus, rh.gs(R.string.maxvalueinpreferences)), this)
        insulin.setIfSmaller(hardLimits.maxBolus(), rh.gs(R.string.limitingextendedbolus, hardLimits.maxBolus(), rh.gs(R.string.hardlimit)), this)
        val pump = activePlugin.activePump
        val rounded = pump.pumpDescription.pumpType.determineCorrectExtendedBolusSize(insulin.value())
        insulin.setIfDifferent(rounded, rh.gs(app.aaps.core.ui.R.string.pumplimit), this)
        return insulin
    }

    override fun applyCarbsConstraints(carbs: Constraint<Int>): Constraint<Int> {
        val maxCarbs = preferences.get(IntKey.SafetyMaxCarbs)
        carbs.setIfSmaller(maxCarbs, rh.gs(R.string.limitingcarbs, maxCarbs, rh.gs(R.string.maxvalueinpreferences)), this)
        return carbs
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(StringKey.SafetyAge, preferences)
            .put(DoubleKey.SafetyMaxBolus, preferences)
            .put(IntKey.SafetyMaxCarbs, preferences)
            .put(DoubleKey.NightModeBgOffset, preferences)
            .put(StringKey.NightModeBegin, preferences)
            .put(StringKey.NightModeEnd, preferences)
            .put(BooleanKey.NightMode, preferences)
            .put(BooleanKey.NightModeLowTT, preferences)
            .put(BooleanKey.NightModeWithCOB, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(StringKey.SafetyAge, preferences)
            .store(DoubleKey.SafetyMaxBolus, preferences)
            .store(IntKey.SafetyMaxCarbs, preferences)
            .store(DoubleKey.NightModeBgOffset, preferences)
            .store(StringKey.NightModeBegin, preferences)
            .store(StringKey.NightModeEnd, preferences)
            .store(BooleanKey.NightMode, preferences)
            .store(BooleanKey.NightModeLowTT, preferences)
            .store(BooleanKey.NightModeWithCOB, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "safety_night_mode_settings") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "safety_settings"
            title = rh.gs(R.string.treatmentssafety_title)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = StringKey.SafetyAge,
                    summary = app.aaps.core.ui.R.string.patient_age_summary,
                    title = app.aaps.core.ui.R.string.patient_type,
                    entries = hardLimits.ageEntries(),
                    entryValues = hardLimits.ageEntryValues()
                )
            )
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.SafetyMaxBolus, title = app.aaps.core.ui.R.string.max_bolus_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.SafetyMaxCarbs, title = app.aaps.core.ui.R.string.max_carbs_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.AlwaysPromoteAdvancedFiltering, title = R.string.always_promote_advanced_filtering_title, summary = R.string.always_promote_advanced_filtering_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.AllowRecalculatedBGs, title = R.string.allow_recalculated_bgs_title, summary = R.string.allow_recalculated_bgs_summary))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "safety_night_mode_settings"
                title = rh.gs(R.string.night_mode)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NightMode, title = R.string.night_mode))
                addPreference(AdaptiveStringPreference(ctx = context, validatorParams = DefaultEditTextValidator.Parameters(testType = EditTextValidator.TEST_TIME), stringKey = StringKey.NightModeBegin, title = R.string.night_mode_begin))
                addPreference(AdaptiveStringPreference(ctx = context, validatorParams = DefaultEditTextValidator.Parameters(testType = EditTextValidator.TEST_TIME), stringKey = StringKey.NightModeEnd, title = R.string.night_mode_end))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.NightModeBgOffset, dialogMessage = R.string.night_mode_bg_offset_summary, title = R.string.night_mode_bg_offset))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NightModeWithCOB, title = R.string.night_mode_cob))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NightModeLowTT, title = R.string.night_mode_lowtt))
            })
        }
    }
}
