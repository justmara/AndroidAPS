package app.aaps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.versionChecker.VersionCheckerUtils
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.locale.LocaleHelper
import app.aaps.core.utils.JsonHelper
import app.aaps.database.persistence.CompatDBHelper
import app.aaps.di.AppComponent
import app.aaps.di.DaggerAppComponent
import app.aaps.implementation.lifecycle.ProcessLifecycleListener
import app.aaps.implementation.plugin.PluginStore
import app.aaps.implementation.receivers.NetworkChangeReceiver
import app.aaps.plugins.aps.openAPSAutoISF.OpenAPSAutoISFPlugin
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin
import app.aaps.plugins.aps.openAPSBoost.StepService
import app.aaps.plugins.aps.openAPSBoostV2.OpenAPSBoostV2Plugin
import app.aaps.plugins.aps.openAPSSMB.PhoneMovementDetector
import app.aaps.plugins.aps.openAPSSMB.StepService as AutoIsfStepService
import app.aaps.plugins.configuration.keys.ConfigurationBooleanComposedKey
import app.aaps.plugins.constraints.objectives.keys.ObjectivesLongComposedKey
import app.aaps.plugins.main.general.themes.ThemeSwitcherPlugin
import app.aaps.plugins.main.profile.keys.ProfileComposedBooleanKey
import app.aaps.plugins.main.profile.keys.ProfileComposedDoubleKey
import app.aaps.plugins.main.profile.keys.ProfileComposedStringKey
import app.aaps.receivers.BTReceiver
import app.aaps.receivers.ChargingStateReceiver
import app.aaps.receivers.KeepAliveWorker
import app.aaps.receivers.TimeDateOrTZChangeReceiver
import app.aaps.ui.activityMonitor.ActivityMonitor
import app.aaps.plugins.main.general.overview.boost.widget.BoostWidget
import app.aaps.ui.widget.Widget
import app.aaps.utils.configureLeakCanary
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.remoteConfig
import dagger.android.AndroidInjector
import dagger.android.DaggerApplication
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import rxdogtag2.RxDogTag
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

class MainApp : DaggerApplication() {

    private val disposable = CompositeDisposable()

    @Inject lateinit var pluginStore: PluginStore
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activityMonitor: ActivityMonitor
    @Inject lateinit var versionCheckersUtils: VersionCheckerUtils
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var config: Config
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var plugins: List<@JvmSuppressWildcards PluginBase>
    @Inject lateinit var compatDBHelper: CompatDBHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var processLifecycleListener: Provider<ProcessLifecycleListener>
    @Inject lateinit var themeSwitcherPlugin: ThemeSwitcherPlugin
    @Inject lateinit var localAlertUtils: LocalAlertUtils
    @Inject lateinit var rh: Provider<ResourceHelper>
    @Inject lateinit var loggerUtils: LoggerUtils
    @Inject lateinit var loop: Loop
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    lateinit var appComponent: AppComponent

    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var refreshWidget: Runnable
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()

        // Here should be everything injected
        loggerUtils.initialize(this)
        aapsLogger.debug("onCreate")
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleListener.get())
        // Configure LeakCanary with Firebase reporting
        // Memory leaks will be uploaded to Firebase Crashlytics via FabricPrivacy.logException
        configureLeakCanary(
            isEnabled = !config.disableLeakCanary(),
            fabricPrivacy = fabricPrivacy
        )

        // Do necessary migrations
        doMigrations()

        // Register and initialize plugins
        pluginStore.plugins = plugins
        configBuilder.initialize()

        // Do initializations in another thread
        scope.launch { doInit() }
    }

    private fun doInit() {
        aapsLogger.debug("doInit")
        RxDogTag.install()
        setRxErrorHandler()
        LocaleHelper.update(this@MainApp)

        var gitRemote: String? = config.REMOTE
        var commitHash: String? = BuildConfig.HEAD
        if (gitRemote?.contains("NoGitSystemAvailable") == true) {
            gitRemote = null
            commitHash = null
        }
        disposable += compatDBHelper.dbChangeDisposable()
        registerActivityLifecycleCallbacks(activityMonitor)
        runOnUiThread { themeSwitcherPlugin.setThemeMode() }
        aapsLogger.debug("Version: " + config.VERSION_NAME)
        aapsLogger.debug("BuildVersion: " + config.BUILD_VERSION)
        aapsLogger.debug("Remote: " + config.REMOTE)
        aapsLogger.debug("Phone: " + Build.MANUFACTURER + " " + Build.MODEL)
        registerLocalBroadcastReceiver()
        setupRemoteConfig()

        // trigger here to see the new version on app start after an update
        handler.postDelayed({ versionCheckersUtils.triggerCheckVersion() }, 30000)

        // delayed actions to make rh context updated for translations
        handler.postDelayed(
            {
                // log version
                disposable += persistenceLayer.insertVersionChangeIfChanged(config.VERSION_NAME, BuildConfig.VERSION_CODE, gitRemote, commitHash).subscribe()
                // log app start
                if (preferences.get(BooleanKey.NsClientLogAppStart))
                    disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE(
                            timestamp = dateUtil.now(),
                            type = TE.Type.NOTE,
                            note = rh.get().gs(app.aaps.core.ui.R.string.androidaps_start) + " - " + Build.MANUFACTURER + " " + Build.MODEL,
                            glucoseUnit = GlucoseUnit.MGDL
                        ),
                        action = Action.START_AAPS,
                        source = Sources.Aaps, note = "", listValues = listOf()
                    ).subscribe()
            }, 10000
        )
        KeepAliveWorker.schedule(this@MainApp)
        localAlertUtils.shortenSnoozeInterval()
        localAlertUtils.preSnoozeAlarms()

        //  schedule widget update
        refreshWidget = Runnable {
            handler.postDelayed(refreshWidget, 60000)
            Widget.updateWidget(this@MainApp, "ScheduleEveryMin")
            BoostWidget.updateWidget(this@MainApp, "ScheduleEveryMin")
        }
        handler.postDelayed(refreshWidget, 60000)
        config.appInitialized = true

        // Record app start (used by AutoISF time-since-start) and register activity sensors
        // for the active APS plugin only — see registerActivitySensors().
        preferences.put(LongKey.AppStart, dateUtil.now())
        try {
            registerActivitySensors()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Failed to register activity sensors", e)
        }

        aapsLogger.debug("doInit end")
    }

    /**
     * Registers activity sensors (hardware step counter + phone-movement detection) used by the
     * activity-aware APS plugins. Only the sensors needed by the *currently enabled* APS plugin are
     * registered — AAPS runs exactly one APS plugin, so an SMB/AMA user pays no sensor cost.
     *
     * Battery notes:
     *  - Step counter is registered with a 2-min FIFO batch (maxReportLatency) so the CPU can stay
     *    asleep between deliveries; Boost/AutoISF only need 5-min resolution.
     *  - Movement detection uses the accelerometer sampled at 1 Hz with a 2-min FIFO batch (CPU
     *    sleeps between flushes; no ACTIVITY_RECOGNITION permission needed), falling back to the
     *    hardware significant-motion trigger on devices without an accelerometer — see
     *    [PhoneMovementDetector].
     */
    private fun registerActivitySensors() {
        val activeAps = plugins.firstOrNull { it.getType() == PluginType.APS && it.isEnabled() }
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return

        // Step counter — consumed by Boost (V1/V2) and AutoISF. Needs ACTIVITY_RECOGNITION on Q+.
        val usesStepCounter = activeAps is OpenAPSBoostPlugin || activeAps is OpenAPSBoostV2Plugin || activeAps is OpenAPSAutoISFPlugin
        if (usesStepCounter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) {
                aapsLogger.warn(LTag.APS, "ACTIVITY_RECOGNITION not granted — step counter disabled until granted and app restarted")
            } else {
                val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (stepSensor == null) {
                    aapsLogger.warn(LTag.APS, "Step counter sensor not available on this device")
                } else {
                    val maxReportLatencyUs = 2 * 60 * 1000 * 1000 // 2 min FIFO batch, well below the 5-min bucket
                    val listener = if (activeAps is OpenAPSAutoISFPlugin) AutoIsfStepService else StepService
                    sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, maxReportLatencyUs)
                    aapsLogger.debug(LTag.APS, "Step counter registered for ${activeAps?.javaClass?.simpleName}")
                }
            }
        }

        // Phone-movement detection — consumed by AutoISF only.
        if (activeAps is OpenAPSAutoISFPlugin) {
            if (PhoneMovementDetector.register(sensorManager)) aapsLogger.debug(LTag.APS, "AutoISF movement detection registered")
            else aapsLogger.warn(LTag.APS, "No movement sensor available for AutoISF")
        }
    }

    private fun setRxErrorHandler() {
        RxJavaPlugins.setErrorHandler { t: Throwable ->
            var e = t
            if (e is UndeliverableException) {
                e = e.cause!!
            }
            if (e is IOException) {
                // fine, irrelevant network problem or API that throws on cancellation
                return@setErrorHandler
            }
            if (e is InterruptedException) {
                // fine, some blocking code was interrupted by a dispose call
                return@setErrorHandler
            }
            if (e is NullPointerException || e is IllegalArgumentException) {
                // that's likely a bug in the application
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), e)
                return@setErrorHandler
            }
            if (e is IllegalStateException) {
                // that's a bug in RxJava or in a custom operator
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), e)
                return@setErrorHandler
            }
            aapsLogger.warn(LTag.CORE, "Undeliverable exception received, not sure what to do", e.localizedMessage)
        }
    }

    private fun doMigrations() {
        // set values for different builds
        // 3.3
        if (preferences.get(IntKey.OverviewEatingSoonDuration) == 0) preferences.remove(IntKey.OverviewEatingSoonDuration)
        if (preferences.get(UnitDoubleKey.OverviewEatingSoonTarget) == 0.0) preferences.remove(UnitDoubleKey.OverviewEatingSoonTarget)
        if (preferences.get(IntKey.OverviewActivityDuration) == 0) preferences.remove(IntKey.OverviewActivityDuration)
        if (preferences.get(UnitDoubleKey.OverviewActivityTarget) == 0.0) preferences.remove(UnitDoubleKey.OverviewActivityTarget)
        if (preferences.get(IntKey.OverviewHypoDuration) == 0) preferences.remove(IntKey.OverviewHypoDuration)
        if (preferences.get(UnitDoubleKey.OverviewHypoTarget) == 0.0) preferences.remove(UnitDoubleKey.OverviewHypoTarget)
        if (preferences.get(UnitDoubleKey.OverviewLowMark) == 0.0) preferences.remove(UnitDoubleKey.OverviewLowMark)
        if (preferences.get(UnitDoubleKey.OverviewHighMark) == 0.0) preferences.remove(UnitDoubleKey.OverviewHighMark)
        if (preferences.getIfExists(BooleanKey.GeneralSimpleMode) == null)
            preferences.put(BooleanKey.GeneralSimpleMode, !preferences.get(BooleanKey.GeneralSetupWizardProcessed))
        // Migrate from OpenAPSSMBDynamicISFPlugin
        if (sp.getBoolean("ConfigBuilder_APS_OpenAPSSMBDynamicISFPlugin_Enabled", false)) {
            sp.remove("ConfigBuilder_APS_OpenAPSSMBDynamicISFPlugin_Enabled")
            sp.remove("ConfigBuilder_APS_OpenAPSSMBDynamicISFPlugin_Visible")
            sp.putBoolean("ConfigBuilder_APS_OpenAPSSMB_Enabled", true)
            preferences.put(BooleanKey.DynIsfEnabled, true)
        }
        // DynISF settings migration (2026-07-24): old keys → unified DynIsf* keys.
        // Priority: Boost old key → shared old key → new default.
        try {
            fun migrateBool(oldKey: String, newKey: BooleanKey) {
                @Suppress("UNCHECKED_CAST")
                preferences.getIfExists(oldKey)?.let { preferences.put(newKey, it as Boolean) }
            }

            fun migrateInt(oldKey: String, newKey: IntKey) {
                preferences.getIfExists(oldKey)?.let { v ->
                    preferences.put(newKey, (v as Int).coerceIn(newKey.min, newKey.max))
                }
            }

            fun migrateUnitDouble(oldKey: String, newKey: UnitDoubleKey) {
                preferences.getIfExists(oldKey)?.let { v ->
                    val d = (v as Number).toDouble()
                    preferences.put(newKey, d.coerceIn(newKey.minMgdl.toDouble(), newKey.maxMgdl.toDouble()))
                }
            }

            // Phase 1: Boost old keys (take priority)
            migrateBool("boost_use_tdd", BooleanKey.DynIsfUseTdd)
            migrateBool("boost_adjust_sensitivity", BooleanKey.DynIsfAdjustSensitivity)
            migrateBool("boost_autosens_when_no_tdd", BooleanKey.DynIsfAutosensWhenNoTdd)
            migrateInt("boost_DynISFAdjust", IntKey.DynIsfAdjustmentFactor)
            migrateUnitDouble("boost_dynisf_bg_cap", UnitDoubleKey.DynIsfBgCap)
            migrateUnitDouble("boost_dynisf_normal_target", UnitDoubleKey.DynIsfNormalTarget)

            // Phase 2: shared old keys (fallback — skip if the DynIsf key already has a value)
            if (preferences.getIfExists(BooleanKey.DynIsfEnabled) == null)
                migrateBool("use_dynamic_sensitivity", BooleanKey.DynIsfEnabled)
            if (preferences.getIfExists(IntKey.DynIsfAdjustmentFactor) == null)
                migrateInt("DynISFAdjust", IntKey.DynIsfAdjustmentFactor)
            if (preferences.getIfExists(UnitDoubleKey.DynIsfBgCap) == null)
                migrateUnitDouble("dynisf_bg_cap", UnitDoubleKey.DynIsfBgCap)

            // DynIsfVelocity was stored as IntKey "DynISFVelocity" (old SMB) or as
            // DoubleKey/IntKey "boost_dynisf_velocity" (old Boost, already migrated above).
            if (preferences.getIfExists(IntKey.DynIsfVelocity) == null) {
                preferences.getIfExists("DynISFVelocity")?.let { v ->
                    preferences.put(IntKey.DynIsfVelocity, (v as Int).coerceIn(IntKey.DynIsfVelocity.min, IntKey.DynIsfVelocity.max))
                }
            }

            // Clean up old keys
            listOf(
                "use_dynamic_sensitivity",
                "boost_use_tdd", "boost_adjust_sensitivity", "boost_autosens_when_no_tdd",
                "boost_DynISFAdjust", "boost_dynisf_velocity",
                "boost_dynisf_bg_cap", "boost_dynisf_normal_target",
                "DynISFAdjust", "DynISFVelocity", "dynisf_bg_cap"
            ).forEach { sp.remove(it) }
        } catch (_: Exception) { /* ignore */ }
        // Clear SmsOtpPassword if wrongly replaced
        if (preferences.get(StringKey.SmsOtpPassword).length > 10) preferences.put(StringKey.SmsOtpPassword, "")

        val keys: Map<String, *> = sp.getAll()
        // Migrate ActivityMonitor
        for ((key, value) in keys) {
            if (key.startsWith("Monitor") && key.endsWith("total")) {
                val activity = key.split("_")[1]
                if (value is String)
                    preferences.put(LongComposedKey.ActivityMonitorTotal, activity, value = SafeParse.stringToLong(value))
                else
                    preferences.put(LongComposedKey.ActivityMonitorTotal, activity, value = value as Long)
                sp.remove(key)
            }
            if (key.startsWith("Monitor") && key.endsWith("resumed")) {
                val activity = key.split("_")[1]
                if (value is String)
                    preferences.put(LongComposedKey.ActivityMonitorResumed, activity, value = SafeParse.stringToLong(value))
                else
                    preferences.put(LongComposedKey.ActivityMonitorResumed, activity, value = value as Long)
                sp.remove(key)
            }
            if (key.startsWith("Monitor") && key.endsWith("start")) {
                val activity = key.split("_")[1]
                if (value is String)
                    preferences.put(LongComposedKey.ActivityMonitorStart, activity, value = SafeParse.stringToLong(value))
                else
                    preferences.put(LongComposedKey.ActivityMonitorStart, activity, value = value as Long)
                sp.remove(key)
            }
        }
        // Migrate Objectives
        for ((key, value) in keys) {
            if (key.startsWith("Objectives_") && key.endsWith("_started")) {
                val objective = key.split("_")[1]
                if (value is String)
                    preferences.put(ObjectivesLongComposedKey.Started, objective, value = SafeParse.stringToLong(value))
                else
                    preferences.put(ObjectivesLongComposedKey.Started, objective, value = value as Long)
                sp.remove(key)
            }
            if (key.startsWith("Objectives_") && key.endsWith("_accomplished")) {
                val objective = key.split("_")[1]
                if (value is String)
                    preferences.put(ObjectivesLongComposedKey.Accomplished, objective, value = SafeParse.stringToLong(value))
                else
                    preferences.put(ObjectivesLongComposedKey.Accomplished, objective, value = value as Long)
                sp.remove(key)
            }
        }
        // Migrate ConfigBuilder
        for ((key, value) in keys) {
            if (key.startsWith("ConfigBuilder_") && key.endsWith("_Enabled")) {
                val plugin = key.split("_")[1] + "_" + key.split("_")[2]
                preferences.put(ConfigurationBooleanComposedKey.ConfigBuilderEnabled, plugin, value = value as Boolean)
                sp.remove(key)
            }
            if (key.startsWith("ConfigBuilder_") && key.endsWith("_Visible")) {
                val plugin = key.split("_")[1] + "_" + key.split("_")[2]
                preferences.put(ConfigurationBooleanComposedKey.ConfigBuilderVisible, plugin, value = value as Boolean)
                sp.remove(key)
            }
        }
        // Migrate Profile
        for ((key, value) in keys) {
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_mgdl")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedBooleanKey.LocalProfileNumberedMgdl, SafeParse.stringToInt(number), value = value as Boolean)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_isf")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedIsf, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_ic")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedIc, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_ic")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedIc, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_basal")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedBasal, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_targetlow")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedTargetLow, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_targethigh")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedTargetHigh, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_name")) {
                val number = key.split("_")[1]
                preferences.put(ProfileComposedStringKey.LocalProfileNumberedName, SafeParse.stringToInt(number), value = value as String)
                sp.remove(key)
            }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_dia")) {
                val number = key.split("_")[1]
                if (value is String)
                    preferences.put(ProfileComposedDoubleKey.LocalProfileNumberedDia, SafeParse.stringToInt(number), value = SafeParse.stringToDouble(value))
                else if (value is Float)
                    preferences.put(ProfileComposedDoubleKey.LocalProfileNumberedDia, SafeParse.stringToInt(number), value = value.toDouble())
                else
                    preferences.put(ProfileComposedDoubleKey.LocalProfileNumberedDia, SafeParse.stringToInt(number), value = value as Double)
                sp.remove(key)
            }
        }

        // Migrate Tidepool from username/password to OAuth2
        if (sp.contains("tidepool_username") || sp.contains("tidepool_password")) {
            sp.remove("tidepool_username")
            sp.remove("tidepool_password")
            sp.remove("tidepool_test_login")
            // Clear OAuth2 state to force re-authentication
            sp.remove("tidepool_auth_state")
            sp.remove("tidepool_service_configuration")
            sp.remove("tidepool_subscription_id")
        }

        // Migrate loop mode
        if (config.APS && sp.contains("aps_mode")) {
            val mode = when (sp.getString("aps_mode", "CLOSED")) {
                "OPEN"   -> RM.Mode.OPEN_LOOP
                "CLOSED" -> RM.Mode.CLOSED_LOOP
                "LGS"    -> RM.Mode.CLOSED_LOOP_LGS
                else     -> RM.Mode.CLOSED_LOOP
            }
            @SuppressLint("CheckResult")
            persistenceLayer.insertOrUpdateRunningMode(
                runningMode = RM(
                    timestamp = dateUtil.now(),
                    mode = mode,
                    autoForced = false,
                    duration = 0
                ),
                action = Action.CLOSED_LOOP_MODE,
                source = Sources.Aaps,
                listValues = listOf(ValueWithUnit.SimpleString("Migration"))
            ).blockingGet()
            sp.remove("aps_mode")
        }
    }

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        appComponent = DaggerAppComponent
            .builder()
            .application(this)
            .build()
        return appComponent
    }

    private val timeDateReceiver = TimeDateOrTZChangeReceiver()
    private val networkReceiver = NetworkChangeReceiver()
    private val chargingReceiver = ChargingStateReceiver()
    private val btReceiver = BTReceiver()

    private fun registerLocalBroadcastReceiver() {
        var filter = IntentFilter()
        filter.addAction(Intent.ACTION_TIME_CHANGED)
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        registerReceiver(timeDateReceiver, filter)
        filter = IntentFilter()
        @Suppress("DEPRECATION")
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        registerReceiver(networkReceiver, filter)
        filter = IntentFilter()
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(chargingReceiver, filter)
        filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        registerReceiver(btReceiver, filter)
    }

    private fun unregisterReceivers() {
        super.onTerminate()
        unregisterReceiver(timeDateReceiver)
        unregisterReceiver(networkReceiver)
        unregisterReceiver(chargingReceiver)
        unregisterReceiver(btReceiver)
    }

    private fun setupRemoteConfig() {
        FirebaseApp.initializeApp(this)
        Firebase.remoteConfig.also { firebaseRemoteConfig ->

            firebaseRemoteConfig.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings
                    .Builder()
                    .setMinimumFetchIntervalInSeconds(3600)
                    .build()
            )
            firebaseRemoteConfig
                .fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        aapsLogger.debug("RemoteConfig received successfully")
                        @Suppress("UNCHECKED_CAST")
                        (versionCheckersUtils::class.declaredMemberProperties.find { it.name == "definition" } as KMutableProperty<Any>?)
                            ?.let {
                                val merged = JsonHelper.merge(it.getter.call(versionCheckersUtils) as JSONObject, JSONObject(firebaseRemoteConfig.getString("defs")))
                                it.setter.call(versionCheckersUtils, merged)
                            }
                    } else aapsLogger.error("RemoteConfig fetch failed")
                }
        }
    }

    override fun onTerminate() {
        aapsLogger.debug(LTag.CORE, "onTerminate")
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
        unregisterReceivers()
        unregisterActivityLifecycleCallbacks(activityMonitor)
        uiInteraction.stopAlarm("onTerminate")
        super.onTerminate()
    }
}
