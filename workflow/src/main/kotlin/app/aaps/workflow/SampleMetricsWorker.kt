package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.metrics.MetricsSampler
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Samples step count and heart rate on demand and persists them, so the values are refreshed only
 * when the rest of the loop metrics are recomputed (i.e. on a new BG value). Runs early in the
 * calculation chain so the fresh values are available both to the overview graph and to the loop.
 */
class SampleMetricsWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var metricsSampler: MetricsSampler

    override suspend fun doWorkAndLog(): Result {
        metricsSampler.sampleNow()
        return Result.success()
    }
}
