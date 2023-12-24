package app.aaps.plugins.aps.EN

import app.aaps.core.interfaces.aps.ENDefaults
import app.aaps.core.interfaces.aps.SMBDefaults
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.IsfCalculator
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.main.extensions.convertedToAbsolute
import app.aaps.core.main.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.main.extensions.plannedRemainingMinutes
import app.aaps.core.main.profile.ProfileSealed
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalAdapterSMBJS
import app.aaps.plugins.aps.utils.ScriptReader
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import kotlin.math.max

class DetermineBasalAdapterENJS internal constructor(scriptReader: ScriptReader, injector: HasAndroidInjector)
    : DetermineBasalAdapterSMBJS(scriptReader, injector) {

    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var repository: AppRepository
    // @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var isfCalculator: IsfCalculator
    @Inject lateinit var tirCalculator: TirCalculator

    override val jsFolder = "EN"
    override val useLoopVariants = true
    override val jsAdditionalScript = """
var getIsfByProfile = function (bg, profile) {
    var cap = profile.dynISFBgCap;
    if (bg > cap) bg = (cap + (bg - cap)/3);
    var sens_BG = Math.log((bg / profile.insulinDivisor) + 1);
    var scaler = Math.log((profile.normalTarget / profile.insulinDivisor) + 1) / sens_BG;
    return profile.sensNormalTarget * (1 - (1 - scaler) * profile.dynISFvelocity);
}"""

    @Suppress("SpellCheckingInspection")
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        flatBGsDetected: Boolean
    ) {
        val now = System.currentTimeMillis()
        val pump = activePlugin.activePump
        val pumpBolusStep = pump.pumpDescription.bolusStep
        this.profile.put("max_iob", maxIob)
        //mProfile.put("dia", profile.getDia());
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("safety_maxbolus", sp.getDouble(app.aaps.core.utils.R.string.key_treatmentssafety_maxbolus, 3.0))
        this.profile.put("min_bg", minBg)
        this.profile.put("max_bg", maxBg)
        this.profile.put("target_bg", targetBg)
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("carb_ratio_midnight", profile.getIc(MidnightTime.calc(now)))
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("sens_midnight", profile.getIsfMgdl(MidnightTime.calc(now)))
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))

        this.profile.put("high_temptarget_raises_sensitivity", sp.getBoolean(R.string.key_high_temptarget_raises_sensitivity, ENDefaults.high_temptarget_raises_sensitivity))
        // this.profile.put("high_temptarget_raises_sensitivity", false)
        this.profile.put("low_temptarget_lowers_sensitivity", sp.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, ENDefaults.low_temptarget_lowers_sensitivity))
        // this.profile.put("low_temptarget_lowers_sensitivity", false)
        this.profile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, ENDefaults.sensitivity_raises_target))
        this.profile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, ENDefaults.resistance_lowers_target))
        this.profile.put("adv_target_adjustments", ENDefaults.adv_target_adjustments)
        this.profile.put("exercise_mode", SMBDefaults.exercise_mode)
        this.profile.put("half_basal_exercise_target", SMBDefaults.half_basal_exercise_target)
        this.profile.put("maxCOB", ENDefaults.maxCOB)
        this.profile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour())
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, ENDefaults.min_5m_carbimpact));
        //}
        this.profile.put("remainingCarbsCap", ENDefaults.remainingCarbsCap)
        this.profile.put("enableUAM", uamAllowed)
        this.profile.put("A52_risk_enable", ENDefaults.A52_risk_enable)
        val smbEnabled = sp.getBoolean(R.string.key_use_smb, false)
        this.profile.put("SMBInterval", sp.getInt(R.string.key_smb_interval, ENDefaults.SMBInterval))
        this.profile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false))
        this.profile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false))
        this.profile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false))
        this.profile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering)
        this.profile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering)
        this.profile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smb_max_minutes, ENDefaults.maxSMBBasalMinutes))
        this.profile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uam_smb_max_minutes, ENDefaults.maxUAMSMBBasalMinutes))
        //set the min SMB amount to be the amount set by the pump.
        this.profile.put("bolus_increment", pumpBolusStep)
        this.profile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, ENDefaults.carbsReqThreshold))
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_min", SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_min, "0.8")))
        this.profile.put("autosens_max", SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_max, "1.2")))
//**********************************************************************************************************************************************
        // Eating Now
        this.profile.put("EatingNowTimeStart", sp.getInt(R.string.key_eatingnow_timestart, 9))
        val EatingNowTimeEnd = sp.getInt(R.string.key_eatingnow_timeend, 17)
        this.profile.put("EatingNowTimeEnd", EatingNowTimeEnd)
        val normalTargetBG = profile.getTargetMgdl()
        this.profile.put("EN_max_iob", sp.getDouble(R.string.key_en_max_iob, 0.0))
        this.profile.put("EN_max_iob_allow_smb", sp.getBoolean(R.string.key_en_max_iob_allow_smb, true))
        this.profile.put("enableGhostCOB", sp.getBoolean(R.string.key_use_ghostcob, false))
        this.profile.put("enableGhostCOBAlways", sp.getBoolean(R.string.key_use_ghostcob_always, false))
        val minCOB = sp.getInt(R.string.key_mincob, 0)
        this.profile.put("minCOB", minCOB)
        this.profile.put("allowENWovernight", sp.getBoolean(R.string.key_use_enw_overnight, false))
        this.profile.put("ENWIOBTrigger", sp.getDouble(R.string.key_enwindowiob, 0.0))
        val enwMinBolus = sp.getDouble(R.string.key_enwminbolus, 0.0)
        this.profile.put("ENWMinBolus", enwMinBolus)
        this.profile.put("ENautostart", sp.getBoolean(R.string.key_enautostart, false))
        this.profile.put("SMBbgOffset",profileUtil.convertToMgdl(sp.getDouble(R.string.key_eatingnow_smbbgoffset, 0.0), profileFunction.getUnits()))
        this.profile.put("SMBbgOffset_day",profileUtil.convertToMgdl(sp.getDouble(R.string.key_eatingnow_smbbgoffset_day, 0.0), profileFunction.getUnits()))
        this.profile.put("ISFbgscaler", sp.getDouble(R.string.key_eatingnow_isfbgscaler, 0.0))
        this.profile.put("MaxISFpct", sp.getInt(R.string.key_eatingnow_maxisfpct, 0))
        this.profile.put("useDynISF", sp.getBoolean(app.aaps.core.utils.R.string.key_dynamic_isf_enable, true))

        this.profile.put("percent", if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100)

        this.profile.put("EN_UAMPlusSMB_NoENW", sp.getBoolean(R.string.key_use_uamplus_noenw, false))
        this.profile.put("EN_UAMPlusTBR_NoENW", sp.getBoolean(R.string.key_use_uamplustbr_noenw, false))

        this.profile.put("EN_NoENW_maxBolus", sp.getDouble(R.string.key_eatingnow_noenw_maxbolus, 0.0))
        this.profile.put("EN_BGPlus_maxBolus", sp.getDouble(R.string.key_eatingnow_bgplus_maxbolus, 0.0))
//**********************************************************************************************************************************************

        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }
        // val now = System.currentTimeMillis()
        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)
        // as we have non default temps longer than 30 mintues
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        mGlucoseStatus.put("glucose", glucoseStatus.glucose)
        mGlucoseStatus.put("noise", glucoseStatus.noise)
        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta)
        }
        mGlucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        mGlucoseStatus.put("date", glucoseStatus.date)
        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        this.mealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation)
        this.mealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation)
        // this.mealData.put("lastBolusTime", mealData.lastBolusTime)
        // this.mealData.put("lastBolusUnits", repository.getLastBolusRecord()?.amount ?: 0L) // EatingNow
        this.mealData.put("lastCarbTime", mealData.lastCarbTime)

        // set the EN start time based on prefs
        val ENStartTime = 3600000 * sp.getInt(R.string.key_eatingnow_timestart, 9) + MidnightTime.calc(now)
        // this.mealData.put("ENStartTime",ENStartTime)

        // Create array to contain treatment times for ENWStartTime for today
        var ENWStartTimeArray: Array<Long> = arrayOf() // Create array to contain last treatment times for ENW for today
        var ENStartedArray: Array<Long> = arrayOf() // Create array to contain first treatment times for ENStartTime for today

        // get the FIRST and LAST carb time since EN activation NEW
        repository.getCarbsDataFromTimeToTime(ENStartTime,now,false, minCOB).blockingGet().let { ENCarbs->
            val firstENCarbTime = with(ENCarbs.firstOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("firstENCarbTime",firstENCarbTime)
            if (firstENCarbTime >0) ENStartedArray += firstENCarbTime

            val lastENCarbTime = with(ENCarbs.lastOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("lastENCarbTime",lastENCarbTime)
            ENWStartTimeArray += lastENCarbTime
        }

        // get the FIRST and LAST bolus time since EN activation NEW
        repository.getENBolusFromTimeOfType(ENStartTime, true, Bolus.Type.NORMAL, enwMinBolus ).blockingGet().let { ENBolus->
            val firstENBolusTime = with(ENBolus.firstOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("firstENBolusTime",firstENBolusTime)
            if (firstENBolusTime >0) ENStartedArray += firstENBolusTime

            val firstENBolusUnits = with(ENBolus.firstOrNull()?.amount) { this ?: 0 }
            this.mealData.put("firstENBolusUnits",firstENBolusUnits)

            val lastENBolusTime = with(ENBolus.lastOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("lastENBolusTime",lastENBolusTime)
            ENWStartTimeArray += lastENBolusTime

            val lastENBolusUnits = with(ENBolus.lastOrNull()?.amount) { this ?: 0 }
            this.mealData.put("lastENBolusUnits",lastENBolusUnits)
        }

        // get the FIRST and LAST ENTempTarget time since EN activation NEW
        repository.getENTemporaryTargetDataFromTimetoTime(ENStartTime,now,true).blockingGet().let { ENTempTarget ->
            val firstENTempTargetTime = with(ENTempTarget.firstOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("firstENTempTargetTime",firstENTempTargetTime)
            if (firstENTempTargetTime >0) ENStartedArray += firstENTempTargetTime

            val lastENTempTargetTime = with(ENTempTarget.lastOrNull()?.timestamp) { this ?: 0 }
            this.mealData.put("lastENTempTargetTime",lastENTempTargetTime)
            ENWStartTimeArray += lastENTempTargetTime

            val lastENTempTargetDuration = with(ENTempTarget.lastOrNull()?.duration) { this ?: 0 }
            this.mealData.put("lastENTempTargetDuration",lastENTempTargetDuration/60000)
        }

        // get the current EN TT info
        repository.getENTemporaryTargetActiveAt(now).blockingGet().lastOrNull()?.let { activeENTempTarget ->
            this.mealData.put("activeENTempTargetStartTime",activeENTempTarget.timestamp)
            this.mealData.put("activeENTempTargetDuration",activeENTempTarget.duration/60000)
            this.mealData.put("activeENPB",activeENTempTarget.reason == TemporaryTarget.Reason.EATING_NOW_PB)
        }

        val ENStartedTime = if (ENStartedArray.isNotEmpty()) ENStartedArray.min() else 0 // get the minimum (earliest) time from the array or make it 0
        this.mealData.put("ENStartedTime", ENStartedTime) // the time EN started today

        val ENWStartTime = if (ENWStartTimeArray.isNotEmpty()) ENWStartTimeArray.max() else 0 // get the maximum (latest) time from the array or make it 0

        // get the TDD since ENW Start
        this.mealData.put("ENWStartTime", ENWStartTime)
        var ENWBolusIOB = if (now < ENWStartTime+(4*3600000)) tddCalculator.calculate(ENWStartTime, now, allowMissingData = true)?.totalAmount else 0
        if (ENWBolusIOB == null) ENWBolusIOB = 0
        this.mealData.put("ENWBolusIOB", ENWBolusIOB)

        // calculate the time that breakfast should be finished or ignored
        var EN_BkfstCutOffhr = sp.getInt(R.string.key_eatingnow_bkfstcutoff, 0) // cutoff pref
        if (EN_BkfstCutOffhr == 0) EN_BkfstCutOffhr = EatingNowTimeEnd
        val EN_BkfstCutOffTime = 3600000 * EN_BkfstCutOffhr + MidnightTime.calc(now)


        // determine if the current ENW is the first meal of the day
        val firstMealWindow = (ENStartedTime == ENWStartTime && now < EN_BkfstCutOffTime)
        this.mealData.put("firstMealWindow", firstMealWindow)
        sp.putBoolean("ENdb_firstMealWindow", ENWStartTime == 0L && now < EN_BkfstCutOffTime) // has EN started? used for TT dialog only

        // use the settings based on the first meal validation
        if (firstMealWindow) {
            // Breakfast profile
            this.profile.put("ENWDuration", sp.getInt(R.string.key_enbkfstwindowminutes, 0)) // ENBkfstWindow
            this.profile.put("MealPct", sp.getInt(R.string.key_eatingnow_breakfastpct, 100)) // meal scaling - BreakfastPct
            this.profile.put("ENW_maxBolus_COB", sp.getDouble(R.string.key_eatingnow_cobboost_maxbolus_breakfast, 0.0)) // EN_COB_maxBolus_breakfast
            this.profile.put("ENW_maxBolus_UAM", sp.getDouble(R.string.key_eatingnow_uam_maxbolus_breakfast, 0.0)) // EN_UAM_maxBolus_breakfast
            this.profile.put("ENW_maxPreBolus", sp.getDouble(R.string.key_eatingnow_uambgboost_maxbolus_bkfast, 0.0)) // EN_UAMPlus_PreBolus_bkfast
            this.profile.put("ENW_maxBolus_UAM_plus", sp.getDouble(R.string.key_eatingnow_uamplus_maxbolus_bkfast, 0.0)) //EN_UAMPlus_maxBolus_bkfst
            this.profile.put("ENW_maxIOB", sp.getDouble(R.string.key_enw_breakfast_max_tdd, 0.0)) // ENW_breakfast_max_tdd
        } else {
            // Subsequent meals profile
            this.profile.put("ENWDuration", sp.getInt(R.string.key_eatingnow_enwindowminutes, 0)) // ENWindow
            this.profile.put("MealPct", sp.getInt(R.string.key_eatingnow_pct, 100)) // meal scaling - ENWPct
            this.profile.put("ENW_maxBolus_COB", sp.getDouble(R.string.key_eatingnow_cobboost_maxbolus, 0.0)) //EN_COB_maxBolus
            this.profile.put("ENW_maxBolus_UAM", sp.getDouble(R.string.key_eatingnow_uamboost_maxbolus, 0.0)) //EN_UAM_maxBolus
            this.profile.put("ENW_maxPreBolus", sp.getDouble(R.string.key_eatingnow_uambgboost_maxbolus, 0.0)) //EN_UAMPlus_PreBolus
            this.profile.put("ENW_maxBolus_UAM_plus", sp.getDouble(R.string.key_eatingnow_uamplus_maxbolus, 0.0)) //EN_UAMPlus_maxBolus
            this.profile.put("ENW_maxIOB", sp.getDouble(R.string.key_enw_max_tdd, 0.0)) //ENW_max_tdd
        }

        // 3PM is used as a low basal point at which the rest of the day leverages for ISF variance when using one ISF in the profile
        this.profile.put("enableBasalAt3PM", sp.getBoolean(R.string.key_use_3pm_basal, false))
        this.profile.put("BasalAt3PM", profile.getBasal(3600000*15+MidnightTime.calc(now)))

        // TDD related functions
        val enableSensTDD = sp.getBoolean(R.string.key_use_sens_tdd, false)
        this.profile.put("use_sens_TDD", enableSensTDD) // Override profile ISF with TDD ISF if selected in prefs
        val enableSensLCTDD = sp.getBoolean(R.string.key_use_sens_lctdd, false)
        this.profile.put("use_sens_LCTDD", enableSensLCTDD) // Override profile ISF with LCTDD ISF if selected in prefs
        this.profile.put("sens_TDD_scale",SafeParse.stringToDouble(sp.getString(R.string.key_sens_tdd_scale,"100")))
        val enableSRTDD = sp.getBoolean(R.string.key_use_sr_tdd, false)
        this.profile.put("enableSRTDD", enableSRTDD)


        // storing TDD values in prefs, terrible but hopefully effective
        // check when TDD last updated
        this.mealData.put("ENWTDD", if (now <= ENWStartTime+(4*3600000)) tddCalculator.calculate(ENWStartTime, now, false)?.bolusAmount ?: 0 else 0)
        val TDDLastUpdate =  sp.getLong("TDDLastUpdate",0)
        val TDDHrSinceUpdate = (now - TDDLastUpdate) / 3600000

        if (TDDLastUpdate == 0L || TDDHrSinceUpdate > 6) {
            // Generate the data for the larger datasets every 6 hours
            var TDDAvg7d = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.totalAmount
            if (TDDAvg7d == 0.0 || TDDAvg7d == null ) TDDAvg7d = ((basalRate * 12)*100)/21
            sp.putDouble("TDDAvg7d", TDDAvg7d)
            sp.putLong("TDDLastUpdate", now)
        }

        // use stored value where appropriate
        var TDDAvg7d = sp.getDouble("TDDAvg7d", ((basalRate * 12)*100)/21)

        // calculate the rest of the TDD data
        var TDDAvg1d = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.totalAmount
        // if (TDDAvg1d == null || TDDAvg1d < basalRate) TDDAvg1d =  tddCalculator.calculateDaily(-24, 0)?.totalAmount
        // if (TDDAvg1d != null) {
        //     if (TDDAvg1d < basalRate) TDDAvg1d = ((basalRate * 12)*100)/21
        // }

        var TDDLast4h = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        var TDDLast8h = tddCalculator.calculateDaily(-8, 0)?.totalAmount
        var TDDLast8hfor4h = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        if (TDDAvg1d == null || TDDAvg1d < basalRate * 12) TDDAvg1d = ((basalRate * 12)*100)/21
        if (TDDLast4h == null) TDDLast4h = (TDDAvg1d / 6)
        if (TDDLast8h == null) TDDLast8h = (TDDAvg1d / 3)
        if (TDDLast8hfor4h == null) TDDLast8hfor4h = (TDDAvg1d / 6)


        val TDDLast8_wt = (((1.4 * TDDLast4h) + (0.6 * TDDLast8hfor4h)) * 3)
        val TDD8h_exp = (3 * TDDLast8h)
        this.mealData.put("TDD8h_exp",TDD8h_exp)

        if ( TDDLast8_wt < (0.75 * TDDAvg7d)) TDDAvg7d = TDDLast8_wt + ( ( TDDLast8_wt / TDDAvg7d ) * ( TDDAvg7d - TDDLast8_wt ) )

        val TDD = (TDDLast8_wt * 0.33) + (TDDAvg7d * 0.34) + (TDDAvg1d * 0.33)

        val lastCannula = repository.getLastTherapyRecordUpToNow(TherapyEvent.Type.CANNULA_CHANGE).blockingGet()
        val lastCannulaTime = if (lastCannula is ValueWrapper.Existing) lastCannula.value.timestamp else 0L
        this.mealData.put("lastCannulaTime", lastCannulaTime)
        val lastCannAgeMins = ((now - lastCannulaTime) / 60000).toDouble()

        val tddAvg = (TDDAvg7d * 0.8) + (TDD * 0.2)
        val TDDLastCannula =
            if (lastCannAgeMins > 1440 && enableSensLCTDD) tddCalculator.calculateDaily(lastCannulaTime, now)?.totalAmount?.div((lastCannAgeMins/1440)) ?: tddAvg
            else tddAvg

        this.mealData.put("TDDLastCannula", TDDLastCannula)
        this.mealData.put("TDDAvg7d", TDDAvg7d)
        this.mealData.put("TDDLastUpdate", sp.getLong("TDDLastUpdate", 0))
        // }

        // TIR Windows - 4 hours prior to current time - TIRB2

        val insulin = activePlugin.activeInsulin
        val insulinPeak = when {
            insulin.peak < 30   -> 30
            insulin.peak > 75   -> 75
            else                -> insulin.peak
        }
        val insulinDivisor = when {
            insulinPeak < 60    -> (90 - insulinPeak) + 30
            else                -> (90 - insulinPeak) + 40
        }

        this.profile.put("insulinType", insulin.friendlyName)
        this.profile.put("insulinPeak", insulinPeak)

        val isf = isfCalculator.calculateAndSetToProfile(
            profile.getIsfMgdl(),
            if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100,
            targetBg,
            insulinDivisor, glucoseStatus, tempTargetSet, this.profile)

        val resistancePerHr = sp.getDouble(R.string.en_resistance_per_hour, 0.0)
        this.profile.put("resistancePerHr", sp.getDouble(R.string.en_resistance_per_hour, 0.0))
        if (resistancePerHr > 0) {
            var TIRTarget = normalTargetBG + 20.0 // TIRB1 - lower band
            // TIR 4h ago
            tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(4, 3, normalTargetBG-9.0, TIRTarget)).let { tir ->
                this.mealData.put("TIRTW4H",tir.abovePct())
                this.mealData.put("TIRTW4L",tir.belowPct())
            }

            // TIR 3h ago
            tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(3, 2, normalTargetBG-9.0, TIRTarget)).let { tir ->
                this.mealData.put("TIRTW3H",tir.abovePct())
                this.mealData.put("TIRTW3L",tir.belowPct())
            }

            // TIR 2h ago
            tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(2, 1, normalTargetBG-9.0, TIRTarget)).let { tir ->
                this.mealData.put("TIRTW2H",tir.abovePct())
                this.mealData.put("TIRTW2L",tir.belowPct())
            }

            // TIR 1h ago
            tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(1, 0, normalTargetBG-9.0, TIRTarget)).let { tir ->
                this.mealData.put("TIRTW1H",tir.abovePct())
                this.mealData.put("TIRTW1L",tir.belowPct())
            }

            TIRTarget = normalTargetBG + 50.0 // TIRB2 - higher band
            this.mealData.put("TIRW4H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(4, 3, 72.0, TIRTarget)).abovePct())
            this.mealData.put("TIRW3H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(3, 2, 72.0, TIRTarget)).abovePct())
            this.mealData.put("TIRW2H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(2, 1, 72.0, TIRTarget)).abovePct())
            this.mealData.put("TIRW1H", tirCalculator.averageTIR(tirCalculator.calculateHoursPrior(1, 0, 72.0, TIRTarget)).abovePct())
        }

        this.profile.put("SR_TDD", TDDLastCannula / TDDAvg7d)
        this.profile.put("sens_LCTDD", isf.isf * TDD / TDDLastCannula)
        autosensData.put("ratio", autosensDataRatio * isf.ratio)

        this.profile.put("normalTarget", 99)
        this.microBolusAllowed = microBolusAllowed
        smbAlwaysAllowed = advancedFiltering
        currentTime = now
        this.flatBGsDetected = flatBGsDetected
    }

    init {
        injector.androidInjector().inject(this)
    }
}