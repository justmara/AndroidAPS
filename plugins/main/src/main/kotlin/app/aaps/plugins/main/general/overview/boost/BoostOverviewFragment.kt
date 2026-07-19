package app.aaps.plugins.main.general.overview.boost

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView

import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.WarnColors
import app.aaps.core.data.model.TE
import app.aaps.core.keys.IntKey
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.convertedToPercent
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.elements.SingleClickButton
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.BoostOverviewFragmentBinding
import app.aaps.core.graph.data.GraphViewWithCleanup
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import app.aaps.plugins.main.skins.SkinProvider
import com.jjoe64.graphview.GraphView
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Provider

/**
 * BoostOverviewFragment — Redesigned home screen for the Boost APS algorithm.
 *
 * Layout: BG bobble with zone-coloured ring, detail panels (IOB/Boost/DynISF/Profile),
 * algorithm chips, primary + secondary BG graphs, IOB decay chart, and the standard
 * AAPS action buttons including automation user action buttons.
 */
class BoostOverviewFragment : DaggerFragment(), View.OnClickListener, View.OnLongClickListener {

    // --- Injection (mirrors OverviewFragment dependencies) ---

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var automation: Automation
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var graphDataProvider: Provider<GraphData>
    @Inject lateinit var boostHelper: BoostOverviewHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var dexcomBoyda: DexcomBoyda
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var warnColors: WarnColors
    @Inject lateinit var skinProvider: SkinProvider
    @Inject lateinit var bgQualityCheck: BgQualityCheck

    // --- State ---

    private val disposable = CompositeDisposable()
    private var handler = Handler(HandlerThread("BoostOverviewHandler").also { it.start() }.looper)
    private lateinit var refreshLoop: Runnable
    private var _binding: BoostOverviewFragmentBinding? = null
    private val binding get() = _binding!!

    // Secondary graphs (identical to OverviewFragment pattern) — built dynamically
    // from the chart-menu checkbox columns (overviewMenus.setting).

    private val secondaryGraphs = ArrayList<GraphView>()
    private val secondaryGraphsLabel = ArrayList<TextView>()

    private var axisWidth: Int = 0

    // Track user action buttons for watch sync
    private var lastUserAction = ""

    // Cached for click handlers — updated each refresh cycle, avoids recalculating on tap
    @Volatile private var lastBoostStatus: BoostOverviewHelper.BoostStatus = BoostOverviewHelper.BoostStatus()

    // Coalesced GUI update — matches standard OverviewFragment pattern
    private var scheduledGuiUpdate: Runnable? = null

    // --- Lifecycle ---

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        BoostOverviewFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Resolve theme colours for custom views
        binding.bgBobble.resolveThemeColors(rh)

        // Graph axis width (density-dependent, same as OverviewFragment)
        axisWidth = when {
            resources.displayMetrics.densityDpi <= 120 -> 3
            resources.displayMetrics.densityDpi <= 160 -> 10
            resources.displayMetrics.densityDpi <= 320 -> 35
            resources.displayMetrics.densityDpi <= 420 -> 50
            resources.displayMetrics.densityDpi <= 560 -> 70
            else                                       -> 80
        }
        // Subtle grid lines (20% opacity of theme grid colour)
        val gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
        val subtleGrid = android.graphics.Color.argb(
            (android.graphics.Color.alpha(gridColor) * 0.2f).toInt(),
            android.graphics.Color.red(gridColor),
            android.graphics.Color.green(gridColor),
            android.graphics.Color.blue(gridColor))

        // BG graph setup — height set in XML (260dp), don't override with skin default
        binding.bgGraph.gridLabelRenderer?.gridColor = subtleGrid
        binding.bgGraph.gridLabelRenderer?.reloadStyles()
        binding.bgGraph.gridLabelRenderer?.labelVerticalWidth = axisWidth

        // Secondary graphs are built lazily by prepareGraphsIfNeeded() once the
        // chart-menu setting count is known (see updateGraph).
        prepareGraphsIfNeeded(overviewMenus.setting.size)

        binding.notifications.setHasFixedSize(false)
        binding.notifications.layoutManager = LinearLayoutManager(view.context)

        // Long-press graph to cycle display range (6h -> 12h -> 18h -> 24h -> 6h)
        binding.bgGraph.setOnLongClickListener {
            overviewData.rangeToDisplay += 6
            overviewData.rangeToDisplay = if (overviewData.rangeToDisplay > 24) 6 else overviewData.rangeToDisplay
            preferences.put(IntNonKey.RangeToDisplay, overviewData.rangeToDisplay)
            rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
            false
        }
        // Tap graph to open Treatments screen
        binding.bgGraph.setOnClickListener {
            context?.let { ctx ->
                startActivity(android.content.Intent(ctx, uiInteraction.treatmentsActivity))
            }
        }
        overviewMenus.setupChartMenu(binding.chartMenuButton, binding.scaleButton)
        binding.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)
        binding.chartMenuButton.visibility = preferences.simpleMode.not().toVisibility()

        // Detail panel click listeners
        binding.panelBoost.setOnClickListener(this)
        binding.panelDynisf.setOnClickListener(this)
        binding.panelProfile.setOnClickListener(this)
        binding.panelProfile.setOnLongClickListener(this)
        binding.connectionIcon.setOnClickListener(this)
        binding.connectionIcon.setOnLongClickListener(this)
        binding.panelIob.setOnClickListener(this)

        // Second row
        binding.panelTdd.setOnClickListener(this)
        binding.panelCob.setOnClickListener(this)
        binding.panelTarget.setOnClickListener(this)
        binding.panelActivity.setOnClickListener(this)
        // AutoISF row — factor panel opens the APS "Result" block popup
        binding.panelAutoisfFactors.setOnClickListener(this)
        // Boost V6 decision strip — tap to show the full V6 decision breakdown
        binding.v5Strip.setOnClickListener(this)

        // Pump status bar — tap to show bolus progress dialog
        binding.pumpStatusLayout.setOnClickListener(this)

        // Standard AAPS button click listeners (from overview_buttons_layout include)
        binding.buttonsLayout.acceptTempButton.setOnClickListener(this)
        binding.buttonsLayout.treatmentButton.setOnClickListener(this)
        binding.buttonsLayout.wizardButton.setOnClickListener(this)
        binding.buttonsLayout.calibrationButton.setOnClickListener(this)
        binding.buttonsLayout.cgmButton.setOnClickListener(this)
        binding.buttonsLayout.insulinButton.setOnClickListener(this)
        binding.buttonsLayout.carbsButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnLongClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        // --- Progress bar (drives the "completion bar") ---
        // throttleLatest keeps the bar responsive while coalescing the burst of progress
        // events a calculation emits into at most one UI update per 100ms (latest wins).
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .throttleLatest(100L, TimeUnit.MILLISECONDS, true)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateCalcProgress() }, fabricPrivacy::logException)

        // --- Granular event-driven updates (debounced, lightweight) ---
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobAndBoost() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGraph() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                // Don't invalidate the boost-status cache here: updateBg() doesn't use it, and the
                // boost status only changes on a new APS run (handled by EventRefreshOverview below).
                // The 30s TTL covers any staleness, so this avoids recomputing the 7-day TDD on every BG.
                updateBg()
            }, fabricPrivacy::logException)

        // --- Full refresh events → coalesced through scheduleUpdateGUI ---
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                boostHelper.invalidate()
                if (it.now) refreshAll() else scheduleUpdateGUI()
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                context?.let { ctx -> overviewData.pumpStatus = it.getStatus(ctx) }
                updatePumpStatus()
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateSecondRow() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                updateLoopIcon()
                runOnUiThread { processButtonsVisibility() }
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ processButtonsVisibility() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                overviewData.rangeToDisplay = it.hours
                preferences.put(IntNonKey.RangeToDisplay, it.hours)
                rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)

        refreshLoop = Runnable { refreshAll(); handler.postDelayed(refreshLoop, 60_000L) }
        handler.postDelayed(refreshLoop, 60_000L)
        handler.post { refreshAll() }
        updatePumpStatus()
        updateCalcProgress()
        popupBolusDialogIfRunning(onClick = false)
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        scheduledGuiUpdate?.let { handler.removeCallbacks(it) }
        scheduledGuiUpdate = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.bgGraph?.let { graph ->
            graph.setOnLongClickListener(null)
            graph.setOnClickListener(null)
            detachAllSeries(graph)
        }
        secondaryGraphs.forEach { detachAllSeries(it) }
        secondaryGraphs.clear()
        secondaryGraphsLabel.clear()
        _binding?.secondaryGraphs?.removeAllViews()
        _binding = null
    }

    /**
     * Detach all OverviewData series from a graph to prevent
     * OverviewDataImpl (singleton) → series.mGraphViews → GraphView → Activity leak chains.
     *
     * graph.removeAllSeries() only clears its mSeries list. Series that were attached
     * via onGraphViewAttached() but removed from mSeries by a race or lifecycle edge case
     * still hold back-references to the GraphView in their mGraphViews list.
     * This explicitly detaches every known series from this graph instance.
     */
    private fun detachAllSeries(graph: com.jjoe64.graphview.GraphView) {
        graph.removeAllSeries()
        // Belt-and-suspenders: explicitly break any stale series → GraphView references
        // on the OverviewDataImpl singleton. Safe even if the series wasn't attached to this graph.
        val allSeries = listOf(
            overviewData.bucketedGraphSeries, overviewData.bgReadingGraphSeries,
            overviewData.predictionsGraphSeries, overviewData.baseBasalGraphSeries,
            overviewData.tempBasalGraphSeries, overviewData.basalLineGraphSeries,
            overviewData.absoluteBasalGraphSeries, overviewData.temporaryTargetSeries,
            overviewData.runningModesSeries, overviewData.activitySeries,
            overviewData.activityPredictionSeries, overviewData.epsSeries,
            overviewData.treatmentsSeries, overviewData.therapyEventSeries,
            overviewData.iobSeries, overviewData.absIobSeries,
            overviewData.iobPredictions1Series, overviewData.minusBgiSeries,
            overviewData.minusBgiHistSeries, overviewData.cobSeries,
            overviewData.cobMinFailOverSeries, overviewData.deviationsSeries,
            overviewData.ratioSeries, overviewData.varSensSeries,
            overviewData.acceIsfSeries, overviewData.bgIsfSeries,
            overviewData.ppIsfSeries, overviewData.duraIsfSeries,
            overviewData.finalIsfSeries, overviewData.iobThSeries,
            overviewData.dsMaxSeries, overviewData.dsMinSeries,
            overviewData.heartRateGraphSeries, overviewData.stepsCountGraphSeries
        )
        for (s in allSeries) {
            (s as? com.jjoe64.graphview.series.Series<*>)?.onGraphViewDetached(graph)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    // --- Coalesced GUI update (matches standard OverviewFragment pattern) ---
    // Multiple rapid-fire events are batched into a single refreshAll() 500ms later.

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {
            override fun run() {
                refreshAll()
                scheduledGuiUpdate = null
            }
        }
        scheduledGuiUpdate?.let { handler.removeCallbacks(it) }
        scheduledGuiUpdate = UpdateRunnable()
        scheduledGuiUpdate?.let { handler.postDelayed(it, 500) }
    }

    // --- Progress bar ---

    private fun updateCalcProgress() {
        _binding ?: return
        binding.progressBar.visibility = (overviewData.calcProgressPct != 100).toVisibility()
        binding.progressBar.progress = overviewData.calcProgressPct
    }

    // --- Refresh ---

    private fun refreshAll() {
        if (!config.appInitialized || !isAdded) return
        // Cache boost status for this refresh cycle (expensive: TDD calcs + regex parsing)
        val cachedStatus = boostHelper.getBoostStatus()
        lastBoostStatus = cachedStatus  // Store for click handlers
        updateBg()
        updateIobAndBoost(cachedStatus)
        updateProfile()
        updatePumpInfo(cachedStatus)
        updateSecondRow(cachedStatus)
        updateAutoIsfRow(cachedStatus)
        updateTemporaryBasal()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            processButtonsVisibility()
            updateGraph()
            updateNotification()
            binding.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)
        }
    }

    // --- BG Bobble ---

    @SuppressLint("SetTextI18n")
    private fun updateBg() {
        if (!isAdded) return
        val lastBg = lastBgData.lastBg()
        val isActual = lastBgData.isActualBg()
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val trendDesc = trendCalculator.getTrendDescription(iobCobCalculator.ads)
        val (trendAngle, chevronRot) = BgBobbleView.trendToAngles(trendArrow)

        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.bgBobble.bgValueMgdl = lastBg?.recalculated ?: 0.0
            binding.bgBobble.bgDisplayText = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
            binding.bgBobble.unitsLabel = if (profileFunction.getUnits() == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
            binding.bgBobble.isActualBg = isActual
            binding.bgBobble.trendAngleDeg = trendAngle
            binding.bgBobble.chevronRotateDeg = chevronRot

            if (isAutoISF()) {
                // AutoISF mode: show the BG acceleration ("a:") in place of the delta/trend line
                binding.deltaText.text =
                    if (glucoseStatus != null) "a: ${String.format(Locale.ENGLISH, "%.1f", glucoseStatus.bgAcceleration)}" else ""
            } else if (glucoseStatus != null) {
                binding.deltaText.text = "${profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)} \u00B7 ${trendDesc ?: ""}"
            } else {
                binding.deltaText.text = ""
            }

            binding.timeAgo.text = dateUtil.minOrSecAgo(rh, lastBg?.timestamp)

            // BG-quality warning triangle (yellow = recalculated, red = doubled, hidden = ok)
            val qualityIcon = bgQualityCheck.icon()
            if (qualityIcon != 0) {
                binding.bgQuality.visibility = View.VISIBLE
                binding.bgQuality.setImageResource(qualityIcon)
                binding.bgQuality.contentDescription = rh.gs(R.string.a11y_bg_quality) + " " + bgQualityCheck.stateDescription()
                binding.bgQuality.setOnClickListener {
                    context?.let { ctx -> OKDialog.show(ctx, rh.gs(R.string.data_status), bgQualityCheck.message) }
                }
            } else {
                binding.bgQuality.visibility = View.GONE
            }

            updateLoopIcon()

            // TalkBack content descriptions
            val bgStr = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
            val unitsStr = binding.bgBobble.unitsLabel
            val trendStr = trendDesc ?: ""
            val ageStr = binding.timeAgo.text
            binding.bgBobble.contentDescription = "Blood glucose $bgStr $unitsStr, $trendStr, $ageStr"
            binding.deltaText.contentDescription = binding.deltaText.text
        }
    }

    // --- Loop status icon --- (matches standard OverviewFragment processAps logic)

    private fun updateLoopIcon() {
        runOnUiThread {
            _binding ?: return@runOnUiThread
            val pump = activePlugin.activePump
            if (pump.pumpDescription.isTempBasalCapable) {
                binding.connectionIcon.visibility = View.VISIBLE
                when (loop.runningMode) {
                    RM.Mode.SUPER_BOLUS       -> {
                        binding.connectionIcon.setImageResource(R.drawable.ic_loop_superbolus)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.superbolus)
                        binding.loopStatusText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.loopStatusText.visibility = View.VISIBLE
                    }
                    RM.Mode.DISCONNECTED_PUMP -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_disconnected)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.disconnected)
                        binding.loopStatusText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.loopStatusText.visibility = View.VISIBLE
                    }
                    RM.Mode.SUSPENDED_BY_PUMP -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.pumpsuspended)
                        binding.loopStatusText.visibility = View.GONE
                    }
                    RM.Mode.SUSPENDED_BY_USER -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.loopsuspended)
                        binding.loopStatusText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.loopStatusText.visibility = View.VISIBLE
                    }
                    RM.Mode.SUSPENDED_BY_DST  -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.loop_suspended_by_dst)
                        binding.loopStatusText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.loopStatusText.visibility = View.VISIBLE
                    }
                    RM.Mode.CLOSED_LOOP_LGS   -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_lgs)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.uel_lgs_loop_mode)
                        binding.loopStatusText.visibility = View.GONE
                    }
                    RM.Mode.CLOSED_LOOP       -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.objects.R.drawable.ic_loop_closed)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.closedloop)
                        binding.loopStatusText.visibility = View.GONE
                    }
                    RM.Mode.OPEN_LOOP         -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_open)
                        binding.connectionIcon.contentDescription = rh.gs(app.aaps.core.ui.R.string.openloop)
                        binding.loopStatusText.visibility = View.GONE
                    }
                    RM.Mode.DISABLED_LOOP     -> {
                        binding.connectionIcon.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_disabled)
                        binding.connectionIcon.contentDescription = rh.gs(R.string.disabled_loop)
                        binding.loopStatusText.visibility = View.GONE
                    }
                    RM.Mode.RESUME            -> error("Invalid mode")
                }
            } else {
                binding.connectionIcon.visibility = View.GONE
                binding.loopStatusText.visibility = View.GONE
            }
            processButtonsVisibility()
        }
    }

    // --- Temporary basal rate (TBR / ВБС) --- shown as a percentage under the loop icon

    @SuppressLint("SetTextI18n")
    private fun updateTemporaryBasal() {
        if (!isAdded) return
        val profile = profileFunction.getProfile()
        val tempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())
            ?.takeIf { it.isInProgress }
        val percent = if (profile != null && tempBasal != null) tempBasal.convertedToPercent(dateUtil.now(), profile) else 100
        val usePercentage = preferences.get(BooleanKey.OverviewBasalIsAlwaysNotAbsolute)
        val tbrText = if (usePercentage || profile == null) "$percent%"
        else rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, tempBasal?.convertedToAbsolute(dateUtil.now(), profile) ?: profile.getBasal())
        val icon = overviewData.temporaryBasalIcon()
        val color = overviewData.temporaryBasalColor(context)
        val dialogText = overviewData.temporaryBasalDialogText()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.tbrIcon.setImageResource(icon)
            binding.tbrValue.text = tbrText
            binding.tbrValue.setTextColor(color)
            binding.tbrLayout.contentDescription = rh.gs(app.aaps.core.ui.R.string.basal) + " " + tbrText
            binding.tbrLayout.setOnClickListener {
                context?.let { ctx -> OKDialog.show(ctx, rh.gs(app.aaps.core.ui.R.string.basal), dialogText) }
            }
        }
    }

    // --- IOB + Boost ---

    @SuppressLint("SetTextI18n")
    private fun updateIobAndBoost(cached: BoostOverviewHelper.BoostStatus? = null) {
        if (!isAdded) return
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob
        val boostStatus = cached ?: boostHelper.getBoostStatus()
        lastBoostStatus = boostStatus

        runOnUiThread {
            _binding ?: return@runOnUiThread
            val ctx = context ?: return@runOnUiThread
            binding.panelIobValue.text = String.format(Locale.getDefault(), "%.2fu", totalIob)
            binding.panelIobValue.setTextColor(rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor))
            binding.panelBoostValue.text = boostStatus.tierLabel
            binding.panelBoostValue.setTextColor(boostStatus.tier.colorHex.toInt())
            binding.panelBoostFastCarb.visibility = if (boostStatus.fastCarbProtection) View.VISIBLE else View.GONE

            val dynRaw = boostStatus.dynIsfValue
            val dynDisp = if (dynRaw > 0) {
                val converted = profileUtil.fromMgdlToUnits(dynRaw)
                if (profileFunction.getUnits() == GlucoseUnit.MGDL) String.format(Locale.getDefault(), "%.0f", converted)
                else String.format(Locale.getDefault(), "%.1f", converted)
            } else "---"
            // CR is g/U (unit-independent). Show "---" when Dynamic CR is off, mirroring how the ISF line is
            // "---" without dynamic ISF — only display a CR value when Dynamic CR was actually engaged.
            val crDisp = if (boostStatus.dynamicCrActive && boostStatus.carbRatioEffective > 0)
                String.format(Locale.getDefault(), "%.1f", boostStatus.carbRatioEffective) else "---"
            binding.panelDynisfValue.text = "ISF $dynDisp\nCR $crDisp"

            // TalkBack content descriptions (set on parent panels — the clickable elements)
            binding.panelIob.contentDescription = "Insulin on board: ${String.format(Locale.getDefault(), "%.2f", totalIob)} units. Tap for details"
            val fcDesc = if (boostStatus.fastCarbProtection) "Fast carb protection active. " else ""
            binding.panelBoost.contentDescription = "${fcDesc}Boost tier: ${boostStatus.tierLabel}. Tap for details"
            val unitsStr = if (profileFunction.getUnits() == GlucoseUnit.MGDL) "mg/dL per unit" else "mmol/L per unit"
            binding.panelDynisf.contentDescription = "Dynamic ISF: $dynDisp $unitsStr. Carb ratio: $crDisp grams per unit. Tap for details"

            updateV5Strip(boostStatus)
        }
    }

    /**
     * Boost V6 decision strip — mirrors the V2 overview's strip. Shown only when Boost V6 is the
     * active APS doser (rt.boostV5_active → BoostStatus.isV5Active); hidden for every other module.
     * Must be called on the UI thread with a valid binding (as it is from [updateIobAndBoost]).
     */
    @SuppressLint("SetTextI18n")
    private fun updateV5Strip(bs: BoostOverviewHelper.BoostStatus) {
        _binding ?: return
        if (!bs.isV5Active) {
            binding.v5Strip.visibility = View.GONE
            return
        }
        binding.v5Strip.visibility = View.VISIBLE
        val color = bs.v5State.colorHex.toInt()

        // Row 1: state · action multiplier · age, then dose / budget
        binding.v5State.text = "${bs.v5State.label.uppercase(Locale.getDefault())}  ×${String.format(Locale.getDefault(), "%.1f", bs.v5ActionMult)} · ${bs.v5Age}c"
        binding.v5State.setTextColor(color)
        binding.v5Dose.text = "dose ${String.format(Locale.getDefault(), "%.2f", bs.v5FinalDose)}U · budget ${String.format(Locale.getDefault(), "%.2f", bs.v5Budget)}U"

        // Row 2: meal-score gauge (0..1 → 0..100), tinted by state colour
        binding.v5ScoreBar.progress = (bs.v5Score * 100).toInt().coerceIn(0, 100)
        binding.v5ScoreBar.progressTintList = ColorStateList.valueOf(color)
        binding.v5ScoreText.text = String.format(Locale.getDefault(), "%.2f", bs.v5Score)

        // Row 3: active brakes — red if any hard gate fired, else amber; hidden when none
        if (bs.v5Brakes.isEmpty()) {
            binding.v5Brakes.visibility = View.GONE
        } else {
            binding.v5Brakes.visibility = View.VISIBLE
            binding.v5Brakes.text = bs.v5Brakes.joinToString("   ·   ") { b ->
                if (b.isHard) "⛔ ${b.label}" else "${b.label} ${String.format(Locale.getDefault(), "%.2f", b.factor)}"
            }
            val hasHard = bs.v5Brakes.any { it.isHard }
            binding.v5Brakes.setTextColor(Color.parseColor(if (hasHard) "#ff5252" else "#fb923c"))
        }

        binding.v5Strip.contentDescription =
            "Boost V6 ${bs.v5State.verb}, score ${String.format(Locale.getDefault(), "%.2f", bs.v5Score)}, dose ${String.format(Locale.getDefault(), "%.2f", bs.v5FinalDose)} units"
    }

    /** Full Boost V6 decision breakdown dialog — text is shared with the V2 overview via the helper. */
    private fun showV5Detail() {
        val a = activity ?: return
        OKDialog.show(a, "Boost V6 decision", BoostOverviewHelper.formatV5Detail(lastBoostStatus))
    }

    // --- Profile ---

    private fun updateProfile() {
        if (!isAdded) return
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getOriginalProfileName()
            .replace(Regex("""\s*\(\d+%\)$"""), "")
        val isModified = (profile as? ProfileSealed.EPS)?.let {
            it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L
        } ?: false
        val pct = (profile as? ProfileSealed.EPS)?.value?.originalPercentage ?: 100

        runOnUiThread {
            _binding ?: return@runOnUiThread
            val ctx = context ?: return@runOnUiThread
            val color = if (isModified) rh.gac(ctx, app.aaps.core.ui.R.attr.warningColor)
            else rh.gac(ctx, app.aaps.core.ui.R.attr.bgInRange)
            binding.panelProfileName.text = profileName
            binding.panelProfileName.setTextColor(color)
            binding.panelProfilePct.text = "${pct}%"
            binding.panelProfilePct.setTextColor(color)

            // TalkBack
            val modStr = if (isModified) ", modified" else ""
            binding.panelProfile.contentDescription = "Profile: $profileName, $pct percent$modStr. Tap to view, long press to switch"
        }
    }

    // --- Pump info ---

    @SuppressLint("SetTextI18n")
    private fun updatePumpInfo(cached: BoostOverviewHelper.BoostStatus? = null) {
        if (!isAdded) return
        val pump = activePlugin.activePump
        val boostStatus = cached ?: boostHelper.getBoostStatus()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            val res = pump.reservoirLevel
            val maxReading = pump.pumpDescription.maxResorvoirReading.toDouble()
            if (pump.pumpDescription.isPatchPump && res >= maxReading) {
                binding.pumpReservoir.text = "${decimalFormatter.to0Decimal(maxReading)}+U"
                binding.pumpReservoir.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor))
            } else if (res > 0) {
                binding.pumpReservoir.text = "${decimalFormatter.to0Decimal(res)}U"
                warnColors.setColorInverse(binding.pumpReservoir, res,
                    preferences.get(IntKey.OverviewResWarning), preferences.get(IntKey.OverviewResCritical))
            } else {
                binding.pumpReservoir.text = "---"
                binding.pumpReservoir.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor))
            }
            val batteryText = pumpBatteryText()
            binding.pumpBattery.text = "\uD83D\uDD0B ${batteryText ?: "---"}"
            val cStr = formatAgeDaysHours(boostStatus.cannulaAgeDays)
            val sStr = formatAgeDaysHours(boostStatus.sensorAgeDays)
            binding.pumpCannulaAge.text = "\uD83E\uDE79 $cStr"
            binding.pumpSensorAge.text = "\uD83D\uDCE1 $sStr"
            // Apply warning/critical colours matching the standard status lights
            val cannulaTE = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
            val sensorTE = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            if (cannulaTE != null) {
                warnColors.setColorByAge(binding.pumpCannulaAge, cannulaTE,
                    preferences.get(IntKey.OverviewCageWarning), preferences.get(IntKey.OverviewCageCritical))
            } else {
                binding.pumpCannulaAge.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor))
            }
            if (sensorTE != null) {
                warnColors.setColorByAge(binding.pumpSensorAge, sensorTE,
                    preferences.get(IntKey.OverviewSageWarning), preferences.get(IntKey.OverviewSageCritical))
            } else {
                binding.pumpSensorAge.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor))
            }

            // TalkBack — set on container; mark children as not individually important
            val resDesc = if (pump.pumpDescription.isPatchPump && res >= maxReading) {
                "${decimalFormatter.to0Decimal(maxReading)} plus units"
            } else if (res > 0) {
                "${decimalFormatter.to0Decimal(res)} units"
            } else "unknown"
            val batDesc = batteryText ?: "unknown"
            val cDesc = formatAgeDaysHours(boostStatus.cannulaAgeDays)
            val sDesc = formatAgeDaysHours(boostStatus.sensorAgeDays)
            binding.pumpSection.contentDescription = "Pump: reservoir $resDesc, battery $batDesc, cannula $cDesc, sensor $sDesc"
        }
    }

    /** Battery level shown in the pump panel, matching the widget.
     *  On the master this is the active pump's battery; on AAPSClient there is no
     *  local pump, so show the master phone's battery synced from Nightscout. */
    private fun pumpBatteryText(): String? =
        if (config.AAPSCLIENT) {
            processedDeviceStatusData.uploaderMap.values
                .minByOrNull { it.battery }
                ?.takeIf { it.battery > 0 }
                ?.let { "${it.battery}%" }
        } else {
            activePlugin.activePump.batteryLevel?.let { "$it%" }
        }

    // --- Second row panels (TDD, Target, Activity, Autosens) ---

    @SuppressLint("SetTextI18n")
    private fun updateSecondRow(cached: BoostOverviewHelper.BoostStatus? = null) {
        if (!isAdded) return
        val bs = cached ?: boostHelper.getBoostStatus()

        // Target: always show active temp target from DB if one exists.
        // Only use algorithm's adjusted target when no temp target is active.
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val hasTempTarget = tempTarget != null
        val profileTargetMgdl = profileFunction.getProfile()?.getTargetMgdl() ?: 0.0
        val targetMgdl: Double
        val apsAdjustedTarget: Boolean

        if (hasTempTarget) {
            // Active temp target — show its actual value, not the algorithm's interpretation
            targetMgdl = tempTarget!!.lowTarget
            apsAdjustedTarget = false
        } else if (bs.targetBgMgdl > 0 && profileTargetMgdl > 0 &&
            kotlin.math.abs(profileTargetMgdl - bs.targetBgMgdl) > 0.01) {
            // No temp target but algorithm has adjusted away from profile
            // (e.g. sensitivity raises/lowers target)
            targetMgdl = bs.targetBgMgdl
            apsAdjustedTarget = true
        } else {
            // No temp target, no algorithm adjustment — show profile target
            targetMgdl = profileTargetMgdl.takeIf { it > 0 } ?: 100.0
            apsAdjustedTarget = false
        }
        val targetStr = profileUtil.fromMgdlToStringInUnits(targetMgdl)

        // TDD: try oapsProfile.TDD (weighted), then parse from scriptDebug, fallback to 7d avg
        val tddWeighted = bs.tddWeighted
        val tddFromDebug = bs.tddFromDebug
        val tddDisplay = when {
            tddWeighted > 0  -> tddWeighted
            tddFromDebug > 0 -> tddFromDebug
            else              -> bs.tdd7d
        }

        // COB — derived from carbs in the IobCob calculator (works on both master and client)
        val cobText = iobCobCalculator.getCobInfo("Boost overview COB").displayText(rh, decimalFormatter)
            ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        runOnUiThread {
            _binding ?: return@runOnUiThread
            val ctx = context ?: return@runOnUiThread

            binding.panelTddValue.text = if (tddDisplay > 0) String.format(Locale.getDefault(), "%.1f", tddDisplay) else "---"

            binding.panelCobValue.text = cobText
            binding.panelCobValue.setTextColor(rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor))

            binding.panelTargetValue.text = if (hasTempTarget) "$targetStr ${dateUtil.untilString(tempTarget!!.end, rh)}" else targetStr
            binding.panelTargetValue.setTextColor(
                when {
                    hasTempTarget     -> rh.gac(ctx, app.aaps.core.ui.R.attr.warningColor)
                    apsAdjustedTarget -> rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor)
                    else              -> rh.gac(ctx, app.aaps.core.ui.R.attr.bgInRange)
                })

            binding.panelActivityValue.text = bs.activityDetail
            val actColor = when (bs.activityMode) {
                BoostOverviewHelper.ActivityMode.ACTIVE    -> rh.gac(ctx, app.aaps.core.ui.R.attr.warningColor)
                BoostOverviewHelper.ActivityMode.INACTIVE  -> rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)
                BoostOverviewHelper.ActivityMode.SLEEP_IN  -> rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor)
                BoostOverviewHelper.ActivityMode.BOOST_OFF -> rh.gac(ctx, app.aaps.core.ui.R.attr.defaultTextColor)
                else                                       -> rh.gac(ctx, app.aaps.core.ui.R.attr.bgInRange)
            }
            binding.panelActivityValue.setTextColor(actColor)

            // TalkBack content descriptions
            val tddStr = if (tddDisplay > 0) String.format(Locale.getDefault(), "%.1f units", tddDisplay) else "unavailable"
            binding.panelTdd.contentDescription = "Total daily dose: $tddStr. Tap for details"
            binding.panelCob.contentDescription = "Carbs on board: $cobText. Tap to add carbs"
            val unitsLabel = if (profileFunction.getUnits() == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
            val targetDesc = when {
                hasTempTarget     -> "Temporary target: $targetStr $unitsLabel, ${dateUtil.untilString(tempTarget!!.end, rh)} remaining"
                apsAdjustedTarget -> "Algorithm adjusted target: $targetStr $unitsLabel"
                else              -> "Target: $targetStr $unitsLabel"
            }
            binding.panelTarget.contentDescription = "$targetDesc. Tap to set temporary target"
            binding.panelActivity.contentDescription = "Exercise mode: ${bs.activityDetail}. Tap for details"
        }
    }

    /** True when the active APS algorithm is AutoISF — switches the second detail row. */
    private fun isAutoISF(): Boolean = activePlugin.activeAPS.algorithm == APSResult.Algorithm.AUTO_ISF

    // --- AutoISF detail row (Deltas | ISF | AutoISF factors | reserved) ---
    // Shown instead of the standard BOOST/TDD/DynISF/EXERCISE row when AutoISF is active.

    @SuppressLint("SetTextI18n")
    private fun updateAutoIsfRow(cached: BoostOverviewHelper.BoostStatus? = null) {
        if (!isAdded) return
        val autoIsf = isAutoISF()
        val bs = cached ?: boostHelper.getBoostStatus()
        val units = profileFunction.getUnits()
        val isfFmt = if (units == GlucoseUnit.MGDL) "%.0f" else "%.1f"

        fun isfStr(mgdl: Double): String =
            if (mgdl > 0) String.format(Locale.getDefault(), isfFmt, profileUtil.fromMgdlToUnits(mgdl)) else "---"

        fun factorStr(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.row2Standard.visibility = (!autoIsf).toVisibility()
            binding.row2Autoisf.visibility = autoIsf.toVisibility()
            if (!autoIsf) return@runOnUiThread

            // Block 1 — BG deltas (current / 15m / 40m)
            binding.autoisfDelta.text = "Δ ${profileUtil.fromMgdlToSignedStringInUnits(bs.deltaMgdl)}"
            binding.autoisfAvgDelta.text = "Δ15 ${profileUtil.fromMgdlToSignedStringInUnits(bs.shortAvgDeltaMgdl)}"
            binding.autoisfLongAvgDelta.text = "Δ40 ${profileUtil.fromMgdlToSignedStringInUnits(bs.longAvgDeltaMgdl)}"

            // Block 2 — profile ISF, calculated ISF, and the effective carb ratio (Dynamic CR; profile CR when off)
            binding.autoisfIsfProfile.text = "ISFp ${isfStr(bs.isfProfileMgdl)}"
            binding.autoisfIsfCalc.text = "ISFc ${isfStr(bs.variableSens)}"
            val crEff = bs.carbRatioEffective
            binding.autoisfIsfCarbs.text = "CR ${if (crEff > 0) String.format(Locale.getDefault(), "%.1f", crEff) else "---"}"

            // Block 3 — AutoISF intermediate factors
            binding.autoisfAcce.text = "Accel ${factorStr(bs.acceIsf)}"
            binding.autoisfBg.text = "BG ${factorStr(bs.bgIsf)}"
            binding.autoisfPp.text = "PP ${factorStr(bs.ppIsf)}"
            binding.autoisfDura.text = "Dura ${factorStr(bs.duraIsf)}"
            binding.autoisfFinal.text = "Final ${factorStr(bs.finalIsf)}"
        }
    }

    // --- Pump status bar ---

    private fun updatePumpStatus() {
        runOnUiThread {
            _binding ?: return@runOnUiThread
            val status = overviewData.pumpStatus
            if (status.isEmpty()) binding.pumpStatusLayout.visibility = View.GONE
            else { binding.pumpStatus.text = status; binding.pumpStatusLayout.visibility = View.VISIBLE }
        }
    }

    // --- Graph (primary only) ---

    private fun updateGraph() {
        _binding ?: return
        val ctx = context ?: return
        val pump = activePlugin.activePump
        val graphData = graphDataProvider.get().with(binding.bgGraph, overviewData)
        val menuChartSettings = overviewMenus.setting
        if (menuChartSettings.isEmpty()) return

        graphData.addInRangeArea(overviewData.fromTime, overviewData.endTime,
            preferences.get(UnitDoubleKey.OverviewLowMark), preferences.get(UnitDoubleKey.OverviewHighMark))
        graphData.addBgReadings(menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal], ctx)
        graphData.addBucketedData()
        graphData.addTreatments(ctx)
        graphData.addEps(ctx, 0.95)
        if (menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal]) graphData.addTherapyEvents()
        if (menuChartSettings[0][OverviewMenus.CharType.PROFILE_CHANGE.ordinal]) graphData.addProfileChangeEvents()
        if (menuChartSettings[0][OverviewMenus.CharType.ACT.ordinal]) graphData.addActivity(0.8)
        if ((pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT) && menuChartSettings[0][OverviewMenus.CharType.BAS.ordinal])
            graphData.addBasals()
        graphData.addTargetLine()
        graphData.addRunningModes()
        graphData.addNowLine(dateUtil.now())
        graphData.setNumVerticalLabels()
        graphData.formatAxis(overviewData.fromTime, overviewData.endTime)
        graphData.performUpdate()

        // Secondary graphs — one per chart-menu column, each showing whatever
        // metrics are ticked in that column (IOB, COB, ABS, deviations, HR, steps…).
        // Mirrors OverviewFragment.updateGraph() so behaviour matches the standard UI.
        prepareGraphsIfNeeded(menuChartSettings.size)
        val secondaryGraphsData: ArrayList<GraphData> = ArrayList()

        val now = dateUtil.now()
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            val secondGraphData = graphDataProvider.get().with(secondaryGraphs[g], overviewData)
            var useABSForScale = false
            var useIobForScale = false
            var useCobForScale = false
            var useDevForScale = false
            var useRatioForScale = false
            var useVarSensForScale = false
            var useDSForScale = false
            var useBGIForScale = false
            var useHRForScale = false
            var useSTEPSForScale = false
            var useAcceIsfForScale = false
            var useBgIsfForScale = false
            var usePpIsfForScale = false
            var useDuraIsfForScale = false
            var useFinalIsfForScale = false
            var useIobThForScale = false
            when {
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]      -> useABSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]      -> useIobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]      -> useCobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]      -> useDevForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]      -> useBGIForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]      -> useRatioForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal]  -> useVarSensForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] -> useDSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal]       -> useHRForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]    -> useSTEPSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.ACCE_ISF.ordinal] -> useAcceIsfForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal]   -> useBgIsfForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal]   -> usePpIsfForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DURA_ISF.ordinal] -> useDuraIsfForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.FINAL_ISF.ordinal]-> useFinalIsfForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal]   -> useIobThForScale = true
            }
            val alignDevBgiScale = menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] && menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]
            // AutoISF factor graphs: when 2+ are on the same sub-graph, share one 1.0-centred axis (maxAutoIsfFactor)
            var maxAutoIsfFactor = 1.0
            var commonIsfCount = 0
            if (menuChartSettings[g + 1][OverviewMenus.CharType.FINAL_ISF.ordinal]) { maxAutoIsfFactor = maxOf(maxAutoIsfFactor, overviewData.maxFinalIsfValueFound); commonIsfCount++ }
            if (menuChartSettings[g + 1][OverviewMenus.CharType.ACCE_ISF.ordinal]) { maxAutoIsfFactor = maxOf(maxAutoIsfFactor, overviewData.maxAcceIsfValueFound); commonIsfCount++ }
            if (menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal]) { maxAutoIsfFactor = maxOf(maxAutoIsfFactor, overviewData.maxBgIsfValueFound); commonIsfCount++ }
            if (menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal]) { maxAutoIsfFactor = maxOf(maxAutoIsfFactor, overviewData.maxPpIsfValueFound); commonIsfCount++ }
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DURA_ISF.ordinal]) { maxAutoIsfFactor = maxOf(maxAutoIsfFactor, overviewData.maxDuraIsfValueFound); commonIsfCount++ }
            val useCommonISFForScale = commonIsfCount > 1
            val maxCommonIob = if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal] &&
                (menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] || menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal])
            ) maxOf(overviewData.maxIobValueFound, overviewData.maxIobThValueFound) else 0.0

            if (menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]) secondGraphData.addAbsIob(useABSForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]) secondGraphData.addIob(useIobForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]) secondGraphData.addCob(useCobForScale, if (useCobForScale) 1.0 else 0.5)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]) secondGraphData.addDeviations(useDevForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]) secondGraphData.addMinusBGI(useBGIForScale, if (alignDevBgiScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]) secondGraphData.addRatio(useRatioForScale, if (useRatioForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal]) secondGraphData.addVarSens(useVarSensForScale, if (useVarSensForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] && config.isDev()) secondGraphData.addDeviationSlope(
                useDSForScale,
                if (useDSForScale) 1.0 else 0.8,
                useRatioForScale
            )
            if (menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal]) secondGraphData.addHeartRate(useHRForScale, if (useHRForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]) secondGraphData.addSteps(useSTEPSForScale, if (useSTEPSForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.ACCE_ISF.ordinal]) secondGraphData.addAcceIsf(useAcceIsfForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal]) secondGraphData.addBgIsf(useBgIsfForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal]) secondGraphData.addPpIsf(usePpIsfForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DURA_ISF.ordinal]) secondGraphData.addDuraIsf(useDuraIsfForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.FINAL_ISF.ordinal]) secondGraphData.addFinalIsf(useFinalIsfForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal]) secondGraphData.addIobTh(useIobThForScale, if (maxCommonIob > 0.0) 1.0 else 0.8, maxCommonIob)

            secondGraphData.formatAxis(overviewData.fromTime, overviewData.endTime)
            secondGraphData.addNowLine(now)
            secondaryGraphsData.add(secondGraphData)
        }
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            secondaryGraphsLabel[g].text = overviewMenus.enabledTypes(g + 1)
            secondaryGraphs[g].visibility = (
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.ACCE_ISF.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DURA_ISF.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.FINAL_ISF.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal]
                ).toVisibility()
            secondaryGraphsData[g].performUpdate()
        }

        // TalkBack
        val hours = overviewData.rangeToDisplay
        binding.bgGraph.contentDescription = "Blood glucose graph, ${hours} hour view. Tap to open treatments. Long press to change time range"
    }

    /**
     * Build/rebuild the dynamic secondary graph views to match the number of chart-menu
     * columns. Identical in spirit to OverviewFragment.prepareGraphsIfNeeded().
     */
    private fun prepareGraphsIfNeeded(numOfGraphs: Int) {
        _binding ?: return
        if (numOfGraphs != secondaryGraphs.size + 1) {
            secondaryGraphs.clear()
            secondaryGraphsLabel.clear()
            binding.secondaryGraphs.removeAllViews()
            val subtleGrid = run {
                val gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
                android.graphics.Color.argb(
                    (android.graphics.Color.alpha(gridColor) * 0.2f).toInt(),
                    android.graphics.Color.red(gridColor),
                    android.graphics.Color.green(gridColor),
                    android.graphics.Color.blue(gridColor)
                )
            }
            (1 until numOfGraphs).forEach { _ ->
                val relativeLayout = RelativeLayout(context)
                relativeLayout.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val graph = GraphViewWithCleanup(requireContext())
                graph.layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh.dpToPx(skinProvider.activeSkin().secondaryGraphHeight))
                        .also { it.setMargins(0, rh.dpToPx(15), 0, rh.dpToPx(10)) }
                graph.gridLabelRenderer?.gridColor = subtleGrid
                graph.gridLabelRenderer?.reloadStyles()
                graph.gridLabelRenderer?.isHorizontalLabelsVisible = false
                graph.gridLabelRenderer?.labelVerticalWidth = axisWidth
                graph.gridLabelRenderer?.numVerticalLabels = 3
                graph.viewport.backgroundColor = rh.gac(context, app.aaps.core.ui.R.attr.viewPortBackgroundColor)
                relativeLayout.addView(graph)

                val label = TextView(context)
                val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.setMargins(rh.dpToPx(30), rh.dpToPx(25), 0, 0) }
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_START)
                label.layoutParams = layoutParams
                relativeLayout.addView(label)
                secondaryGraphsLabel.add(label)

                binding.secondaryGraphs.addView(relativeLayout)
                secondaryGraphs.add(graph)
            }
        }
    }

    // --- Notifications ---

    private fun updateNotification() {
        _binding ?: return
        binding.notifications.let { notificationStore.updateNotifications(it) }
    }

    // --- Button visibility + automation user action buttons ---
    // Mirrors OverviewFragment.processButtonsVisibility() exactly

    @SuppressLint("SetTextI18n")
    private fun processButtonsVisibility() {
        _binding ?: return
        val ctx = context ?: return
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        val actualBG = iobCobCalculator.ads.actualBg()
        val xDripIsBgSource = activePlugin.activeBgSource is XDripSource
        val dexcomIsSource = activePlugin.activeBgSource is DexcomBoyda

        // Quick Wizard button
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBG != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileFunction.getProfileName(), actualBG)
            binding.buttonsLayout.quickWizardButton.text = quickWizardEntry.buttonText() + "\n" +
                rh.gs(app.aaps.core.objects.R.string.format_carbs, quickWizardEntry.carbs()) +
                " " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.calculatedTotalInsulin)
            if (wizard.calculatedTotalInsulin <= 0) binding.buttonsLayout.quickWizardButton.visibility = View.GONE
        } else binding.buttonsLayout.quickWizardButton.visibility = View.GONE

        // Accept temp button
        val lastRun = loop.lastRun
        if (lastRun?.request?.isChangeRequested == true && loop.runningMode != RM.Mode.DISCONNECTED_PUMP && loop.runningMode == RM.Mode.OPEN_LOOP) {
            binding.buttonsLayout.acceptTempButton.visibility = View.VISIBLE
            binding.buttonsLayout.acceptTempButton.text = "${rh.gs(R.string.set_basal_question)}\n${lastRun.constraintsProcessed?.resultAsString()}"
        } else {
            binding.buttonsLayout.acceptTempButton.visibility = View.GONE
        }

        // Standard button visibility based on preferences and state
        binding.buttonsLayout.carbsButton.visibility =
            (profile != null && preferences.get(BooleanKey.OverviewShowCarbsButton)).toVisibility()
        binding.buttonsLayout.treatmentButton.visibility = (loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized() && profile != null
            && preferences.get(BooleanKey.OverviewShowTreatmentButton)).toVisibility()
        binding.buttonsLayout.wizardButton.visibility = (loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized() && profile != null
            && preferences.get(BooleanKey.OverviewShowWizardButton)).toVisibility()
        binding.buttonsLayout.insulinButton.visibility = (profile != null && preferences.get(BooleanKey.OverviewShowInsulinButton)).toVisibility()

        // Insulin button: warning colour when pump disconnected/suspended
        if (loop.runningMode == RM.Mode.DISCONNECTED_PUMP || pump.isSuspended() || !pump.isInitialized()) {
            setRibbon(binding.buttonsLayout.insulinButton,
                app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                app.aaps.core.ui.R.attr.ribbonWarningColor,
                rh.gs(app.aaps.core.ui.R.string.overview_insulin_label))
        } else {
            setRibbon(binding.buttonsLayout.insulinButton,
                app.aaps.core.ui.R.attr.icBolusColor,
                app.aaps.core.ui.R.attr.ribbonDefaultColor,
                rh.gs(app.aaps.core.ui.R.string.overview_insulin_label))
        }

        binding.buttonsLayout.calibrationButton.visibility = (xDripIsBgSource && actualBG != null && preferences.get(BooleanKey.OverviewShowCalibrationButton)).toVisibility()
        binding.buttonsLayout.cgmButton.visibility = (preferences.get(BooleanKey.OverviewShowCgmButton) && (xDripIsBgSource || dexcomIsSource)).toVisibility()
        if (dexcomIsSource) {
            binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(R.drawable.ic_byoda), null, null)
            for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                drawable?.mutate()
                drawable?.colorFilter = PorterDuffColorFilter(rh.gac(ctx, app.aaps.core.ui.R.attr.cgmDexColor), PorterDuff.Mode.SRC_IN)
            }
            binding.buttonsLayout.cgmButton.setTextColor(rh.gac(ctx, app.aaps.core.ui.R.attr.cgmDexColor))
        } else if (xDripIsBgSource) {
            binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(app.aaps.core.objects.R.drawable.ic_xdrip), null, null)
            for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                drawable?.mutate()
                drawable?.colorFilter = PorterDuffColorFilter(rh.gac(ctx, app.aaps.core.ui.R.attr.cgmXdripColor), PorterDuff.Mode.SRC_IN)
            }
            binding.buttonsLayout.cgmButton.setTextColor(rh.gac(ctx, app.aaps.core.ui.R.attr.cgmXdripColor))
        }

        // --- Automation user action buttons (second row) ---
        var list = ""
        binding.buttonsLayout.userButtonsLayout.removeAllViews()
        val events = automation.userEvents()
        if (!loop.runningMode.isSuspended() && pump.isInitialized() && profile != null && !config.showUserActionsOnWatchOnly())
            for (event in events)
                if (event.isEnabled && event.canRun()) {
                    context?.let { ctx ->
                        SingleClickButton(ctx, null, app.aaps.core.ui.R.attr.customBtnStyle).also { btn ->
                            btn.setTextColor(rh.gac(ctx, app.aaps.core.ui.R.attr.userOptionColor))
                            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { l ->
                                l.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                            }
                            btn.setPadding(rh.dpToPx(1), btn.paddingTop, rh.dpToPx(1), btn.paddingBottom)
                            btn.compoundDrawablePadding = rh.dpToPx(-4)
                            btn.setCompoundDrawablesWithIntrinsicBounds(
                                null,
                                rh.gd(event.firstActionIcon() ?: app.aaps.core.ui.R.drawable.ic_user_options_24dp).also { icon ->
                                    icon?.setBounds(rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20))
                                }, null, null
                            )
                            btn.text = event.title
                            btn.setOnClickListener {
                                OKDialog.showConfirmation(ctx, rh.gs(R.string.run_question, event.title),
                                    { handler.post { automation.processEvent(event) } })
                            }
                            binding.buttonsLayout.userButtonsLayout.addView(btn)
                            for (d in btn.compoundDrawables) {
                                d?.mutate()
                                d?.colorFilter = PorterDuffColorFilter(rh.gac(ctx, app.aaps.core.ui.R.attr.userOptionColor), PorterDuff.Mode.SRC_IN)
                            }
                        }
                    }
                    list += event.hashCode()
                }
        binding.buttonsLayout.userButtonsLayout.visibility = events.isNotEmpty().toVisibility()

        if (list != lastUserAction) {
            lastUserAction = list
            rxBus.send(EventWearUpdateTiles())
        }
    }

    // --- Click handlers ---

    @SuppressLint("SetTextI18n")
    override fun onClick(v: View) {
        if (childFragmentManager.isStateSaved) return
        activity?.let { a ->
            when (v.id) {
                // Loop status icon — opens loop dialog (suspend, disable, etc.)
                R.id.connection_icon -> {
                    protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded) uiInteraction.runLoopDialog(childFragmentManager, 1)
                    })
                }

                // Boost detail panels
                R.id.panel_boost -> {
                    val bs = lastBoostStatus
                    val fcLine = if (bs.fastCarbProtection) "⚠️ Fast Carb Protection active — UAM/Accel tiers suppressed\n\n" else ""
                    OKDialog.show(a, "Boost Decision",
                        "${fcLine}Current tier: ${bs.tierLabel}\n\nReason: ${bs.tierReason}\n\nDelta accel: ${String.format(Locale.getDefault(), "%.1f", bs.deltaAccl)}")
                }
                R.id.panel_dynisf -> {
                    val bs = lastBoostStatus
                    val units = profileFunction.getUnits()
                    val unitsStr = if (units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
                    val details = StringBuilder()
                    val dynConverted = if (bs.variableSens > 0) profileUtil.fromMgdlToUnits(bs.variableSens) else 0.0
                    val dynFmt = if (units == GlucoseUnit.MGDL) "%.0f" else "%.1f"
                    details.append("Dynamic ISF: ${if (bs.variableSens > 0) String.format(dynFmt, dynConverted) else "---"} $unitsStr/U")
                    details.append("\n\nAlgorithm inputs:")
                    val bgConverted = if (bs.lastBg > 0) profileUtil.fromMgdlToStringInUnits(bs.lastBg) else "---"
                    details.append("\n  BG: $bgConverted $unitsStr")
                    details.append("\n  TDD (weighted): ${if (bs.tddWeighted > 0) String.format(Locale.getDefault(), "%.1f", bs.tddWeighted) else "---"}")
                    if (bs.insulinDivisor > 0) details.append("\n  Insulin divisor: ${bs.insulinDivisor}")
                    details.append("\n\nTDD 7d avg: ${String.format(Locale.getDefault(), "%.1f", bs.tdd7d)}")
                    details.append("\nTDD 24h: ${String.format(Locale.getDefault(), "%.1f", bs.tdd24h)}")
                    OKDialog.show(a, "Dynamic ISF", details.toString())
                }
                R.id.panel_profile -> {
                    uiInteraction.runProfileViewerDialog(childFragmentManager, dateUtil.now(), UiInteraction.Mode.RUNNING_PROFILE)
                }
                R.id.panel_iob -> {
                    val bi = iobCobCalculator.calculateIobFromBolus().round()
                    val ba = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
                    OKDialog.show(a, rh.gs(app.aaps.core.ui.R.string.iob),
                        "Total: ${String.format(Locale.getDefault(), "%.2f", bi.iob + ba.basaliob)}U\nBolus: ${String.format(Locale.getDefault(), "%.2f", bi.iob)}U\nBasal: ${String.format(Locale.getDefault(), "%.2f", ba.basaliob)}U")
                }

                // Second row panels
                R.id.panel_tdd -> {
                    val bs = lastBoostStatus
                    OKDialog.show(a, "Total Daily Dose",
                        "oapsProfile.TDD: ${if (bs.tddWeighted > 0) String.format(Locale.getDefault(), "%.1f", bs.tddWeighted) else "(not set)"}\n" +
                            "TDD from debug: ${if (bs.tddFromDebug > 0) String.format(Locale.getDefault(), "%.1f", bs.tddFromDebug) else "(not found)"}\n" +
                            "TDD 7d avg: ${String.format(Locale.getDefault(), "%.1f", bs.tdd7d)}\n" +
                            "TDD 24h: ${String.format(Locale.getDefault(), "%.1f", bs.tdd24h)}\n\n" +
                            "--- Script Debug ---\n${bs.scriptDebugText.ifEmpty { "(no debug output)" }}")
                }
                R.id.panel_cob -> {
                    protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                        UIRunnable { if (isAdded) uiInteraction.runCarbsDialog(childFragmentManager) })
                }
                R.id.panel_target -> {
                    protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                        UIRunnable { if (isAdded) uiInteraction.runTempTargetDialog(childFragmentManager) })
                }
                R.id.panel_activity -> {
                    val bs = lastBoostStatus
                    val units = profileFunction.getUnits()
                    val unitsLabel = if (units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
                    val exerciseTargetStr = if (bs.targetBgMgdl > 0)
                        "${profileUtil.fromMgdlToStringInUnits(bs.targetBgMgdl)} $unitsLabel"
                    else "---"
                    OKDialog.show(a, "Exercise / Activity Mode",
                        "Current: ${bs.activityDetail}\n\n" +
                            "Exercise target: $exerciseTargetStr\n\n" +
                            "Profile: ${bs.profilePercentage}%\n\n" +
                            "--- Script Debug ---\n${bs.scriptDebugText.ifEmpty { "(no debug output)" }}")
                }
                // AutoISF factor panel — show the APS "Result" block (Script debug / Result / Request)
                // exactly as on the AUTO ISF tab, mirroring the EXERCISE popup pattern.
                R.id.panel_autoisf_factors -> {
                    val spanned = activePlugin.activeAPS.lastResultBlockSpanned()
                    if (spanned != null) OKDialog.show(a, rh.gs(app.aaps.core.ui.R.string.result), spanned)
                    else OKDialog.show(a, rh.gs(app.aaps.core.ui.R.string.result), rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
                }
                // Boost V6 decision strip — full decision breakdown
                R.id.v5_strip -> showV5Detail()

                // Standard AAPS buttons (from overview_buttons_layout)
                R.id.treatment_button -> protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runTreatmentDialog(childFragmentManager) })
                R.id.wizard_button -> protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runWizardDialog(childFragmentManager) })
                R.id.insulin_button -> protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runInsulinDialog(childFragmentManager) })
                R.id.quick_wizard_button -> protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) onClickQuickWizard() })
                R.id.carbs_button -> protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runCarbsDialog(childFragmentManager) })
                R.id.accept_temp_button -> {
                    protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded) {
                            val lastRun = loop.lastRun
                            loop.invoke("Accept temp button", false)
                            if (lastRun?.lastAPSRun != null && lastRun.constraintsProcessed?.isChangeRequested == true) {
                                protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS, UIRunnable {
                                    uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Overview)
                                    binding.buttonsLayout.acceptTempButton.visibility = View.GONE
                                })
                            }
                        }
                    })
                }
                R.id.cgm_button -> {
                    if (xDripSource.isEnabled()) openCgmApp("com.eveningoutpost.dexdrip")
                    else if (dexcomBoyda.isEnabled()) dexcomBoyda.dexcomPackages().forEach { openCgmApp(it) }
                }
                R.id.calibration_button -> {
                    if (xDripSource.isEnabled())
                        uiInteraction.runCalibrationDialog(childFragmentManager)
                }
                R.id.pump_status_layout -> {
                    popupBolusDialogIfRunning(onClick = true)
                }
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (v.id) {
            R.id.connection_icon -> {
                activity?.let { a ->
                    protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded) uiInteraction.runLoopDialog(childFragmentManager, 0)
                    })
                }
                return true
            }
            R.id.panel_profile -> {
                activity?.let { a ->
                    if (loop.runningMode == RM.Mode.DISCONNECTED_PUMP)
                        OKDialog.show(a, rh.gs(R.string.not_available_full), rh.gs(R.string.smscommunicator_pump_disconnected))
                    else
                        protectionCheck.queryProtection(a, ProtectionCheck.Protection.BOLUS,
                            UIRunnable { uiInteraction.runProfileSwitchDialog(childFragmentManager) })
                }
                return true
            }
            R.id.quick_wizard_button -> {
                startActivity(android.content.Intent(v.context, uiInteraction.quickWizardListActivity))
                return true
            }
        }
        return false
    }

    /** Quick wizard click — opens quick wizard list for selection */
    private fun onClickQuickWizard() {
        context?.let { ctx ->
            startActivity(android.content.Intent(ctx, uiInteraction.quickWizardListActivity))
        }
    }

    /** Open a CGM companion app by package name */
    private fun openCgmApp(packageName: String) {
        context?.let { ctx ->
            try {
                val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw android.content.ActivityNotFoundException()
                intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                ctx.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                aapsLogger.debug("Error opening CGM app")
            }
        }
    }

    /** Set button text, background and text colour (matches standard Overview ribbon styling) */
    private fun setRibbon(view: android.widget.TextView, attrResText: Int, attrResBack: Int, text: String) {
        with(view) {
            setText(text)
            setBackgroundColor(rh.gac(context, attrResBack))
            setTextColor(rh.gac(context, attrResText))
            compoundDrawables[0]?.setTint(rh.gac(context, attrResText))
        }
    }

    /**
     * Show bolus progress dialog if a bolus is currently being delivered.
     * Called on resume (auto-show) and on pump status bar tap (manual).
     * Only shows for manual boluses (not SMBs) unless onClick = true.
     */
    fun popupBolusDialogIfRunning(onClick: Boolean) {
        if (commandQueue.bolusInQueue()) {
            if (!BolusProgressData.bolusEnded && (!BolusProgressData.isSMB || onClick)) {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded)
                            uiInteraction.runBolusProgressDialog(childFragmentManager)
                    })
                }
            }
        }
    }

    /** Format age from fractional days to "Xd Yh" string */
    private fun formatAgeDaysHours(ageDays: Double): String {
        if (ageDays < 0) return "?"
        val totalHours = (ageDays * 24).toLong()
        val days = totalHours / 24
        val hours = totalHours % 24
        return "${days}d ${hours}h"
    }
}
