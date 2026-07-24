package app.aaps.core.interfaces.stats

import app.aaps.core.interfaces.profile.Profile

interface DynIsfCalculator {
    fun calculate(profile: Profile): DynIsfResult
}
