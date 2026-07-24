package app.aaps.core.interfaces.stats

import app.aaps.core.interfaces.profile.Profile

interface DynIsfCalculator {
    fun calculate(profile: Profile): DynIsfResult

    /**
     * Compute the velocity-scaled sensitivity at an arbitrary BG level.
     * Uses the base sensitivity (sensNormalTarget) from a prior [calculate] call.
     * @param bg BG level in mg/dL
     * @param sensNormalTarget TDD-based sensitivity at normal target (from DynIsfResult.sensNormalTarget)
     * @param insulinDivisor divisor from insulin peak (from DynIsfResult.insulinDivisor)
     * @param useCap whether to apply the BG cap (bg > cap → cap + (bg - cap) / 3)
     */
    fun getIsfForBg(bg: Double, sensNormalTarget: Double, insulinDivisor: Int, useCap: Boolean): Double
}
