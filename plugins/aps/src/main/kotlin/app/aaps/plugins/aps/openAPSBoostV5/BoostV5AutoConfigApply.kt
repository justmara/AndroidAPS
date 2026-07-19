package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.DoubleKey

/**
 * Pure helpers for applying a [BoostV5AutoConfig.V5Suggestion] to preferences while respecting any
 * value the user — or a preset (e.g. a pre-seeded keystore/import) — has ALREADY set.
 *
 * Separated from [OpenAPSBoostV5Plugin]'s preference I/O so the invariant Tim cares about is
 * unit-testable: presetting ONE V6 knob must NOT block the others — the preset value is kept and
 * every other unset knob is still configured.
 */
internal object BoostV5AutoConfigApply {

    /** The double-valued V5 knobs auto-config manages, paired with their suggested value (stable order). */
    fun managedDoubleKnobs(s: BoostV5AutoConfig.V5Suggestion): List<Pair<DoubleKey, Double>> = listOf(
        DoubleKey.ApsBoostV5Aggression to s.aggression,
        DoubleKey.ApsBoostV5HypoCaution to s.hypoCaution,
        DoubleKey.ApsBoostV5ConfirmedCapU to s.confirmedCapU,
        DoubleKey.ApsBoostV5CommittedCapU to s.committedCapU,
        DoubleKey.ApsBoostCumulativeSmbCap60Min to s.cumulativeSmbCap60MinU,
        DoubleKey.ApsBoostMaxIob to s.maxIobU,
        DoubleKey.ApsBoostBolus to s.bolusCapU
    )

    /**
     * Write each managed knob ONLY if it isn't already set ([isSet] == false); a preset/user value is
     * kept (skipped). Per-knob and independent — presetting one never blocks the others. Returns the
     * knobs actually written (for the "configured N setting(s)" notification). Pure: [isSet] / [put]
     * inject the preference I/O so the preset-skip behaviour can be tested without the plugin/DI.
     */
    fun applyAutoConfig(
        knobs: List<Pair<DoubleKey, Double>>,
        isSet: (DoubleKey) -> Boolean,
        put: (DoubleKey, Double) -> Unit
    ): List<Pair<DoubleKey, Double>> {
        val applied = mutableListOf<Pair<DoubleKey, Double>>()
        for ((key, value) in knobs) {
            if (!isSet(key)) {
                put(key, value)
                applied += key to value
            }
        }
        return applied
    }
}
