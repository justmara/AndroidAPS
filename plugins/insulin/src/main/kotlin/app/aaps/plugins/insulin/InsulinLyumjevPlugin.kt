package app.aaps.plugins.insulin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.pow

@Singleton
class InsulinLyumjevPlugin @Inject constructor(
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction,
    private val preferences: Preferences,
) : InsulinOrefBasePlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_LYUMJEV
    override val friendlyName get(): String = rh.gs(R.string.lyumjev)

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.LyumjevU200, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.LyumjevU200, preferences)
    }

    override fun commentStandardText(): String = rh.gs(R.string.lyumjev)

    override val peak = 45

    init {
        pluginDescription
            .pluginIcon(R.drawable.ic_insulin)
            .pluginName(R.string.lyumjev)
            .preferencesId(PluginDescription.PREFERENCE_SCREEN)
            .description(R.string.description_insulin_lyumjev)
    }

    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            if (t >= 8 * 60) return super.iobCalcForTreatment(bolus, time, dia)

            // MP Model for estimation of PD-based peak time: (a0 + a1*X)/(1+b1*X), where X = bolus size
            val a0 = 61.33 //MP Units = min
            val a1 = 12.27
            val b1 = 0.05185
            val tp: Double
            if (preferences.get(BooleanKey.LyumjevU200)) { //MP ID = 6 for Lyumjev U200
                tp = (a0 + a1 * 2 * bolus.amount)/(1 + b1 * 2 * bolus.amount)
            } else {
                tp = (a0 + a1 * bolus.amount) / (1 + b1 * bolus.amount)
            }

            // MP The peak time in the model is defined as half of
            // the square root of this variable - thus the tp entered into the model must be transformed first
            val tp_model = tp.pow(2.0) * 2

            //MP Calculate remaining IOB of this bolus (PD based approach)
            /**
             *
             * MP - UAM Tsunami PD model U100 vs U200
             *
             * InsActinvity calculation below: The same formula is used for both, U100 and U200
             * insulin as the concentration effect is already included in the peak time calculation.
             * If peak time is kept constant and only the dose is doubled, the general shape of the
             * curve doesn't change and hence the equation does not need adjusting. Unless a global
             * U200 mode is introduced where ISF between U100 and U200 has the same value (i.e.: When
             * ISF doubling and basal halving is done in AAPS' calculations and not by the user), the
             * equation doesn't need any changing.
             * The user must keep in mind that the displayed IOB is only half of the actual IOB.
             *
             */
            result.activityContrib = (2 * bolus.amount / tp_model) * t * exp(-t.pow(2.0) / tp_model)
            // MP New IOB formula - integrated version of the above activity curve
            val lowerLimit = t // MP lower integration limit, in min
            val upperLimit = 8.0 * 60 // MP upper integration limit, in min
            result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0)/tp_model) - exp(-upperLimit.pow(2.0)/tp_model))
        }
        return result
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "insulin_lyumjev_settings"
            title = rh.gs(R.string.lyumjev)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.LyumjevU200, title = R.string.insulin_u200))
        }
    }
}