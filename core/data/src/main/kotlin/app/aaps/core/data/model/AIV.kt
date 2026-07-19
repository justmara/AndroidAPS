package app.aaps.core.data.model

/**
 * AutoISF key intermediate values.
 *
 * In upstream aisf-3.2.0 these are persisted to the database for sub-graph plotting.
 * In this self-contained port the values are kept in memory only (no DB entity / DAO),
 * since the persistence path is pure telemetry and not used for dosing.
 */
data class AIV(
    var timestamp: Long = 0L,
    var acceIsf: Double = 0.0,
    var bgIsf: Double = 0.0,
    var ppIsf: Double = 0.0,
    var driftIsf: Double = 0.0,
    var duraIsf: Double = 0.0,
    var finalIsf: Double = 0.0,
    var iobThEffective: Double = 0.0
)
