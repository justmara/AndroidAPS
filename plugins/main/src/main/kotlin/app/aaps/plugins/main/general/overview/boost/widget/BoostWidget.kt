package app.aaps.plugins.main.general.overview.boost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.convertedToPercent
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.overview.boost.BgBobbleView
import app.aaps.plugins.main.general.overview.boost.BoostOverviewHelper
import dagger.android.HasAndroidInjector
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Boost-specific home screen widget.
 *
 * Layout: BG bobble + tier on the left, data table on the right.
 * Standard (non-AutoISF) rows: IOB|TBR|COB / Profile|Target / DynISF|Activity.
 */
class BoostWidget : AppWidgetProvider() {

    @Inject lateinit var boostOverviewHelper: BoostOverviewHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var config: Config
    @Inject lateinit var preferences: Preferences

    companion object {

        private var handler = Handler(HandlerThread(BoostWidget::class.simpleName + "Handler").also { it.start() }.looper)

        // The BG bobble bitmap is expensive to render (a ~2 MB ARGB_8888 bitmap plus ~15 Canvas
        // draw calls). Its pixels only depend on BG value, trend, units and size, which change at
        // most every ~5 min, while the widget refreshes every minute. Cache the last bitmap and
        // reuse it when those inputs are unchanged. Static because the provider is re-instantiated
        // per broadcast. (Not recycled on replacement: a previous bitmap may still be in flight to
        // the launcher via RemoteViews; let GC reclaim it.)
        private var cachedBobble: Bitmap? = null
        private var cachedBobbleKey: String? = null

        fun updateWidget(context: Context, from: String) {
            val ids = AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, BoostWidget::class.java))
            // No Boost widget on the home screen -> skip the broadcast. This avoids a wasted
            // dependency injection + bitmap-drawing wake-up every minute when nothing is shown.
            if (ids == null || ids.isEmpty()) return
            context.sendBroadcast(Intent().also {
                it.component = ComponentName(context, BoostWidget::class.java)
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                it.putExtra("from", from)
                it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }

        private fun zoneColor(bgMgdl: Double): Int = when {
            bgMgdl > 250 -> Color.parseColor("#FF1744")
            bgMgdl > 180 -> Color.parseColor("#FFEB3B")
            bgMgdl >= 70 -> Color.parseColor("#4CAF50")
            bgMgdl >= 54 -> Color.parseColor("#FF5722")
            bgMgdl > 0   -> Color.parseColor("#D50000")
            else          -> Color.parseColor("#4CAF50")
        }
    }

    private val intentAction = "OpenApp"

    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        aapsLogger.debug(LTag.WIDGET, "BoostWidget onReceive ${intent?.extras?.getString("from")}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val myIds = AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, BoostWidget::class.java)) ?: intArrayOf()
        val myIdSet = myIds.toSet()
        for (appWidgetId in appWidgetIds) {
            if (appWidgetId in myIdSet) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    override fun onEnabled(context: Context) {}
    override fun onDisabled(context: Context) {}

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.boost_widget_layout)
        val alpha = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        val useBlack = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)

        val intent = Intent(context, uiInteraction.mainActivity).also { it.action = intentAction }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.boost_widget_layout, pendingIntent)

        if (config.APS || useBlack)
            views.setInt(R.id.boost_widget_layout, "setBackgroundColor", Color.argb(alpha, 0, 0, 0))

        // Compute text sizes based on actual widget dimensions
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        val sizes = computeTextSizes(widthDp, heightDp)

        applyTextSizes(views, sizes)

        handler.post {
            if (config.appInitialized) {
                updateBgBobble(views, context, heightDp)
                updateBoostData(views)
                updateTarget(views, R.id.temp_target)
                updateIob(views)
                updateTbr(views, R.id.tbr)
                updatePumpBattery(views)
                updateAutoIsf(views)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    /** Scale text sizes proportionally to widget dimensions. */
    private fun computeTextSizes(widthDp: Int, heightDp: Int): TextSizes {
        // Base sizes at 250x110dp (3x2 widget). Scale from there.
        val widthScale = widthDp / 250f
        val heightScale = heightDp / 110f
        val scale = minOf(widthScale, heightScale).coerceIn(0.8f, 2.5f)

        return TextSizes(
            label = (11f * scale).coerceIn(9f, 16f),
            value = (16f * scale).coerceIn(12f, 30f),
            tier = (14f * scale).coerceIn(12f, 24f),
            timeAgo = (11f * scale).coerceIn(9f, 16f)
        )
    }

    private data class TextSizes(val label: Float, val value: Float, val tier: Float, val timeAgo: Float)

    private fun applyTextSizes(views: RemoteViews, s: TextSizes) {
        // Tier + time ago + pump battery
        views.setTextViewTextSize(R.id.tier_label, TypedValue.COMPLEX_UNIT_SP, s.tier)
        views.setTextViewTextSize(R.id.time_ago, TypedValue.COMPLEX_UNIT_SP, s.timeAgo)
        views.setTextViewTextSize(R.id.pump_battery, TypedValue.COMPLEX_UNIT_SP, s.timeAgo)

        // Value fields. R.id.dynisf is intentionally excluded: it now holds two lines (ISF + CR) and is
        // governed by its XML uniform autosize so both lines fit, instead of a single fixed size.
        val valueIds = intArrayOf(R.id.cob, R.id.iob, R.id.activity_mode, R.id.profile_pct, R.id.temp_target)
        for (id in valueIds) {
            views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, s.value)
        }
        // TBR sits in a narrower centre column — render it a touch smaller so it fits
        views.setTextViewTextSize(R.id.tbr, TypedValue.COMPLEX_UNIT_SP, (s.value * 0.85f))

        // Label fields
        val labelIds = intArrayOf(R.id.label_dynisf, R.id.label_cob, R.id.label_iob, R.id.label_activity, R.id.label_profile, R.id.label_target, R.id.label_tbr)
        for (id in labelIds) {
            views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, s.label)
        }
    }

    /** Render BG bobble as a high-res bitmap that fills the left panel. */
    private fun updateBgBobble(views: RemoteViews, context: Context, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        // Scale bobble to ~80% of widget height, min 100dp, max 240dp
        val bobbleDp = (heightDp * 0.8f).coerceIn(100f, 240f)
        val sizePx = (bobbleDp * density).toInt()
        val bgMgdl = lastBgData.lastBg()?.recalculated ?: 0.0
        val bgText = lastBgData.lastBg()?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: "---"
        val isActual = lastBgData.isActualBg()
        val trend = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val (trendAngle, chevronRotation) = BgBobbleView.trendToAngles(trend)
        val units = profileFunction.getUnits()
        val unitsLabel = if (units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"

        // Time ago is a separate TextView, so it must refresh every minute regardless of the cache.
        views.setTextViewText(R.id.time_ago, dateUtil.minOrSecAgo(rh, lastBgData.lastBg()?.timestamp))

        // Reuse the previously drawn bitmap when nothing that affects its pixels has changed.
        val cacheKey = "$sizePx|$bgMgdl|$isActual|$trendAngle|$chevronRotation|$unitsLabel"
        cachedBobble?.let {
            if (cacheKey == cachedBobbleKey) {
                views.setImageViewBitmap(R.id.bg_bobble, it)
                return
            }
        }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val dp = density
        val sz = sizePx.toFloat()
        val cx = sz / 2f
        val cy = sz / 2f
        val sw = 8f * dp
        val r = (sz - sw * 2 - 16 * dp) / 2f
        val br = 14f * dp
        val z = zoneColor(bgMgdl)

        // Ring background
        val ringBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw
            color = Color.argb(15, Color.red(z), Color.green(z), Color.blue(z))
        }
        c.drawCircle(cx, cy, r, ringBgP)

        // Full ring
        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND; color = z
        }
        c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f, false, ringP)

        // Inner fill
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(30, Color.red(z), Color.green(z), Color.blue(z))
        }
        c.drawCircle(cx, cy, r - sw / 2f - 4f * dp, fillP)

        // BG text
        val ir = r - sw / 2f - 4f * dp
        val bgTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
            color = z; textSize = ir * 0.58f
            if (!isActual) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        c.drawText(bgText, cx, cy + bgTxtP.textSize * 0.15f, bgTxtP)

        // Units label
        val unitP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; color = Color.argb(128, 255, 255, 255)
            textSize = ir * 0.16f
        }
        c.drawText(unitsLabel, cx, cy + bgTxtP.textSize * 0.15f + unitP.textSize * 1.8f, unitP)

        // Trend chevron badge on ring
        val rad = Math.toRadians((trendAngle - 90).toDouble())
        val bx = cx + r * cos(rad).toFloat()
        val by = cy + r * sin(rad).toFloat()

        val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#121212") }
        c.drawCircle(bx, by, br, badgeP)
        badgeP.color = Color.parseColor("#1E1E1E")
        c.drawCircle(bx, by, br - 2f * dp, badgeP)
        val badgeBdrP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = z }
        c.drawCircle(bx, by, br - 2f * dp, badgeBdrP)

        val cs = br * 0.45f
        val chevP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * dp; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; color = z
        }
        val path = Path().apply {
            moveTo(-cs * 0.6f, -cs)
            lineTo(cs * 0.6f, 0f)
            lineTo(-cs * 0.6f, cs)
        }
        c.save()
        c.translate(bx, by)
        c.rotate(chevronRotation)
        c.drawPath(path, chevP)
        c.restore()

        cachedBobble = bitmap
        cachedBobbleKey = cacheKey
        views.setImageViewBitmap(R.id.bg_bobble, bitmap)
    }

    private fun updateBoostData(views: RemoteViews) {
        val status = boostOverviewHelper.getBoostStatus()
        val units = profileFunction.getUnits()

        // Tier
        views.setTextViewText(R.id.tier_label, status.tierLabel)
        views.setTextColor(R.id.tier_label, status.tier.colorHex.toInt())

        // DynISF / CR — two labelled lines: calculated dynamic ISF, then the effective carb ratio
        // (Dynamic CR; equals the profile CR when Dynamic CR is off). CR is g/U (unit-independent).
        val dynIsfText = if (status.variableSens > 0) {
            String.format(Locale.getDefault(), "%.1f", profileUtil.fromMgdlToUnits(status.variableSens, units))
        } else "--"
        // Show "--" when Dynamic CR is off (consistent with the ISF line), not the profile CR.
        val crText = if (status.dynamicCrActive && status.carbRatioEffective > 0) String.format(Locale.getDefault(), "%.1f", status.carbRatioEffective) else "--"
        views.setTextViewText(R.id.dynisf, "ISF $dynIsfText\nCR $crText")

        // COB (works on both master and client — derived from carbs in the IobCob calculator)
        val cobText = iobCobCalculator.getCobInfo("Boost widget COB").displayText(rh, decimalFormatter) ?: "--"
        views.setTextViewText(R.id.cob, cobText)

        // Activity mode
        views.setTextViewText(R.id.activity_mode, status.activityDetail)
        val activityColor = when (status.activityMode) {
            BoostOverviewHelper.ActivityMode.ACTIVE   -> Color.parseColor("#42A5F5")
            BoostOverviewHelper.ActivityMode.INACTIVE  -> Color.parseColor("#FF9800")
            BoostOverviewHelper.ActivityMode.SLEEP_IN  -> Color.parseColor("#AB47BC")
            BoostOverviewHelper.ActivityMode.BOOST_OFF -> Color.parseColor("#78909C")
            BoostOverviewHelper.ActivityMode.NORMAL    -> Color.WHITE
        }
        views.setTextColor(R.id.activity_mode, activityColor)

        // Profile percentage — read live from the active profile switch (same source as the
        // standard widget's updateProfile), so a manual profile-% change is reflected
        // immediately instead of waiting for the next APS run that feeds status.profilePercentage.
        val profilePct = (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage ?: status.profilePercentage
        views.setTextViewText(R.id.profile_pct, "$profilePct%")
        if (profilePct != 100) {
            views.setTextColor(R.id.profile_pct, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
        } else {
            views.setTextColor(R.id.profile_pct, Color.WHITE)
        }
    }

    /** Target display — temp target with time remaining, algorithm-adjusted, or profile default. */
    private fun updateTarget(views: RemoteViews, targetViewId: Int) {
        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        if (tempTarget != null) {
            views.setTextColor(targetViewId, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
            views.setTextViewText(
                targetViewId,
                profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh)
            )
        } else {
            profileFunction.getProfile()?.let { profile ->
                val targetUsed = loop.lastRun?.constraintsProcessed?.targetBG
                    ?: boostOverviewHelper.getBoostStatus().targetBgMgdl
                if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                    views.setTextViewText(targetViewId, profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units))
                    views.setTextColor(targetViewId, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
                } else {
                    views.setTextColor(targetViewId, rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault))
                    views.setTextViewText(targetViewId, profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units))
                }
            } ?: run {
                views.setTextViewText(targetViewId, "--")
                views.setTextColor(targetViewId, Color.WHITE)
            }
        }
    }

    private fun updateIob(views: RemoteViews) {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob
        views.setTextViewText(R.id.iob, rh.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIob))
    }

    /** Current temporary basal rate as a percentage of profile basal.
     *  Works on both master and AAPSClient — the TBR is read from the persistence
     *  layer (synced from Nightscout on the client). */
    private fun updateTbr(views: RemoteViews, tbrViewId: Int) {
        val profile = profileFunction.getProfile()
        val tempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())
            ?.takeIf { it.isInProgress }
        val percent = if (profile != null && tempBasal != null) tempBasal.convertedToPercent(dateUtil.now(), profile) else 100
        val usePercentage = preferences.get(BooleanKey.OverviewBasalIsAlwaysNotAbsolute)
        val tbrText = if (usePercentage || profile == null) "$percent%"
        else rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, tempBasal?.convertedToAbsolute(dateUtil.now(), profile) ?: profile.getBasal())
        views.setTextViewText(tbrViewId, tbrText)
        if (percent != 100) {
            views.setTextColor(tbrViewId, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
        } else {
            views.setTextColor(tbrViewId, Color.WHITE)
        }
    }

    /** Battery level shown in the widget.
     *  On the master this is the active pump's battery; on AAPSClient there is no
     *  local pump, so show the master phone's battery synced from Nightscout. */
    private fun updatePumpBattery(views: RemoteViews) {
        val batteryText = if (config.AAPSCLIENT) {
            processedDeviceStatusData.uploaderMap.values
                .minByOrNull { it.battery }
                ?.takeIf { it.battery > 0 }
                ?.let { "${it.battery}%" }
        } else {
            activePlugin.activePump.batteryLevel?.let { "$it%" }
        }
        views.setTextViewText(R.id.pump_battery, "🔋 ${batteryText ?: "---"}")
    }

    /**
     * AutoISF / OpenAPS-AMA / OpenAPS-SMB widget variant. For these algorithms swap the standard
     * 3-row data table for the AutoISF arrangement:
     *   Row 1: IOB | TBR | COB
     *   Row 2: Profile | Target
     *   Row 3 (AutoISF):  BG deltas + acceleration | AutoISF ISF factors
     *   Row 3 (AMA/SMB):  BG deltas (no acceleration) | autosens (AS + direction arrow + Alg)
     * and replace the tier label (left column, under "time ago") with the profile→calculated
     * ISF (ФЧИ) line, so the bobble column shows only time-ago + ISF.
     */
    private fun updateAutoIsf(views: RemoteViews) {
        val algorithm = activePlugin.activeAPS.algorithm
        val autoIsf = algorithm == APSResult.Algorithm.AUTO_ISF
        val amaOrSmb = algorithm == APSResult.Algorithm.AMA || algorithm == APSResult.Algorithm.SMB
        // AMA/SMB reuse the AutoISF panel layout but fill row 3 with autosens data instead.
        val useAutoIsfPanel = autoIsf || amaOrSmb
        views.setViewVisibility(R.id.right_panel_standard, if (useAutoIsfPanel) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.right_panel_autoisf, if (useAutoIsfPanel) View.VISIBLE else View.GONE)
        if (!useAutoIsfPanel) return

        val status = boostOverviewHelper.getBoostStatus()
        val units = profileFunction.getUnits()
        val isfFmt = if (units == GlucoseUnit.MGDL) "%.0f" else "%.1f"
        fun isfStr(mgdl: Double): String =
            if (mgdl > 0) String.format(Locale.getDefault(), isfFmt, profileUtil.fromMgdlToUnits(mgdl, units)) else "--"
        fun factor(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
        fun pct(ratio: Double): String = String.format(Locale.getDefault(), "%.0f%%", ratio * 100)

        // Row 1 col 1: IOB
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob
        views.setTextViewText(R.id.ai_iob, rh.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIob))

        // Row 1 col 2/3: TBR + COB
        updateTbr(views, R.id.ai_tbr)
        val cobText = iobCobCalculator.getCobInfo("Boost widget COB autoISF").displayText(rh, decimalFormatter) ?: "--"
        views.setTextViewText(R.id.ai_cob, cobText)

        // Row 2: Profile | Target — profile % read live from the active profile switch (same
        // source as the standard widget) so manual changes are reflected immediately.
        val aiProfilePct = (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage ?: status.profilePercentage
        views.setTextViewText(R.id.ai_profile, "$aiProfilePct%")
        views.setTextColor(
            R.id.ai_profile,
            if (aiProfilePct != 100) rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning) else Color.WHITE
        )
        updateTarget(views, R.id.ai_target)

        // Row 3 col 1: BG deltas, plus the acceleration line only for AutoISF.
        val deltas = StringBuilder()
            .append("Δ ${profileUtil.fromMgdlToSignedStringInUnits(status.deltaMgdl)}\n")
            .append("Δ15 ${profileUtil.fromMgdlToSignedStringInUnits(status.shortAvgDeltaMgdl)}\n")
            .append("Δ40 ${profileUtil.fromMgdlToSignedStringInUnits(status.longAvgDeltaMgdl)}")
        if (autoIsf) deltas.append("\na: ${String.format(Locale.ENGLISH, "%.1f", status.bgAcceleration)}")
        views.setTextViewText(R.id.ai_deltas, deltas.toString())

        // Row 3 col 2 (label + content): AutoISF intermediate factors, or for AMA/SMB the autosens
        // block. The left "Deltas" label is static in the layout. (RemoteViews are rebuilt from the
        // layout on every update, so the label is reset each time before this override.)
        views.setTextViewText(R.id.label_ai_factors, if (autoIsf) "Factors" else "Autosens")
        if (autoIsf) {
            views.setTextViewText(
                R.id.ai_isf_factors,
                "Accel ${factor(status.acceIsf)}\n" +
                    "BG ${factor(status.bgIsf)}\n" +
                    "PP ${factor(status.ppIsf)}\n" +
                    "Dura ${factor(status.duraIsf)}\n" +
                    "Final ${factor(status.finalIsf)}"
            )
        } else {
            // AMA/SMB: same autosens info as the old widget's bottom-right block, minus the ISF
            // change (shown under the bobble). The "Autosens" label replaces the inner AS caption.
            // AS = live autosens ratio with a direction arrow; Alg = the ratio the APS actually
            // used, only when it differs from autosens.
            val asRatio = iobCobCalculator.ads.getLastAutosensData("BoostWidget AS", aapsLogger, dateUtil)
                ?.autosensResult?.ratio ?: 1.0
            val algRatio = status.autosensRatio
            val arrow = when {
                asRatio > 1.0 -> "↑"
                asRatio < 1.0 -> "↓"
                else          -> "→"
            }
            val sb = StringBuilder("$arrow ${pct(asRatio)}")
            if (algRatio != 1.0 && algRatio != asRatio) sb.append("\nAlg ${pct(algRatio)}")
            views.setTextViewText(R.id.ai_isf_factors, sb.toString())
        }

        // Tier-label position → two left-aligned lines under the BG bobble: ISF (profile→calculated) and
        // CR (profile→effective Dynamic CR). Each collapses to just the profile value when its dynamic
        // adjustment is off/equal (no dynamic ISF, or Dynamic CR disabled). CR is g/U (unit-independent).
        fun crStr(v: Double): String = if (v > 0) String.format(Locale.getDefault(), "%.1f", v) else "--"
        val isfLine = if (autoIsf || (status.variableSens > 0 && status.variableSens != status.isfProfileMgdl))
            "ISF ${isfStr(status.isfProfileMgdl)}→${isfStr(status.variableSens)}"
        else
            "ISF ${isfStr(status.isfProfileMgdl)}"
        val crLine = if (status.carbRatioEffective > 0 && status.carbRatioProfile > 0 && status.carbRatioEffective != status.carbRatioProfile)
            "CR ${crStr(status.carbRatioProfile)}→${crStr(status.carbRatioEffective)}"
        else
            "CR ${crStr(status.carbRatioProfile)}"
        views.setTextViewText(R.id.tier_label, "$isfLine\n$crLine")
        views.setInt(R.id.tier_label, "setGravity", Gravity.START)
        views.setTextColor(R.id.tier_label, Color.WHITE)
    }
}
