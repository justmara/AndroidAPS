package info.nightscout.plugins.aps.Boost

import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.plugins.aps.Boost.BoostDefaults
import info.nightscout.plugins.aps.APSResultObject
import info.nightscout.core.extensions.convertedToAbsolute
import info.nightscout.core.extensions.getPassedDurationToTimeInMinutes
import info.nightscout.core.extensions.plannedRemainingMinutes
import info.nightscout.core.profile.ProfileSealed
import info.nightscout.core.validators.LoopVariantPreference
import info.nightscout.database.ValueWrapper
import info.nightscout.database.entities.Bolus
import info.nightscout.database.entities.TherapyEvent
import info.nightscout.database.impl.AppRepository
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.aps.DetermineBasalAdapter
import info.nightscout.interfaces.aps.SMBDefaults
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.iob.GlucoseStatus
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.iob.IobTotal
import info.nightscout.interfaces.iob.MealData
import info.nightscout.interfaces.plugin.ActivePlugin
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.stats.TddCalculator
import info.nightscout.interfaces.stats.TirCalculator
import info.nightscout.interfaces.utils.MidnightTime
import info.nightscout.interfaces.utils.Round
import info.nightscout.plugins.aps.R
import info.nightscout.plugins.aps.logger.LoggerCallback
import info.nightscout.plugins.aps.openAPSSMB.DetermineBasalResultSMB
import info.nightscout.plugins.aps.utils.ScriptReader
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.SafeParse
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.utils.T
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import javax.inject.Inject

class DetermineBasalAdapterBoostJS internal constructor(private val scriptReader: ScriptReader, private val injector: HasAndroidInjector) : DetermineBasalAdapter {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: Constraints
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var tddCalculator: TddCalculator

    private var profile = JSONObject()
    private var mGlucoseStatus = JSONObject()
    private var iobData: JSONArray? = null
    private var mealData = JSONObject()
    private var currentTemp = JSONObject()
    private var autosensData = JSONObject()
    private var microBolusAllowed = false
    private var smbAlwaysAllowed = false
    private var currentTime: Long = 0
    private var flatBGsDetected = false

    override var currentTempParam: String? = null
    override var iobDataParam: String? = null
    override var glucoseStatusParam: String? = null
    override var profileParam: String? = null
    override var mealDataParam: String? = null
    override var scriptDebug = ""

    @Suppress("SpellCheckingInspection")
    override operator fun invoke(): DetermineBasalResultSMB? {
        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal <<<")
        aapsLogger.debug(LTag.APS, "Glucose status: " + mGlucoseStatus.toString().also { glucoseStatusParam = it })
        aapsLogger.debug(LTag.APS, "IOB data:       " + iobData.toString().also { iobDataParam = it })
        aapsLogger.debug(LTag.APS, "Current temp:   " + currentTemp.toString().also { currentTempParam = it })
        aapsLogger.debug(LTag.APS, "Profile:        " + profile.toString().also { profileParam = it })
        aapsLogger.debug(LTag.APS, "Meal data:      " + mealData.toString().also { mealDataParam = it })
        aapsLogger.debug(LTag.APS, "Autosens data:  $autosensData")
        aapsLogger.debug(LTag.APS, "Reservoir data: " + "undefined")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "SMBAlwaysAllowed:  $smbAlwaysAllowed")
        aapsLogger.debug(LTag.APS, "CurrentTime: $currentTime")
        aapsLogger.debug(LTag.APS, "flatBGsDetected: $flatBGsDetected")
        var determineBasalResult: DetermineBasalResultSMB? = null
        val rhino = Context.enter()
        val scope: Scriptable = rhino.initStandardObjects()
        // Turn off optimization to make Rhino Android compatible
        rhino.optimizationLevel = -1
        try {

            //register logger callback for console.log and console.error
            ScriptableObject.defineClass(scope, LoggerCallback::class.java)
            val myLogger = rhino.newObject(scope, "LoggerCallback", null)
            scope.put("console2", scope, myLogger)
            rhino.evaluateString(scope, readFile("OpenAPSAMA/loggerhelper.js"), "JavaScript", 0, null)

            //set module parent
            rhino.evaluateString(scope, "var module = {\"parent\":Boolean(1)};", "JavaScript", 0, null)
            rhino.evaluateString(scope, "var round_basal = function round_basal(basal, profile) { return basal; };", "JavaScript", 0, null)
            rhino.evaluateString(scope, "require = function() {return round_basal;};", "JavaScript", 0, null)

            //generate functions "determine_basal" and "setTempBasal"
            rhino.evaluateString(scope, readFile(LoopVariantPreference.getVariantFileName(sp, "Boost")), "JavaScript", 0, null)
            rhino.evaluateString(scope, readFile("Boost/basal-set-temp.js"), "setTempBasal.js", 0, null)
            val determineBasalObj = scope["determine_basal", scope]
            val setTempBasalFunctionsObj = scope["tempBasalFunctions", scope]

            //call determine-basal
            if (determineBasalObj is Function && setTempBasalFunctionsObj is NativeObject) {

                //prepare parameters
                val params = arrayOf(
                    makeParam(mGlucoseStatus, rhino, scope),
                    makeParam(currentTemp, rhino, scope),
                    makeParamArray(iobData, rhino, scope),
                    makeParam(profile, rhino, scope),
                    makeParam(autosensData, rhino, scope),
                    makeParam(mealData, rhino, scope),
                    setTempBasalFunctionsObj,
                    java.lang.Boolean.valueOf(microBolusAllowed),
                    makeParam(null, rhino, scope),  // reservoir data as undefined
                    java.lang.Long.valueOf(currentTime),
                    java.lang.Boolean.valueOf(flatBGsDetected)
                )
                val jsResult = determineBasalObj.call(rhino, scope, scope, params) as NativeObject
                scriptDebug = LoggerCallback.scriptDebug

                // Parse the jsResult object to a JSON-String
                val result = NativeJSON.stringify(rhino, scope, jsResult, null, null).toString()
                aapsLogger.debug(LTag.APS, "Result: $result")
                try {
                    val resultJson = JSONObject(result)
                    determineBasalResult = DetermineBasalResultSMB(injector, resultJson)
                } catch (e: JSONException) {
                    aapsLogger.error(LTag.APS, "Unhandled exception", e)
                }
            } else {
                aapsLogger.error(LTag.APS, "Problem loading JS Functions")
            }
        } catch (e: IOException) {
            aapsLogger.error(LTag.APS, "IOException")
        } catch (e: RhinoException) {
            aapsLogger.error(LTag.APS, "RhinoException: (" + e.lineNumber() + "," + e.columnNumber() + ") " + e.toString())
        } catch (e: IllegalAccessException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InstantiationException) {
            aapsLogger.error(LTag.APS, e.toString())
        } catch (e: InvocationTargetException) {
            aapsLogger.error(LTag.APS, e.toString())
        } finally {
            Context.exit()
        }
        glucoseStatusParam = mGlucoseStatus.toString()
        iobDataParam = iobData.toString()
        currentTempParam = currentTemp.toString()
        profileParam = profile.toString()
        mealDataParam = mealData.toString()
        return determineBasalResult
    }

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
        flatBGsDetected: Boolean,
        tdd1D: Double?,
        tdd7D: Double?,
        tddLast24H: Double?,
        tddLast4H: Double?,
        tddLast8to4H: Double?
    ) {
        val pump = activePlugin.activePump
        val pumpBolusStep = pump.pumpDescription.bolusStep

        val insulin = activePlugin.activeInsulin
        val insulinType = insulin.friendlyName
        val insulinPeak: Int = insulin.peak


        this.profile.put("max_iob", maxIob)
        //mProfile.put("dia", profile.getDia());
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("min_bg", minBg)
        this.profile.put("max_bg", maxBg)
        this.profile.put("target_bg", targetBg)
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))
        this.profile.put("lgsThreshold", Profile.toMgdl(sp.getDouble(R.string.key_lgs_threshold, 65.0)))

        //mProfile.put("high_temptarget_raises_sensitivity", SP.getBoolean(R.string.key_high_temptarget_raises_sensitivity, BoostDefaults.high_temptarget_raises_sensitivity));
//**********************************************************************************************************************************************
        //this.profile.put("high_temptarget_raises_sensitivity", false)
        //mProfile.put("low_temptarget_lowers_sensitivity", SP.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, BoostDefaults.low_temptarget_lowers_sensitivity));
        this.profile.put("high_temptarget_raises_sensitivity",sp.getBoolean(R.string.key_high_temptarget_raises_sensitivity,BoostDefaults.high_temptarget_raises_sensitivity))
        this.profile.put("low_temptarget_lowers_sensitivity",sp.getBoolean(R.string.key_low_temptarget_lowers_sensitivity,BoostDefaults.low_temptarget_lowers_sensitivity))
        this.profile.put("enableBoostPercentScale", sp.getBoolean(R.string.key_enable_boost_percent_scale, false))
        this.profile.put("SensBGCap", sp.getBoolean(R.string.key_enableSensBGCap, false))
        this.profile.put("enableCircadianISF", sp.getBoolean(R.string.key_enableCircadianISF, false))
        //this.profile.put("low_temptarget_lowers_sensitivity", false)
//**********************************************************************************************************************************************
        this.profile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, BoostDefaults.sensitivity_raises_target))
        this.profile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, BoostDefaults.resistance_lowers_target))
        this.profile.put("adv_target_adjustments", BoostDefaults.adv_target_adjustments)
        this.profile.put("exercise_mode", BoostDefaults.exercise_mode)
        this.profile.put("half_basal_exercise_target", BoostDefaults.half_basal_exercise_target)
        this.profile.put("maxCOB", BoostDefaults.maxCOB)
        this.profile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour())
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact));
        //}
        this.profile.put("remainingCarbsCap", BoostDefaults.remainingCarbsCap)
        this.profile.put("enableUAM", uamAllowed)
        this.profile.put("A52_risk_enable", BoostDefaults.A52_risk_enable)
        val smbEnabled = sp.getBoolean(R.string.key_use_smb, false)
        this.profile.put("SMBInterval", sp.getInt(R.string.key_smb_interval, BoostDefaults.SMBInterval))
        this.profile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false))
        this.profile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false))
        this.profile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false))
        this.profile.put("allowBoost_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowBoost_with_high_temptarget, false))
        this.profile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering)
        this.profile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering)
        this.profile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smb_max_minutes, BoostDefaults.maxSMBBasalMinutes))
        this.profile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uam_smb_max_minutes, BoostDefaults.maxUAMSMBBasalMinutes))
        this.profile.put("DynISFAdjust",  SafeParse.stringToDouble(sp.getString(R.string.key_DynISFAdjust,"100")))
        this.profile.put("insulinType", insulinType)
        this.profile.put("insulinPeak", insulinPeak)
        this.profile.put("profilePercent",  if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100)

        //set the min SMB amount to be the amount set by the pump.
        this.profile.put("bolus_increment", pumpBolusStep)
        this.profile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, BoostDefaults.carbsReqThreshold))
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_max", SafeParse.stringToDouble(sp.getString(info.nightscout.core.utils.R.string.key_openapsama_autosens_max, "1.2")))
        this.profile.put("autosens_min", SafeParse.stringToDouble(sp.getString(info.nightscout.core.utils.R.string.key_openapsama_autosens_min, "0.8")))
        this.profile.put("lgsThreshold", sp.getInt(R.string.key_lgs_threshold, 60))
//**********************************************************************************************************************************************
        //this.profile.put("scale_min",SafeParse.stringToDouble(sp.getString(R.string.key_scale_min,"70")))
        //this.profile.put("scale_max",SafeParse.stringToDouble(sp.getString(R.string.key_scale_max,"20")))
        //this.profile.put("scale_50",SafeParse.stringToDouble(sp.getString(R.string.key_scale_50,"2")))
//MP: Boost_boluscap start
        this.profile.put("boost_bolus",SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_bolus,"2.5")))
        this.profile.put("boost_percent_scale",SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_scale_factor,"200")))
        this.profile.put("boost_start",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_start, "7.0")))
        this.profile.put("boost_end",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_end, "8.0")))
        this.profile.put("boost_maxIOB",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_max_iob, "1.0")))
        this.profile.put("Boost_InsulinReq",  SafeParse.stringToDouble(sp.getString(R.string.key_boost_insulinreq,"50.0")))
        this.profile.put("boost_scale",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_scale, "1.0")))
        this.profile.put("inactivity_steps",SafeParse.stringToDouble(sp.getString(R.string.key_inactivity_steps,"400")))
        this.profile.put("inactivity_pct",SafeParse.stringToDouble(sp.getString(R.string.key_inactivity_pct_inc,"130")))
        this.profile.put("sleep_in_hrs",SafeParse.stringToDouble(sp.getString(R.string.key_sleep_in_hrs,"2")))
        this.profile.put("sleep_in_steps",SafeParse.stringToDouble(sp.getString(R.string.key_sleep_in_steps,"250")))
        this.profile.put("activity_steps_5",SafeParse.stringToDouble(sp.getString(R.string.key_activity_steps_5,"420")))
        this.profile.put("activity_steps_30",SafeParse.stringToDouble(sp.getString(R.string.key_activity_steps_30,"1200")))
        this.profile.put("activity_steps_60",SafeParse.stringToDouble(sp.getString(R.string.key_activity_hour_steps,"1800")))
        this.profile.put("activity_pct",SafeParse.stringToDouble(sp.getString(R.string.key_activity_pct_inc,"80")))
        //this.profile.put("Boost_eventualBG",SafeParse.stringToDouble(sp.getString(R.string.key_Boost_eventualBG,"155")))
        //this.profile.put("W2_IOB_threshold",SafeParse.stringToDouble(sp.getString(R.string.key_w2_iob_threshold,"20")))
        //this.profile.put("Boost_hyperBG",SafeParse.stringToDouble(sp.getString(R.string.key_Boost_hyperBG,"220")));
//**********************************************************************************************************************************************
        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }
        val now = System.currentTimeMillis()
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
        this.mealData.put("lastBolusTime", mealData.lastBolusTime)
        this.mealData.put("lastCarbTime", mealData.lastCarbTime)

        this.mealData.put("TDDAIMI1", tddCalculator.averageTDD(tddCalculator.calculate(1, false))?.totalAmount)
        this.mealData.put("TDDAIMI7", tddCalculator.averageTDD(tddCalculator.calculate(7, false))?.totalAmount)
        this.mealData.put("TDDLast4", tddCalculator.calculateDaily(-4, 0)?.totalAmount)
        this.mealData.put("TDDLast8", tddCalculator.calculateDaily(-8, 0)?.totalAmount)
        this.mealData.put("TDD4to8", tddCalculator.calculateDaily(-8, -4)?.totalAmount)
        this.mealData.put("TDD24", tddCalculator.calculateDaily(-24, 0)?.totalAmount)

        this.profile.put("recentSteps5Minutes", StepService.getRecentStepCount5Min())
        this.profile.put("recentSteps10Minutes", StepService.getRecentStepCount10Min())
        this.profile.put("recentSteps15Minutes", StepService.getRecentStepCount15Min())
        this.profile.put("recentSteps30Minutes", StepService.getRecentStepCount30Min())
        this.profile.put("recentSteps60Minutes", StepService.getRecentStepCount60Min())
        //this.profile.put("recentSteps36hrs", StepService.getRecentStepCount36Hrs())

        if (constraintChecker.isAutosensModeEnabled().value()) {
            autosensData.put("ratio", autosensDataRatio)
        } else {
            autosensData.put("ratio", 1.0)
        }
        this.microBolusAllowed = microBolusAllowed
        smbAlwaysAllowed = advancedFiltering
        currentTime = now
        this.flatBGsDetected = flatBGsDetected
    }

    private fun makeParam(jsonObject: JSONObject?, rhino: Context, scope: Scriptable): Any {
        return if (jsonObject == null) Undefined.instance
        else NativeJSON.parse(rhino, scope, jsonObject.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    private fun makeParamArray(jsonArray: JSONArray?, rhino: Context, scope: Scriptable): Any {
        return NativeJSON.parse(rhino, scope, jsonArray.toString()) { _: Context?, _: Scriptable?, _: Scriptable?, objects: Array<Any?> -> objects[1] }
    }

    @Throws(IOException::class) private fun readFile(filename: String): String {
        val bytes = scriptReader.readFile(filename)
        var string = String(bytes, StandardCharsets.UTF_8)
        if (string.startsWith("#!/usr/bin/env node")) {
            string = string.substring(20)
        }
        return string
    }

    init {
        injector.androidInjector().inject(this)
    }
}