package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.interfaces.logging.AAPSLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boost V5 algorithm core — Observe-Confirm-Commit pipeline orchestrator.
 *
 * Status: PRE-ALPHA. Phase 1.a (meal_signal_score), 1.b (state machine), 1.c (AggressionBudget),
 * 2 (action multiplier), and 3 (safety gates) are all implemented in their respective files.
 * The orchestrator below stitches them into a single `decide()` entry point. The plugin's
 * `invoke()` wires inputs from oref / Boost services into [V5Inputs] and calls [decide];
 * that wiring lands in a follow-up task (#9: RT serialization), and feature-gating happens
 * via the plugin's `isEnabled()` returning false until shadow mode is approved.
 *
 * Architecture (mirrors `boost_v5_redesign_proposal.md` exactly):
 *
 *   Phase 1 — state estimation (no commitment):
 *     - mealSignalScore          (MealSignalScore.kt)
 *     - step + resetIfNeeded     (MealHypothesis.kt)
 *     - aggressionBudget         (AggressionBudget.kt)
 *
 *   Phase 2 — single decision rule:
 *     - mealActionMultiplier(state) × budget   (MealActionMultiplier.kt)
 *
 *   Phase 3 — ordered safety gates:
 *     - applyPhase3              (SafetyGates.kt)
 *
 * `baseInsulinReq` MUST come from Boost-flavoured oref calc (DynISF + 7D TDD with W8H pull-down +
 * TDD-anchored EMA sensitivity + autosens + hour-of-day ISF + TempTargets — all unchanged).
 * V5 contains zero sensitivity logic of its own. See `MIGRATION.md` and the AggressionBudget
 * KDoc for the full inheritance contract.
 */

/** All inputs V5 needs for one cycle. Caller assembles from oref + Boost services. */
data class V5Inputs(
    // Glucose status
    val delta: Double,
    val shortAvgDelta: Double,
    val deltaAccl: Double,
    val bg: Double,
    val eventualBg: Double,
    val targetBg: Double,
    val maxDelta: Double,
    val minGuardBg: Double,
    val minGuardThreshold: Double,
    /** Last ≥3 deltas (oldest → newest, including current). For [deltaDeclining]. */
    val deltaHistory: List<Double>,

    // IOB / dose context
    val iob: Double,
    val maxIob: Double,
    /** Boost-flavoured oref insulinReq for this cycle. NOT vanilla oref — see KDoc. */
    val baseInsulinReq: Double,
    val roundSmbTo: Double,
    val enableSmbPreChecks: Boolean,

    // ML model outputs
    val mlHypoRisk: Double?,
    val mlMealLikely: Double?,
    /** ML model invocation: re-run hypo risk at projected IOB. Null disables postActionRiskCheck. */
    val riskAtProjectedIob: ((projectedIob: Double) -> Double)? = null,

    // Cycle context
    val recentLowBg: Double,
    /** Cumulative BG rise over the last ~30 min, mg/dL. Derived from `shortAvgDelta * 6`. Fix 4 (2026-05-22). */
    val cumulativeRise30min: Double,
    val hour: Int,
    val exerciseActive: Boolean,
    val inPostExerciseWindow: Boolean,
    /** SLEEPING (prior-cycle sleep state). Gates the fast-carb fast-path off overnight. 2026-06-16. */
    val asleep: Boolean = false,
    /** Fast-carb fast-path toggle (ApsBoostV5FastCarbConfirm). Single-cycle confirm on sharp+accel+score. */
    val fastCarbConfirmEnabled: Boolean = false,
    val sensorQualityOk: Boolean = true,

    // Reset triggers
    val profileSwitched: Boolean = false,
    val pumpDisconnected: Boolean = false,
    val loopSuspended: Boolean = false,
    val timeJumpMinutes: Double = 0.0,

    // User-facing knobs
    val aggressionUserKnob: Double = 1.0,
    val hypoCautionUserKnob: Double = 1.0,
    /** Per-user "Sensitivity" budget multiplier ∈ [0.8, 1.2]. Default 1.0 = no change. */
    val sensitivityUserKnob: Double = 1.0,

    // Alpha: user-adjustable dose caps (default to the validated Fix-6 values). Let the operator
    // tighten V5's commit/holding doses live during active-dosing alpha.
    val confirmedCapU: Double = MAX_CONFIRMED_COMMIT_DOSE_U,
    val committedCapU: Double = MAX_COMMITTED_DOSE_U,
)

/** Persisted V5 state read from RT at cycle start, written back at cycle end. */
data class V5PersistedState(
    val mealHypothesis: MealHypothesisState = MealHypothesisState(),
    val mlMealLikelyNullStreak: Int = 0,
    /**
     * Previous cycle's meal_signal_score — input to the sustained-score early confirm
     * ([CONFIRM_MIN_OBSERVING_AGE_SCORE_READY], 2026-07-03). Held in V5StateStore's IN-MEMORY
     * cache only, deliberately NOT serialized to the preferences JSON blob: a process restart
     * loses it, which fails safe (streak=false → legacy confirm timing for one cycle).
     */
    val lastCycleScore: Double? = null,
)

/** Full per-cycle V5 output. Every field is reconstructable into the ~6 NS RT fields. */
data class V5Decision(
    val finalDose: Double,
    val score: Double,
    val scoreComponents: ScoreComponents,
    val mlWeightsRenormalized: Boolean,
    val mealHypothesis: MealHypothesis,
    val mealHypothesisAge: Int,
    val stateReset: Boolean,
    val aggressionBudget: AggressionBudgetResult,
    val actionMultiplier: Double,
    val velocityFactor: Double,      // 2026-07-10 telemetry: climb-velocity dose scale on the raw shot
    val insulinToDeliver: Double,    // = dose after velocity + state cap, before Phase-3 brakes
    val phase3: Phase3Result,        // phase3.finalDose = dose after the composed brake stack
    val newPersistedState: V5PersistedState,
)

@Singleton
class DetermineBasalBoostV5 @Inject constructor(
    @Suppress("unused") private val aapsLogger: AAPSLogger,
) {
    /** Run one full V5 cycle. Pure function over inputs + prior state. */
    fun decide(inputs: V5Inputs, persisted: V5PersistedState): V5Decision {
        // Reset state machine if any reset condition fired (reboot equivalents)
        val (resetState, didReset) = resetIfNeeded(
            current = persisted.mealHypothesis,
            profileSwitched = inputs.profileSwitched,
            pumpDisconnected = inputs.pumpDisconnected,
            loopSuspended = inputs.loopSuspended,
            timeJumpMinutes = inputs.timeJumpMinutes,
        )

        // Phase 1.a — meal_signal_score
        val nextNullStreak =
            if (inputs.mlMealLikely == null) persisted.mlMealLikelyNullStreak + 1 else 0
        val scoreResult = mealSignalScore(
            delta = inputs.delta,
            deltaAccl = inputs.deltaAccl,
            mlMealLikely = inputs.mlMealLikely,
            recentLowBg = inputs.recentLowBg,
            hour = inputs.hour,
            exerciseActive = inputs.exerciseActive,
            cumulativeRise30min = inputs.cumulativeRise30min,
            mlMealLikelyNullStreak = nextNullStreak,
        )

        // Phase 1.c HOISTED — AggressionBudget is state-independent (takes no meal-state input), so
        // compute it BEFORE the state step so the OBSERVING→CONFIRMED dose-adequacy gate can size the
        // prospective commit-shot. Pure reorder, no behaviour change. (2026-07-02)
        val budget = aggressionBudget(
            baseInsulinReq = inputs.baseInsulinReq,
            mlHypoRisk = inputs.mlHypoRisk,
            inPostExerciseWindow = inputs.inPostExerciseWindow,
            hypoCautionUserKnob = inputs.hypoCautionUserKnob,
            sensitivityUserKnob = inputs.sensitivityUserKnob,
        )

        // Dose-adequacy gate for OBSERVING→CONFIRMED (2026-07-02): the single per-session commit-shot
        // must beat one routine COMMITTED hold cycle (committedCapU) to be worth spending — else a
        // trivial pre-meal upswing burns the token and the committedInSession lock starves the meal on
        // holds alone (the 2026-07-01 eventualBG→372 incident). Uses the mlHypoRisk-DAMPED budget, so
        // confirm is also held back when hypo risk is elevated. Clamped strictly below confirmedCapU so
        // a manual committedCap ≥ confirmedCap can't make the gate unsatisfiable (which would silently
        // disable V6 meal response). Fast-carb fast-path is exempt (handled inside step()).
        // 2026-07-02 (2): the gate sizes the shot as it would actually DELIVER — including velocity
        // scaling — not the pre-velocity raw. Backtest (1,860 deduped cohort confirms): the raw gate
        // passed 1,072 of which 384 (35.8%) delivered BELOW the floor after velocity scaling — token
        // burnt on a sub-hold shot, recreating the starvation the gate exists to prevent. The
        // velocityFactor is hoisted here (pure fn of inputs) and reused by Phase 2.5 below.
        val velocityFactor = velocityScaledDoseFactor(inputs.cumulativeRise30min)
        val prospectiveConfirmShot = budget.budget * mealActionMultiplier(MealHypothesis.CONFIRMED, inputs.aggressionUserKnob) * velocityFactor
        val confirmDoseFloor = minOf(inputs.committedCapU, CONFIRM_DOSE_FLOOR_MAX_FRAC_OF_CONFIRMED_CAP * inputs.confirmedCapU)
        val confirmDoseAdequate = prospectiveConfirmShot > confirmDoseFloor

        // Phase 1.b — state machine step
        val newHypothesisState = step(
            current = resetState,
            score = scoreResult.score,
            eventualBg = inputs.eventualBg,
            targetBg = inputs.targetBg,
            delta = inputs.delta,
            deltaAccl = inputs.deltaAccl,
            deltaDeclining = deltaDeclining(inputs.deltaHistory, windowCycles = 2),
            asleep = inputs.asleep,
            exerciseActive = inputs.exerciseActive,
            // 2026-07-02: post-hypo rescue-carb guard — fast path suppressed when the 60-min low
            // is < FAST_CONFIRM_MIN_RECENT_LOW_MGDL (replay-calibrated; see MealHypothesis.kt).
            fastConfirmEnabled = fastConfirmAllowed(inputs.fastCarbConfirmEnabled, inputs.recentLowBg),
            confirmDoseAdequate = confirmDoseAdequate,
            // 2026-07-03 sustained-score early confirm: was LAST cycle's score already confirm-ready?
            // Sourced from the in-memory persisted state (null on cold start → false → legacy timing).
            scoreReadyStreak = (persisted.lastCycleScore ?: 0.0) >= CONFIRM_SCORE,
        )

        // Phase 2 — single decision rule
        val actionMult = mealActionMultiplier(newHypothesisState.state, inputs.aggressionUserKnob)
        val rawInsulinToDeliver = budget.budget * actionMult

        // Phase 2.5 — Fix 6 dose calibration (2026-05-26)
        //
        // Diagnosed from 2026-05-25 evening meal: V5's CONFIRMED commit-shot produces 2.5-3.15U
        // single doses on slow-meal climbs, calibrated for the sharp-meal case where the 1.8×
        // CONFIRMED multiplier matches a genuine "catch-up" need. For slow meals (the case
        // Fix 4+5 was added to detect), 3U commits are dangerously aggressive — V4.4.2's actual
        // 1.5U delivery on the same climb caused a hypo (BG 192 → 48). V5 dosing 2-3× more
        // would be catastrophic.
        //
        // Two-stage calibration applied here:
        //   1. Velocity scaling — scale the dose by climb velocity (Fix 4's cumulativeRise30min
        //      signal). Sharp meals (≥50 mg/dL over 30 min) keep the full dose; slow meals
        //      (≤25 mg/dL) get 40% of it; linear interpolation between.
        //   2. State-specific hard cap — regardless of computed dose, CONFIRMED commits cap at
        //      MAX_CONFIRMED_COMMIT_DOSE_U; COMMITTED holding doses cap at MAX_COMMITTED_DOSE_U.
        //      These caps are calibrated so V5 cannot deliver more than V4.4.2 would on the same
        //      cycle, regardless of any upstream bug.
        // velocityFactor hoisted above the state step (used by the confirm dose gate). (2026-07-02)
        val velocityScaled = rawInsulinToDeliver * velocityFactor
        val cappedInsulinToDeliver = applyStateDoseCap(newHypothesisState.state, velocityScaled, inputs.confirmedCapU, inputs.committedCapU)
        val insulinToDeliver = cappedInsulinToDeliver

        // Phase 3 — ordered safety gates
        val phase3 = applyPhase3(Phase3Inputs(
            insulinToDeliver = insulinToDeliver,
            enableSmbPreChecks = inputs.enableSmbPreChecks,
            minGuardBg = inputs.minGuardBg,
            minGuardThreshold = inputs.minGuardThreshold,
            maxDelta = inputs.maxDelta,
            bg = inputs.bg,
            iob = inputs.iob,
            maxIob = inputs.maxIob,
            deltaAccl = inputs.deltaAccl,
            delta = inputs.delta,
            baseInsulinReq = inputs.baseInsulinReq,
            roundSmbTo = inputs.roundSmbTo,
            sensorQualityOk = inputs.sensorQualityOk,
            riskAtProjectedIob = inputs.riskAtProjectedIob,
            mlHypoRisk = inputs.mlHypoRisk,
        ))

        return V5Decision(
            finalDose = phase3.finalDose,
            score = scoreResult.score,
            scoreComponents = scoreResult.components,
            mlWeightsRenormalized = scoreResult.mlWeightsRenormalized,
            mealHypothesis = newHypothesisState.state,
            mealHypothesisAge = newHypothesisState.ageCycles,
            stateReset = didReset,
            aggressionBudget = budget,
            actionMultiplier = actionMult,
            velocityFactor = velocityFactor,
            insulinToDeliver = insulinToDeliver,
            phase3 = phase3,
            newPersistedState = V5PersistedState(
                mealHypothesis = newHypothesisState,
                mlMealLikelyNullStreak = nextNullStreak,
                lastCycleScore = scoreResult.score,
            ),
        )
    }
}

// ===== Fix 6 dose calibration (2026-05-26) =====

/**
 * Hard upper cap on the CONFIRMED commit-shot dose, in units. The 5/25 evening meal showed
 * V5's raw CONFIRMED dose reaches 2.5-3.15U for slow-meal climbs at typical baseInsulinReq —
 * far exceeding what V4.4.2 actually delivers in the same scenario (~1.5U cumulative across
 * multiple small SMBs), and on a climb where 1.5U already caused a hypo. 1.0U is the DEFAULT
 * CONFIRMED commit cap. NOTE: the effective cap at runtime is `Inputs.confirmedCapU`
 * (preferences.ApsBoostV5ConfirmedCapU, defaulting to this value) — this constant is the default,
 * not a hard floor/ceiling; a user/auto-config pref above 1.0 will raise the actual cap.
 */
internal const val MAX_CONFIRMED_COMMIT_DOSE_U = 1.0

/**
 * Hard upper cap on COMMITTED holding doses (post-commit-shot, sustaining meal coverage).
 * V4.4.2's per-cycle SMBs in this phase are typically 0.10-0.20U. Set to 0.25U: matches V4.4.x
 * per-cycle magnitude, while leaving room for slightly larger holding doses on genuine
 * sustained climbs. With rapid invokes (every 1-3 min in practice during meal events), this
 * caps the COMMITTED holding-dose stream to ≤0.25U per invoke.
 */
internal const val MAX_COMMITTED_DOSE_U = 0.25

/**
 * Cumulative-rise (mg/dL over last 30 min, from Fix 4) below this threshold scales the dose
 * to [VELOCITY_SCALE_FLOOR]. Above [VELOCITY_RISE_HI_MGDL] the dose is unscaled. Linear in
 * between.
 */
internal const val VELOCITY_RISE_LO_MGDL = 25.0
internal const val VELOCITY_RISE_HI_MGDL = 50.0
internal const val VELOCITY_SCALE_FLOOR = 0.40

/**
 * Velocity-aware dose scaling factor. Returns 1.0 for sharp meals (cumulative rise ≥ 50 mg/dL
 * over 30 min — full V5 dose applies), [VELOCITY_SCALE_FLOOR] for slow meals (≤ 25 mg/dL —
 * 40% of dose), linear interpolation between.
 *
 * Rationale: V5's CONFIRMED 1.8× catch-up multiplier is calibrated for sharp meals where waiting
 * 2 cycles in OBSERVING genuinely warrants a larger commit. Fix 4+5 unlocked V5 detection on
 * slow meals where the same 1.8× multiplier produces over-aggressive commits relative to the
 * actual carb absorption rate. Scaling by the same Fix 4 signal that triggered the detection
 * is the natural calibration knob.
 */
internal fun velocityScaledDoseFactor(cumulativeRise30min: Double): Double {
    if (cumulativeRise30min >= VELOCITY_RISE_HI_MGDL) return 1.0
    if (cumulativeRise30min <= VELOCITY_RISE_LO_MGDL) return VELOCITY_SCALE_FLOOR
    val span = VELOCITY_RISE_HI_MGDL - VELOCITY_RISE_LO_MGDL
    val frac = (cumulativeRise30min - VELOCITY_RISE_LO_MGDL) / span
    return VELOCITY_SCALE_FLOOR + (1.0 - VELOCITY_SCALE_FLOOR) * frac
}

/**
 * State-specific hard upper cap on the dose. Defense-in-depth: even if the upstream multiplier
 * stack produces a large value, this clamps it to a known-safe magnitude per state.
 *
 * - **CONFIRMED**: capped at [MAX_CONFIRMED_COMMIT_DOSE_U] (1.0 U). The commit-shot is the
 *   single most dose-impactful decision V5 makes; safety floor it tightly until validation
 *   data justifies raising the cap.
 * - **COMMITTED**: capped at [MAX_COMMITTED_DOSE_U] (0.5 U). Multiple of these can fire per
 *   meal (one per cycle while in COMMITTED state), so per-cycle cap must be smaller.
 * - **IDLE / OBSERVING / RECOVERING**: no cap; these states either don't dose (IDLE,
 *   RECOVERING) or use a test-dose multiplier (OBSERVING 0.3×) that doesn't reach dangerous
 *   magnitudes from any plausible baseInsulinReq.
 */
internal fun applyStateDoseCap(
    state: MealHypothesis,
    dose: Double,
    confirmedCapU: Double = MAX_CONFIRMED_COMMIT_DOSE_U,
    committedCapU: Double = MAX_COMMITTED_DOSE_U,
): Double = when (state) {
    MealHypothesis.CONFIRMED -> dose.coerceAtMost(confirmedCapU)
    MealHypothesis.COMMITTED -> dose.coerceAtMost(committedCapU)
    else -> dose
}
