package app.aaps.core.interfaces.stats

data class DynIsfResult(
    val tdd1D: Double? = null,
    val tdd7D: Double? = null,
    val tddLast24H: Double? = null,
    val tddLast4H: Double? = null,
    val tddLast8to4H: Double? = null,
    val tdd: Double? = null,
    // raw weighted TDD before the DynISF adjustment factor — reused by Dynamic CR so it works whenever any TDD exists
    val tddRaw: Double? = null,
    val variableSensitivity: Double? = null,
    val insulinDivisor: Int = 0,
    val tddLast24HCarbs: Double = 0.0,
    val tdd7DDataCarbs: Double = 0.0,
    val tdd7DAllDaysHaveCarbs: Boolean = false
) {
    fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null
    fun tddQuickCalculated() = tddLast4H != null && tddLast8to4H != null

    fun log() =
        "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd variableSensitivity=$variableSensitivity insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
}
