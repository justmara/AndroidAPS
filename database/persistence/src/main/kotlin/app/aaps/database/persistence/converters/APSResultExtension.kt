package app.aaps.database.persistence.converters

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.aps.RT
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.Json
import javax.inject.Provider

// Tolerant reader for persisted APS results. The serialized RT/profile/glucose-status
// schema differs between AAPS variants (e.g. AutoISF/Boost add fields like `acceIsf`),
// so reading rows written by a different app version must ignore unknown keys instead
// of throwing JsonDecodingException and crashing on app up/downgrade.
private val jsonReader = Json { ignoreUnknownKeys = true }

// Decode an optional persisted sub-object, returning null instead of crashing the whole app
// start when a row was written by a different app version (e.g. a removed/renamed required field
// raises MissingFieldException, which ignoreUnknownKeys does not cover). These fields are display/
// IOB-cache data; a null degrades gracefully where a throw would crash on app up/downgrade.
private inline fun <reified T> decodeOrNull(json: String?): T? =
    json?.let { try { jsonReader.decodeFromString<T>(it) } catch (_: Exception) { null } }

fun app.aaps.database.entities.APSResult.fromDb(apsResultProvider: Provider<APSResult>): APSResult =
    when (algorithm) {
        app.aaps.database.entities.APSResult.Algorithm.AMA,
        app.aaps.database.entities.APSResult.Algorithm.SMB,
        app.aaps.database.entities.APSResult.Algorithm.EN       ->
            apsResultProvider.get().with(jsonReader.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = decodeOrNull(this.glucoseStatusJson)
                result.currentTemp = decodeOrNull(this.currentTempJson)
                result.iobData = decodeOrNull(this.iobDataJson)
                result.oapsProfile = decodeOrNull(this.profileJson)
                result.mealData = decodeOrNull(this.mealDataJson)
                result.autosensResult = decodeOrNull(this.autosensDataJson)
            }

        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF ->
            apsResultProvider.get().with(jsonReader.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = decodeOrNull(this.glucoseStatusJson)
                result.currentTemp = decodeOrNull(this.currentTempJson)
                result.iobData = decodeOrNull(this.iobDataJson)
                result.oapsProfileAutoIsf = decodeOrNull(this.profileJson)
                result.mealData = decodeOrNull(this.mealDataJson)
                result.autosensResult = decodeOrNull(this.autosensDataJson)
            }

        app.aaps.database.entities.APSResult.Algorithm.BOOST    ->
            apsResultProvider.get().with(jsonReader.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = decodeOrNull(this.glucoseStatusJson)
                result.currentTemp = decodeOrNull(this.currentTempJson)
                result.iobData = decodeOrNull(this.iobDataJson)
                result.oapsProfileBoost = decodeOrNull(this.profileJson)
                result.mealData = decodeOrNull(this.mealDataJson)
                result.autosensResult = decodeOrNull(this.autosensDataJson)
            }

        else                                                    -> error("Unsupported")
    }

@OptIn(ExperimentalSerializationApi::class)
fun APSResult.toDb(): app.aaps.database.entities.APSResult =
    when (algorithm) {
        APSResult.Algorithm.AMA,
        APSResult.Algorithm.EN,
        APSResult.Algorithm.SMB      ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { Json.encodeToString(GlucoseStatusSMB.serializer(), it as GlucoseStatusSMB) },
                currentTempJson = this.currentTemp?.let { Json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { Json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfile?.let { Json.encodeToString(OapsProfile.serializer(), it) },
                mealDataJson = this.mealData?.let { Json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { Json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = Json.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        APSResult.Algorithm.AUTO_ISF ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { Json.encodeToString(GlucoseStatusAutoIsf.serializer(), it as GlucoseStatusAutoIsf) },
                currentTempJson = this.currentTemp?.let { Json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { Json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfileAutoIsf?.let { Json.encodeToString(OapsProfileAutoIsf.serializer(), it) },
                mealDataJson = this.mealData?.let { Json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { Json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = Json.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        APSResult.Algorithm.BOOST   ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { Json.encodeToString(GlucoseStatusSMB.serializer(), it as GlucoseStatusSMB) },
                currentTempJson = this.currentTemp?.let { Json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { Json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfileBoost?.let { Json.encodeToString(OapsProfileBoost.serializer(), it) },
                mealDataJson = this.mealData?.let { Json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { Json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = Json.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        else                         -> error("Unsupported")
    }

fun app.aaps.database.entities.APSResult.Algorithm.fromDb(): APSResult.Algorithm =
    when (this) {
        app.aaps.database.entities.APSResult.Algorithm.AMA      -> APSResult.Algorithm.AMA
        app.aaps.database.entities.APSResult.Algorithm.SMB      -> APSResult.Algorithm.SMB
        app.aaps.database.entities.APSResult.Algorithm.EN       -> APSResult.Algorithm.EN
        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF -> APSResult.Algorithm.AUTO_ISF
        app.aaps.database.entities.APSResult.Algorithm.BOOST    -> APSResult.Algorithm.BOOST
        else                                                    -> error("Unsupported")
    }

fun APSResult.Algorithm.toDb(): app.aaps.database.entities.APSResult.Algorithm =
    when (this) {
        APSResult.Algorithm.AMA      -> app.aaps.database.entities.APSResult.Algorithm.AMA
        APSResult.Algorithm.SMB      -> app.aaps.database.entities.APSResult.Algorithm.SMB
        APSResult.Algorithm.EN       -> app.aaps.database.entities.APSResult.Algorithm.EN
        APSResult.Algorithm.AUTO_ISF -> app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF
        APSResult.Algorithm.BOOST    -> app.aaps.database.entities.APSResult.Algorithm.BOOST
        else                         -> error("Unsupported")
    }