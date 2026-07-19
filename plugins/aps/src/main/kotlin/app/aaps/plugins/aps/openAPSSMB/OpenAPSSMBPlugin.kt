package app.aaps.plugins.aps.openAPSSMB

import android.content.Context
import android.content.Intent
import androidx.collection.LongSparseArray
import androidx.collection.forEach
import androidx.core.net.toUri
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
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
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.utils.extensions.put
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.dynamiccr.DynamicCarbRatioCalculator
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

@Singleton
open class OpenAPSSMBPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalSMB: DetermineBasalSMB,
    private val profiler: Profiler,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: Provider<APSResult>,
    private val dynamicCarbRatioCalculator: DynamicCarbRatioCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openapssmb)
        .shortName(app.aaps.core.ui.R.string.smb_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.description_smb)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    override fun onStart() {
        super.onStart()
        var count = 0
        val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        // Mutate dynIsfCache under the same monitor calculateVariableIsf (@Synchronized) uses; the
        // LongSparseArray is not thread-safe.
        synchronized(this) {
            apsResults.forEach {
                val glucose = it.glucoseStatus?.glucose ?: return@forEach
                val variableSens = it.variableSens ?: return@forEach
                val timestamp = it.date
                val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
                if (variableSens > 0) dynIsfCache.put(key, variableSens)
                count++
            }
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
    }

    // last values - written on the loop worker thread, read by UI / Wear (DataHandlerMobile) on other
    // threads; @Volatile to publish the reference (and avoid a torn 64-bit read of lastAPSRun).
    @Volatile override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
    @Volatile override var lastAPSResult: APSResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val sensitivity = calculateVariableIsf(start, profile)
        if (sensitivity.second == null)
            uiInteraction.addNotificationValidTo(
                Notification.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, sensitivity.first), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
        else
            uiInteraction.dismissNotification(Notification.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() reason=${sensitivity.first} sensitivity=${sensitivity.second} caller=$caller", start)
        return sensitivity.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        // Iterate under the calculateVariableIsf monitor: this is called from overview/graph/profile on
        // UI/Rx threads while the loop thread mutates dynIsfCache, and LongSparseArray is not thread-safe.
        synchronized(this) {
            dynIsfCache.forEach { key, value ->
                if (key in start..timestamp) {
                    count++
                    sum += value
                }
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported() || preferences.get(BooleanKey.AlwaysPromoteAdvancedFiltering)
        val autoSensOrDynIsfSensEnabled = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
        } else {
            preferences.get(BooleanKey.ApsUseAutosens)
        }

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }

    private val dynIsfCache = LongSparseArray<Double>()

    @Synchronized
    private fun calculateVariableIsf(timestamp: Long, profile: Profile): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return Pair("OFF", null)

        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null && result.variableSens != 0.0) {
            //aapsLogger.debug("calculateVariableIsf $caller DB  ${dateUtil.dateAndTimeAndSecondsString(timestamp)} ${result.variableSens}")
            return Pair("DB", result.variableSens)
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to 30 min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        val cached = dynIsfCache[key]
        if (cached != null && timestamp < dateUtil.now()) {
            //aapsLogger.debug("calculateVariableIsf $caller HIT ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $cached")
            return Pair("HIT", cached)
        }

        val dynIsfResult = calculateRawDynIsf(profile)
        if (!dynIsfResult.tddPartsCalculated() && !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)) return Pair("TDD miss", null)
        // no cached result found, let's calculate the value
        //aapsLogger.debug("calculateVariableIsf $caller CAL ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
        dynIsfCache.put(key, dynIsfResult.variableSensitivity)
        if (dynIsfCache.size() > 1000) dynIsfCache.clear()
        return Pair("CALC", dynIsfResult.variableSensitivity)
    }

    internal class DynIsfResult {

        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        // raw weighted TDD before the DynISF adjustment factor — reused by Dynamic CR so it works whenever any TDD exists
        var tddRaw: Double? = null
        var variableSensitivity: Double? = null
        var insulinDivisor: Int = 0

        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false

        fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null
        fun tddQuickCalculated() = tddLast4H != null && tddLast8to4H != null

        fun log() =
            "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd variableSensitivity=$variableSensitivity insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
    }

    private fun capGlucose(glucoseStatus: GlucoseStatus): Double {
        // inspired by justmara
        val bgCap = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsDynIsfBgCap))
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

    private fun calculateRawDynIsf(profile: Profile): DynIsfResult {
        val dynIsfResult = DynIsfResult()
        val profileMultiplier = if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage))
            100.0 / (profile as ProfileSealed.EPS).value.originalPercentage
        else
            1.0

        // DynamicISF specific
        // without these values DynISF doesn't work properly
        val glucose = glucoseStatusProvider.glucoseStatusData?.let {capGlucose(it) }

        dynIsfResult.tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            dynIsfResult.tdd7D = it.data.totalAmount
            dynIsfResult.tdd7DDataCarbs = it.data.carbs
            dynIsfResult.tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }
        tddCalculator.calculateDaily(-24, 0)?.also {
            dynIsfResult.tddLast24H = it.totalAmount
            dynIsfResult.tddLast24HCarbs = it.carbs
        }
        dynIsfResult.tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        dynIsfResult.tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount
        dynIsfResult.insulinDivisor = getInsulinDivisor()

        val normalTarget = 100.0
        var baseSensitivity = profile.getProfileIsfMgdl()

        // Always calculate TDD, it's used not just in sensitivity calculation
        val useTDD = !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)
        if (dynIsfResult.tddPartsCalculated()) {
            val tddStatus = TddStatus(dynIsfResult.tdd1D!!, dynIsfResult.tdd7D!!, dynIsfResult.tddLast24H!!, dynIsfResult.tddLast4H!!, dynIsfResult.tddLast8to4H!!)
            val tddWeightedFromLast8H = ((1.4 * tddStatus.tddLast4H) + (0.6 * tddStatus.tddLast8to4H)) * 3
            dynIsfResult.tdd = (tddWeightedFromLast8H * 0.33) + (tddStatus.tdd7D * 0.34) + (tddStatus.tdd1D * 0.33)
        } else if (dynIsfResult.tddQuickCalculated()) {
            aapsLogger.warn(LTag.APS, "Using quick TDD")
            dynIsfResult.tdd = ((1.4 * dynIsfResult.tddLast4H!!) + (0.6 * dynIsfResult.tddLast8to4H!!)) * 3
        }

        dynIsfResult.tddRaw = dynIsfResult.tdd // raw weighted TDD (pre adjustment factor) for Dynamic CR
        val adjFactor = preferences.get(IntKey.ApsDynIsfAdjustmentFactor) / 100.0
        if (dynIsfResult.tdd != null) dynIsfResult.tdd = dynIsfResult.tdd!! * adjFactor

        if (useTDD) {
            if (dynIsfResult.tdd == null) {
                aapsLogger.error(LTag.APS, "Using TDD-based DynISF, but got no TDD")
                return dynIsfResult
            }

            aapsLogger.debug(LTag.APS, "Using TDD base sensitivity")
            baseSensitivity = Round.roundTo(1800.0 / (dynIsfResult.tdd!! * (ln((normalTarget / dynIsfResult.insulinDivisor) + 1))), 0.1)
            if (preferences.get(BooleanKey.ApsDynIsfProfilePercentage)) {
                baseSensitivity *= profileMultiplier
                aapsLogger.debug(LTag.APS, "Scaling TDD sensitivity by profile% - $profileMultiplier")
            }
        }

        if (glucose == null) {
            aapsLogger.error(LTag.APS, "Glucose is null")
            return dynIsfResult
        }

        // Scale base sensitivity by TT if needed
        var isTempTarget = false
        var targetBg = profile.getTargetMgdl()
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }
        if (isTempTarget) {
            if ((preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens) && targetBg > normalTarget)
                || (preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens) && targetBg < normalTarget)) {
                val c = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget) - normalTarget
                if (c * (c + targetBg - normalTarget) > 0.0) {
                    // coerceAtLeast/coerceAtMost return a new value; the previous `.apply { }` discarded
                    // them, leaving the ratio unclamped. Chain the coerces so AutosensMin/Max actually bound it.
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

        // Calculate variable sensitivity
        val velocity = preferences.get(IntKey.ApsDynIsfVelocity) / 100.0
        val sbg = ln((glucose / dynIsfResult.insulinDivisor) + 1)
        val scaler = ln((normalTarget / dynIsfResult.insulinDivisor) + 1) / sbg
        val ratio = 1 - (1 - scaler) * velocity
        dynIsfResult.variableSensitivity = baseSensitivity * ratio

        aapsLogger.debug(LTag.APS, "multiplier=$profileMultiplier gluc=$glucose tdd=${dynIsfResult.tdd} (${adjFactor}x) baseSens=${baseSensitivity} velocity=$velocity -> sensRatio=${ratio} sens=${dynIsfResult.variableSensitivity}")
        return dynIsfResult
    }

    private fun getSmbRatio(target: Double): Double {
        val fixedRatio = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatio)
        val mappedRatioMin = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMin)
        val mappedRatioMax = preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioMax)
        var mappedRatioBG = if (preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) < 10.0)
            preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange) * GlucoseUnit.MMOLL_TO_MGDL
        else
            preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange)

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if (glucoseStatus == null || mappedRatioBG == 0.0) return fixedRatio

        val bg = glucoseStatus.glucose
        if (bg < target) return mappedRatioMin
        if (bg > target + mappedRatioBG) return mappedRatioMax

        val ratio = mappedRatioMin + (mappedRatioMax - mappedRatioMin) * (bg - target) / mappedRatioBG
        aapsLogger.debug(LTag.APS, "SMBVariableRatio($mappedRatioMin - $mappedRatioMax, BG $mappedRatioBG) = $ratio")
        return ratio
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSSMBPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data
        val dynIsfResult = calculateRawDynIsf(profile)
        val dynIsfMode =
            preferences.get(BooleanKey.ApsUseDynamicSensitivity) &&
                hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(), R.string.dyn_isf_adjust_title, IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(), IntKey.ApsDynIsfAdjustmentFactor.max.toDouble()) &&
                hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfVelocity).toDouble(), R.string.dynisf_velocity, IntKey.ApsDynIsfVelocity.min.toDouble(), IntKey.ApsDynIsfVelocity.max.toDouble()) &&
                hardLimits.checkHardLimits(dynIsfResult.variableSensitivity ?: 0.0, R.string.dynisf_settings_title, HardLimits.MIN_ISF, HardLimits.MAX_ISF)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        var autosensResult = AutosensResult()

        if (dynIsfMode && !dynIsfResult.tddPartsCalculated() && !preferences.get(BooleanKey.ApsDynIsfUseProfileSens)) {
            uiInteraction.addNotificationValidTo(
                Notification.SMB_FALLBACK, dateUtil.now(),
                rh.gs(R.string.fallback_smb_no_tdd), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).also {
                    it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this)
                }
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply {
                    set(true, "tdd1D=${dynIsfResult.tdd1D} tdd7D=${dynIsfResult.tdd7D} tddLast4H=${dynIsfResult.tddLast4H} tddLast8to4H=${dynIsfResult.tddLast8to4H} tddLast24H=${dynIsfResult.tddLast24H}", this)
                }
            )
        }
        if (dynIsfMode && dynIsfResult.tddPartsCalculated()) {
            uiInteraction.dismissNotification(Notification.SMB_FALLBACK)
            // Compare insulin consumption of last 24h with last 7 days average
            // Guard tdd7D > 0: tddPartsCalculated() only ensures non-null, so a 0.0 average (sparse/restored
            // history) would make the ratio Infinity/NaN and poison the autosens ratio. Fall back to 1.0.
            val tddRatio = if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) && (dynIsfResult.tdd7D ?: 0.0) > 0.0)
                dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!! else 1.0
            // Because consumed carbs affects total amount of insulin compensate final ratio by consumed carbs ratio
            // take only 60% (expecting 40% basal). We cannot use bolus/total because of SMBs
            val carbsRatio = if (
                preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) &&
                dynIsfResult.tddLast24HCarbs != 0.0 &&
                dynIsfResult.tdd7DDataCarbs != 0.0 &&
                dynIsfResult.tdd7DAllDaysHaveCarbs
            ) ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0 else 1.0
            autosensResult = AutosensResult(
                ratio = tddRatio / carbsRatio,
                ratioFromTdd = tddRatio,
                ratioFromCarbs = carbsRatio
            )
        } else {
            if (constraintsChecker.isAutosensModeEnabled().value()) {
                val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
                if (autosensData == null) {
                    rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                    return
                }
                autosensResult = autosensData.autosensResult
            } else autosensResult.sensResult = "autosens disabled"
        }

        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget), isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        // Dynamic / Auto Carb Ratio (ported from iAPS/Trio). 0.0 = disabled (DetermineBasal uses profile.carb_ratio).
        // Dynamic CR uses the same robust weighted TDD that DynISF uses (tddRaw, available whenever any TDD exists).
        // Priority: formula-based Dynamic CR over AutoCR when both are enabled.
        var dynamicCarbRatio = 0.0
        var dynamicCarbRatioReason = ""
        run {
            val baseCR = profile.getIc()
            if (baseCR <= 0.0) return@run
            if (preferences.get(BooleanKey.ApsUseDynamicCarbRatio)) {
                val tddRaw = dynIsfResult.tddRaw
                if (tddRaw != null && tddRaw > 0.0) {
                    val rr = dynamicCarbRatioCalculator.computeDynamicCarbRatio(
                        baseCR = baseCR,
                        currentGlucose = glucoseStatus.glucose,
                        targetGlucose = targetBg,
                        tddRaw = tddRaw,
                        tddLast24H = dynIsfResult.tddLast24H,
                        tdd7D = dynIsfResult.tdd7D,
                        weightPercentage = preferences.get(DoubleKey.ApsDynamicCrWeightPercentage),
                        formula = if (preferences.get(IntKey.ApsDynamicCrFormula) == 1) DynamicCarbRatioCalculator.Formula.SIGMOID else DynamicCarbRatioCalculator.Formula.LOGARITHMIC,
                        adjustmentFactor = preferences.get(DoubleKey.ApsDynamicCrAdjustmentFactor),
                        insulinPeakTime = preferences.get(IntKey.ApsDynamicCrPeakTime),
                        useCustomPeakTime = preferences.get(BooleanKey.ApsDynamicCrUseCustomPeakTime),
                        autosensMin = preferences.get(DoubleKey.AutosensMin),
                        autosensMax = preferences.get(DoubleKey.AutosensMax),
                        sensitivity = profile.getProfileIsfMgdl()
                    )
                    dynamicCarbRatio = rr.carbRatio
                    dynamicCarbRatioReason = rr.reason
                } else {
                    dynamicCarbRatioReason = "Dynamic CR: enabled but no usable TDD — using profile CR"
                }
            } else if (preferences.get(BooleanKey.ApsAutoCr)) {
                if (dynIsfMode) {
                    // AutoCR — follow the DynISF sensitivity adjustment, keeping CSF (ISF/CR) at the profile baseline
                    val rr = dynamicCarbRatioCalculator.computeAutoCarbRatio(baseCR, profile.getProfileIsfMgdl(), dynIsfResult.variableSensitivity ?: 0.0)
                    dynamicCarbRatio = rr.carbRatio
                    dynamicCarbRatioReason = rr.reason
                } else {
                    // AutoCR mirrors the dynamic-ISF ratio, which only exists in Dynamic ISF mode — say so explicitly.
                    dynamicCarbRatioReason = "Auto CR: requires Dynamic ISF mode — using profile CR"
                }
            }
            // Autosens (not DynISF) + formula Dynamic CR: the formula uses the static profile ISF, so it would
            // decouple from the autosens ratio -> CSF (ISF/CR) drifts. Scale CR by autosensRatio so CSF tracks the
            // profile baseline (resistant ratio>1 -> smaller CR -> more insulin per carb). Bounded by the 0.5..2x clamp.
            // AutoCR can't reach here (it requires dynIsfMode) and already keeps CSF consistent on its own.
            if (!dynIsfMode && dynamicCarbRatio > 0.0 && autosensResult.ratio > 0.0) {
                dynamicCarbRatio = (dynamicCarbRatio / autosensResult.ratio).coerceIn(baseCR * 0.5, baseCR * 2.0)
                dynamicCarbRatioReason += " | autosens-coupled /${Round.roundTo(autosensResult.ratio, 0.01)}"
            }
        }

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfile(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSSMBPlugin"),
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens),
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens),
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = preferences.get(IntKey.ApsAutoIsfHalfBasalExerciseTarget),
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (dynIsfMode) dynIsfResult.variableSensitivity ?: 0.0 else 0.0,
            insulinDivisor = dynIsfResult.insulinDivisor,
            TDD = dynIsfResult.tdd ?: 0.0,
            use_TDD_for_predictions = !preferences.get(BooleanKey.ApsDynIsfUseProfileSens),
            dynamicCarbRatio = dynamicCarbRatio,
            dynamicCarbRatioReason = dynamicCarbRatioReason
        )
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfNonTDD:       ${preferences.get(BooleanKey.ApsDynIsfUseProfileSens)}")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

        determineBasalSMB.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode,
            smb_ratio = getSmbRatio(targetBg)
        ).also {
            val determineBasalResult = apsResultProvider.get().with(it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfile = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)
            .put(IntKey.ApsDynIsfVelocity, preferences)
            .put(BooleanKey.ApsDynIsfUseProfileSens, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
            .store(IntKey.ApsDynIsfVelocity, preferences)
            .store(BooleanKey.ApsDynIsfUseProfileSens, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey !in arrayOf("absorption_smb_advanced", "smb_delivery_settings", "dynisf_settings", "openapssmb_dynamic_cr_settings")) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapssmb_settings"
            title = rh.gs(R.string.openapssmb)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "dynisf_settings"
                title = rh.gs(R.string.dynisf_settings_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseDynamicSensitivity, summary = R.string.use_dynamic_sensitivity_summary, title = R.string.use_dynamic_sensitivity_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfAdjustmentFactor, dialogMessage = R.string.dyn_isf_adjust_summary, title = R.string.dyn_isf_adjust_title))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfVelocity, dialogMessage = R.string.dynisf_velocity_summary, title = R.string.dynisf_velocity))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsDynIsfBgCap, dialogMessage = R.string.dynisf_bg_cap_summary, title = R.string.dynisf_bg_cap))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfAdjustSensitivity, summary = R.string.dynisf_adjust_sensitivity_summary, title = R.string.dynisf_adjust_sensitivity))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfUseProfileSens, summary = R.string.dynisf_use_profile_sens_summary, title = R.string.dynisf_use_profile_sens))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfProfilePercentage, title = R.string.dynisf_use_profile_percentage))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "openapssmb_dynamic_cr_settings"
                title = rh.gs(R.string.dynamic_cr_submenu_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseDynamicCarbRatio, summary = R.string.pref_summary_aps_use_dynamic_carb_ratio, title = R.string.pref_title_aps_use_dynamic_carb_ratio))
                val formulaPref = AdaptiveListIntPreference(
                    ctx = context, intKey = IntKey.ApsDynamicCrFormula, title = R.string.pref_title_aps_dynamic_cr_formula, summary = R.string.pref_summary_aps_dynamic_cr_formula,
                    entries = arrayOf<CharSequence>(rh.gs(R.string.dynamic_cr_formula_logarithmic), rh.gs(R.string.dynamic_cr_formula_sigmoid)), entryValues = arrayOf<CharSequence>("0", "1")
                )
                addPreference(formulaPref)
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsDynamicCrAdjustmentFactor, dialogMessage = R.string.pref_summary_aps_dynamic_cr_adjustment_factor, title = R.string.pref_title_aps_dynamic_cr_adjustment_factor))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsDynamicCrWeightPercentage, dialogMessage = R.string.pref_summary_aps_dynamic_cr_weight_percentage, title = R.string.pref_title_aps_dynamic_cr_weight_percentage))
                val customPeakPref = AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynamicCrUseCustomPeakTime, summary = R.string.pref_summary_aps_dynamic_cr_use_custom_peak_time, title = R.string.pref_title_aps_dynamic_cr_use_custom_peak_time)
                addPreference(customPeakPref)
                val peakTimePref = AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynamicCrPeakTime, dialogMessage = R.string.pref_summary_aps_dynamic_cr_peak_time, title = R.string.pref_title_aps_dynamic_cr_peak_time)
                addPreference(peakTimePref)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoCr, summary = R.string.pref_summary_aps_auto_cr, title = R.string.pref_title_aps_auto_cr))
                // Insulin peak time only affects the logarithmic formula — hide it (and its child) for sigmoid.
                fun applyFormulaVisibility(logarithmic: Boolean) {
                    val crOn = preferences.get(BooleanKey.ApsUseDynamicCarbRatio)
                    customPeakPref.isVisible = crOn && logarithmic
                    peakTimePref.isVisible = crOn && logarithmic && preferences.get(BooleanKey.ApsDynamicCrUseCustomPeakTime)
                }
                applyFormulaVisibility(preferences.get(IntKey.ApsDynamicCrFormula) == 0)
                formulaPref.setOnPreferenceChangeListener { _, newValue ->
                    applyFormulaVisibility(newValue?.toString() == "0")
                    true
                }
            })
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfHighTtRaisesSens, summary = R.string.high_temptarget_raises_sensitivity_summary, title = R.string.high_temptarget_raises_sensitivity_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAutoIsfLowTtLowersSens, summary = R.string.low_temptarget_lowers_sensitivity_summary, title = R.string.low_temptarget_lowers_sensitivity_title))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsAutoIsfHalfBasalExerciseTarget, dialogMessage = R.string.half_basal_exercise_target_summary, title = R.string.half_basal_exercise_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.EnableSmbBgThreshold, title = R.string.enable_smb_bg_threshold))
            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.SmbBgThreshold, title = R.string.smb_bg_threshold_title, dialogMessage = R.string.smb_bg_threshold_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob, summary = R.string.enable_smb_with_cob_summary, title = R.string.enable_smb_with_cob))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs, summary = R.string.enable_smb_after_carbs_summary, title = R.string.enable_smb_after_carbs))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb, title = R.string.smb_max_minutes_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb, dialogMessage = R.string.uam_smb_max_minutes, title = R.string.uam_smb_max_minutes_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(
                    AdaptiveIntentPreference(
                        ctx = context,
                        intentKey = IntentKey.ApsLinkToDocs,
                        intent = Intent().apply { action = Intent.ACTION_VIEW; data = rh.gs(R.string.openapsama_link_to_preference_json_doc).toUri() },
                        summary = R.string.openapsama_link_to_preference_json_doc_txt
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                addPreference(
                    AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier, dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary, title = R.string.openapsama_current_basal_safety_multiplier)
                )
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "smb_delivery_settings"
                title = rh.gs(R.string.smb_delivery_settings_title)
                summary = rh.gs(R.string.smb_delivery_settings_summary)
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatio, dialogMessage = R.string.openapsama_smb_delivery_ratio_summary, title = R.string.openapsama_smb_delivery_ratio))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMin, dialogMessage = R.string.openapsama_smb_delivery_ratio_min_summary, title = R.string.openapsama_smb_delivery_ratio_min))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioMax, dialogMessage = R.string.openapsama_smb_delivery_ratio_max_summary, title = R.string.openapsama_smb_delivery_ratio_max))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsAutoIsfSmbDeliveryRatioBgRange, dialogMessage = R.string.openapsama_smb_delivery_ratio_bg_range_summary, title = R.string.openapsama_smb_delivery_ratio_bg_range))
            })
        }
    }
}