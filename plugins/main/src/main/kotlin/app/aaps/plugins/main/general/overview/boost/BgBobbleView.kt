package app.aaps.plugins.main.general.overview.boost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.resources.ResourceHelper
import kotlin.math.cos
import kotlin.math.sin

/**
 * BgBobbleView
 *
 * Custom View drawing the Boost overview BG "bobble":
 *   - A full outer ring coloured by the CURRENT glucose zone
 *   - A filled circle with the BG value and unit label
 *   - A trend chevron badge ON the ring that moves around the
 *     circumference based on glucose trend direction
 *
 * Ring colour uses the standard Time-In-Range colour scheme applied
 * to the current glucose reading (NOT a TIR percentage arc):
 *
 *   >250 mg/dL  (>13.9 mmol/L)    Very High -> urgentColor   (red)
 *   181-250     (10.1-13.9)        High      -> highColor     (yellow)
 *   70-180      (3.9-10.0)         In Range  -> bgInRange     (green)
 *   54-69       (3.0-3.8)          Low       -> bgLow         (red/orange)
 *   <54         (<3.0)             Very Low  -> urgentColor   (dark red)
 *
 * All colours resolve from the app theme so they adapt to
 * light/dark mode automatically.
 *
 * Trend chevron position (degrees, 0 deg = 12 o'clock, evenly spaced 30 deg):
 *   DOUBLE_UP       ->   0 deg  chevron pointing up
 *   SINGLE_UP       ->  30 deg  chevron pointing up-right
 *   FORTY_FIVE_UP   ->  60 deg  chevron pointing up-right
 *   FLAT            ->  90 deg  chevron pointing right
 *   FORTY_FIVE_DOWN -> 120 deg  chevron pointing down-right
 *   SINGLE_DOWN     -> 150 deg  chevron pointing down-right
 *   DOUBLE_DOWN     -> 180 deg  chevron pointing down
 */
class BgBobbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Public properties ---

    /** Current BG in mg/dL — determines ring and fill zone colour */
    var bgValueMgdl: Double = 0.0
        set(v) { field = v; invalidate() }

    /** Formatted BG string shown inside the bobble (e.g. "6.2" or "112") */
    var bgDisplayText: String = "---"
        set(v) { field = v; invalidate() }

    /** Units label shown below the BG text (e.g. "mmol/L" or "mg/dL") */
    var unitsLabel: String = "mg/dL"
        set(v) { field = v; invalidate() }

    /** Position of the chevron badge on the ring, in degrees from 12 o'clock */
    var trendAngleDeg: Float = 90f
        set(v) { field = v; invalidate() }

    /** Rotation of the chevron arrow inside its badge */
    var chevronRotateDeg: Float = 0f
        set(v) { field = v; invalidate() }

    /** False when the BG reading is stale — draws strike-through text */
    var isActualBg: Boolean = true
        set(v) { field = v; invalidate() }

    // --- Theme colours (resolved at runtime) ---

    private var cVeryHigh = Color.RED
    private var cHigh = Color.YELLOW
    private var cInRange = Color.GREEN
    private var cLow = Color.RED
    private var cVeryLow = Color.RED
    private var cSurface = Color.parseColor("#121212")
    private var cSurfaceVar = Color.parseColor("#1E1E1E")
    private var cTextPrimary = Color.WHITE
    private var cTextSec = Color.parseColor("#80FFFFFF")

    /**
     * Resolve all colours from the current theme.
     * Call from Fragment.onViewCreated so we pick up light/dark mode.
     */
    fun resolveThemeColors(rh: ResourceHelper) {
        cVeryHigh = rh.gac(context, app.aaps.core.ui.R.attr.urgentColor)
        cHigh = rh.gac(context, app.aaps.core.ui.R.attr.highColor)
        cInRange = rh.gac(context, app.aaps.core.ui.R.attr.bgInRange)
        cLow = rh.gac(context, app.aaps.core.ui.R.attr.bgLow)
        cVeryLow = rh.gac(context, app.aaps.core.ui.R.attr.urgentColor)
        val ta = context.obtainStyledAttributes(intArrayOf(
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorSurfaceVariant,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorSecondary
        ))
        cSurface = ta.getColor(0, cSurface)
        cSurfaceVar = ta.getColor(1, cSurfaceVar)
        cTextPrimary = ta.getColor(2, cTextPrimary)
        cTextSec = ta.getColor(3, cTextSec)
        ta.recycle()
        invalidate()
    }

    /**
     * Returns the zone colour for the current bgValueMgdl.
     * Thresholds match the international TIR consensus:
     *   Very High  >250  (>13.9 mmol/L)
     *   High       >180  (>10.0 mmol/L)
     *   In Range   70-180 (3.9-10.0 mmol/L)
     *   Low        54-69  (3.0-3.8 mmol/L)
     *   Very Low   <54    (<3.0 mmol/L)
     */
    private fun zoneColor(): Int = when {
        bgValueMgdl > 250 -> cVeryHigh
        bgValueMgdl > 180 -> cHigh
        bgValueMgdl >= 70 -> cInRange
        bgValueMgdl >= 54 -> cLow
        bgValueMgdl > 0   -> cVeryLow
        else               -> cInRange   // No reading yet — default green
    }

    // --- Paints ---

    private val ringBgP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgTxtP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val unitP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgeP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeBdrP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val chevP    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        // Default size 160dp; constrain to parent
        val d = (160 * resources.displayMetrics.density).toInt()
        val s = minOf(resolveSize(d, w), resolveSize(d, h))
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val dp = resources.displayMetrics.density
        val sz = width.toFloat()
        val cx = sz / 2f
        val cy = sz / 2f
        val sw = 8f * dp                           // Ring stroke width
        val r = (sz - sw * 2 - 16 * dp) / 2f      // Ring radius
        val br = 14f * dp                           // Chevron badge radius
        val z = zoneColor()

        // 1) Ring background — faint zone colour full circle
        ringBgP.strokeWidth = sw
        ringBgP.color = Color.argb(15, Color.red(z), Color.green(z), Color.blue(z))
        c.drawCircle(cx, cy, r, ringBgP)

        // 2) Full ring — solid zone colour, full 360 degrees
        ringP.strokeWidth = sw
        ringP.color = z
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, -90f, 360f, false, ringP)

        // 3) Inner filled circle — zone colour at low alpha
        fillP.color = Color.argb(30, Color.red(z), Color.green(z), Color.blue(z))
        c.drawCircle(cx, cy, r - sw / 2f - 4f * dp, fillP)

        // 4) BG text — large, centred, zone-coloured
        val ir = r - sw / 2f - 4f * dp
        bgTxtP.color = z
        bgTxtP.textSize = ir * 0.58f
        bgTxtP.flags = if (!isActualBg) bgTxtP.flags or Paint.STRIKE_THRU_TEXT_FLAG
        else bgTxtP.flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        c.drawText(bgDisplayText, cx, cy + bgTxtP.textSize * 0.15f, bgTxtP)

        // 5) Units label — small, secondary colour
        unitP.textSize = ir * 0.16f
        unitP.color = cTextSec
        c.drawText(unitsLabel, cx, cy + bgTxtP.textSize * 0.15f + unitP.textSize * 1.8f, unitP)

        // 6) Trend chevron badge — positioned on ring circumference
        val rad = Math.toRadians((trendAngleDeg - 90).toDouble())
        val bx = cx + r * cos(rad).toFloat()
        val by = cy + r * sin(rad).toFloat()

        // Badge background (surface colour) with zone-coloured border
        badgeP.color = cSurface
        c.drawCircle(bx, by, br, badgeP)
        badgeP.color = cSurfaceVar
        c.drawCircle(bx, by, br - 2f * dp, badgeP)
        badgeBdrP.color = z
        badgeBdrP.strokeWidth = 2.5f * dp
        c.drawCircle(bx, by, br - 2f * dp, badgeBdrP)

        // Chevron ">" arrow, rotated to point in trend direction
        val cs = br * 0.45f
        chevP.color = z
        chevP.strokeWidth = 3f * dp
        path.reset()
        path.moveTo(-cs * 0.6f, -cs)
        path.lineTo(cs * 0.6f, 0f)
        path.lineTo(-cs * 0.6f, cs)
        c.save()
        c.translate(bx, by)
        c.rotate(chevronRotateDeg)
        c.drawPath(path, chevP)
        c.restore()
    }

    companion object {

        /**
         * Convert a TrendArrow enum to (ringAngle, chevronRotation).
         * Ring angle places the badge on the circumference (0 deg = 12 o'clock).
         * Chevron rotation points the ">" arrow in the trend direction.
         * Angles are evenly spaced at 30 degree intervals.
         */
        fun trendToAngles(trend: TrendArrow?): Pair<Float, Float> = when (trend) {
            TrendArrow.DOUBLE_UP       -> 0f to -90f
            TrendArrow.TRIPLE_UP       -> 0f to -90f
            TrendArrow.SINGLE_UP       -> 30f to -60f
            TrendArrow.FORTY_FIVE_UP   -> 60f to -30f
            TrendArrow.FLAT            -> 90f to 0f
            TrendArrow.FORTY_FIVE_DOWN -> 120f to 30f
            TrendArrow.SINGLE_DOWN     -> 150f to 60f
            TrendArrow.DOUBLE_DOWN     -> 180f to 90f
            TrendArrow.TRIPLE_DOWN     -> 180f to 90f
            else                       -> 90f to 0f   // NONE / unknown -> flat
        }
    }
}
