package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.resources.ResourceHelper

class StepsDataPoint(
    private val data: SC,
    private val rh: ResourceHelper,
    private val scale: Scale = Scale(),
) : DataPointWithLabelInterface {

    override fun getX(): Double = data.timestamp.toDouble()
    // Scaled so the series can share a graph with another series (e.g. heart rate).
    // When steps drive the scale the multiplier is 1.0 / shift 0.0, i.e. identity.
    override fun getY(): Double = scale.transform(data.steps5min.toDouble())
    override fun setY(y: Double) {}

    override val label: String = ""
    override val duration = 300000L
    override val shape = Shape.STEPS
    override val size = 10f
    override val paintStyle: Paint.Style = Paint.Style.FILL

    override fun color(context: Context?): Int = rh.gac(context, app.aaps.core.ui.R.attr.stepsColor)
}
