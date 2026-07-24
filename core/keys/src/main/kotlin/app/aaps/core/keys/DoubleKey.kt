// Modified for Eating Now
package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1("insulin_button_increment_1", 0.5, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement2("insulin_button_increment_2", 1.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    OverviewInsulinButtonIncrement3("insulin_button_increment_3", 2.0, -5.0, 5.0, defaultedBySM = true, dependency = BooleanKey.OverviewShowInsulinButton),
    ActionsFillButton1("fill_button1", 0.3, 0.05, 20.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    ActionsFillButton2("fill_button2", 0.0, 0.05, 20.0, defaultedBySM = true),
    ActionsFillButton3("fill_button3", 0.0, 0.05, 20.0, defaultedBySM = true),
    SafetyMaxBolus("treatmentssafety_maxbolus", 3.0, 0.1, 60.0),
    ApsMaxBasal("openapsma_max_basal", 1.0, 0.1, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsSmbMaxIob("openapsmb_max_iob", 3.0, 0.0, 70.0, defaultedBySM = true, calculatedBySM = true),
    ApsAmaMaxIob("openapsma_max_iob", 1.5, 0.0, 25.0, defaultedBySM = true, calculatedBySM = true),
    ApsMaxDailyMultiplier("openapsama_max_daily_safety_multiplier", 3.0, 1.0, 10.0, defaultedBySM = true),
    ApsMaxCurrentBasalMultiplier("openapsama_current_basal_safety_multiplier", 4.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaBolusSnoozeDivisor("bolussnooze_dia_divisor", 2.0, 1.0, 10.0, defaultedBySM = true),
    ApsAmaMin5MinCarbsImpact("openapsama_min_5m_carbimpact", 3.0, 1.0, 12.0, defaultedBySM = true),
    ApsSmbMin5MinCarbsImpact("openaps_smb_min_5m_carbimpact", 8.0, 1.0, 12.0, defaultedBySM = true),
    AbsorptionCutOff("absorption_cutoff", 6.0, 4.0, 10.0),
    AbsorptionMaxTime("absorption_maxtime", 6.0, 4.0, 10.0),
    AutosensMin("autosens_min", 0.7, 0.1, 1.0, defaultedBySM = true, hideParentScreenIfHidden = true),
    AutosensMax("autosens_max", 1.2, 0.5, 3.0, defaultedBySM = true),
    ApsAutoIsfMin("autoISF_min", 1.0, 0.3, 1.0, defaultedBySM = true),
    ApsAutoIsfMax("autoISF_max", 1.0, 1.0, 3.0, defaultedBySM = true),
    ApsAutoIsfBgAccelWeight("bgAccel_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfBgBrakeWeight("bgBrake_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfLowBgWeight("lower_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfHighBgWeight("higher_ISFrange_weight", 0.0, 0.0, 2.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioBgRange("openapsama_smb_delivery_ratio_bg_range", 0.0, 0.0, 100.0, defaultedBySM = true),
    ApsAutoIsfPpWeight("pp_ISF_weight", 0.0, 0.0, 1.0, defaultedBySM = true),
    ApsAutoIsfDuraWeight("dura_ISF_weight", 0.0, 0.0, 3.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatio("openapsama_smb_delivery_ratio", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMin("openapsama_smb_delivery_ratio_min", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbDeliveryRatioMax("openapsama_smb_delivery_ratio_max", 0.5, 0.5, 1.0, defaultedBySM = true),
    ApsAutoIsfSmbMaxRangeExtension("openapsama_smb_max_range_extension", 1.0, 1.0, 5.0, defaultedBySM = true),
    ApsDynamicCrAdjustmentFactor("dynamic_cr_adjustment_factor", 0.5, 0.0, 2.0, dependency = BooleanKey.ApsUseDynamicCarbRatio),
    ApsDynamicCrWeightPercentage("dynamic_cr_weight_percentage", 0.65, 0.0, 1.0, dependency = BooleanKey.ApsUseDynamicCarbRatio),

    // AutoISF 3.2.0 - FSL/Libre raw calibration & smoothing
    FslCalOffset("fslCal_Offset", 0.0, -50.0, 50.0, defaultedBySM = true),
    FslCalSlope("fslCal_Slope", 1.0, 0.5, 1.5, defaultedBySM = true),
    FslSmoothAlpha("fsl_exp1_factor", 1.0, 0.1, 1.0, defaultedBySM = true),
    // AutoISF 3.2.0 - activity detection
    ActivityMonitorRatio("activity_ratio", 1.0, 0.0, 2.0, defaultedBySM = true),
    ActivityScaleFactor("activity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),
    InactivityScaleFactor("inactivity_scale_factor", 1.0, 0.0, 1.5, defaultedBySM = true, dependency = BooleanKey.ActivityMonitorDetection),

    NightModeBgOffset("night_mode_glucose_offset", 0.0, 0.0, 90.0, dependency = BooleanKey.NightMode),

    // Boost
    ApsBoostBolus("boost_bolus_cap", 2.5, 0.1, 10.0, defaultedBySM = true),
    ApsBoostMaxIob("boost_max_iob", 1.0, 0.1, 12.0, defaultedBySM = true),
    ApsBoostInsulinReqPct("boost_insulin_req_pct", 50.0, 30.0, 100.0, defaultedBySM = true),
    ApsBoostScale("boost_scale_value", 1.0, 0.1, 3.0, defaultedBySM = true),
    ApsBoostPercentScale("boost_percent_scale_factor", 200.0, 50.0, 500.0, defaultedBySM = true),
    ApsBoostSleepInHours("boost_sleep_in_hrs", 2.0, 0.0, 18.0, defaultedBySM = true),
    ApsBoostInactivityPct("boost_inactivity_pct", 130.0, 100.0, 200.0, defaultedBySM = true),
    ApsBoostActivityPct("boost_activity_pct", 80.0, 30.0, 150.0, defaultedBySM = true),
    ApsBoostPostExerciseRecoveryHours("boost_post_exercise_recovery_hours", 2.0, 0.5, 8.0, defaultedBySM = true),
    ApsBoostPostExerciseRecoveryScale("boost_post_exercise_recovery_scale", 0.5, 0.0, 1.0, defaultedBySM = true),

    // Boost V5 silent-shadow knobs
    ApsBoostV5Aggression("boost_v5_aggression", 1.0, 0.7, 1.3, defaultedBySM = true),
    ApsBoostV5HypoCaution("boost_v5_hypo_caution", 1.0, 1.0, 2.0, defaultedBySM = true),
    ApsBoostV5Sensitivity("boost_v5_sensitivity", 1.0, 0.8, 1.2, defaultedBySM = true),

    // Boost V5/V6 dose caps + V6 pre-meal (ported from boost_v6)
    ApsBoostCumulativeSmbCap60Min("boost_cumulative_smb_cap_60min", 10.0, 0.0, 10.0, defaultedBySM = true),
    ApsBoostV5ConfirmedCapU("boost_v5_confirmed_cap_u", 2.5, 0.0, 7.5, defaultedBySM = true),
    ApsBoostV5CommittedCapU("boost_v5_committed_cap_u", 0.5, 0.0, 2.5, defaultedBySM = true),
    ApsBoostV6PreMealTargetMgdl("boost_v6_pre_meal_target_mgdl", 72.0, 65.0, 90.0, defaultedBySM = true),
    ApsBoostV6PreMealLeadMin("boost_v6_pre_meal_lead_min", 60.0, 30.0, 90.0, defaultedBySM = true),

    // Eating Now

    // General
    Eatingnow_overnightSMB("overnightSMB", 0.0, 0.0, 270.0, defaultedBySM = true),
    highBGthreshold("highBGthreshold", 0.0, 0.0, 100.0, defaultedBySM = true),

    // ENW variable limits
    Eatingnow_enw_cob_maxbolus("Eatingnow_enw_cob_maxbolus", 0.0, 0.0, 5.0, defaultedBySM = true),
    Eatingnow_enw_uam_maxbolus("Eatingnow_enw_uam_maxbolus", 0.0, 0.0, 5.0, defaultedBySM = true),
    Eatingnow_enw_maxiob("Eatingnow_enw_maxiob", 0.0, 0.0, 15.0, defaultedBySM = true),
    Eatingnow_enw_prebolus("Eatingnow_enw_prebolus", 0.0, 0.0, 15.0, defaultedBySM = true),
    Eatingnow_enw_uamplus_maxbolus("Eatingnow_enw_uamplus_maxbolus", 0.0, 0.0, 5.0, defaultedBySM = true),
    Eatingnow_enw_triggerbolus("Eatingnow_enw_triggerbolus", 0.0, 0.0, 15.0, defaultedBySM = true)
}