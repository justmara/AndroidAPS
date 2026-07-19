package app.aaps.plugins.sync.metrics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot reader of the phone's hardware step counter ([Sensor.TYPE_STEP_COUNTER]).
 *
 * Instead of keeping a listener registered continuously, it registers briefly, reads the single
 * current cumulative value (the step counter is an on-change sensor and reports its last value on
 * registration), then unregisters. Used only as the last-resort fallback by [MetricsSamplerImpl].
 */
@Singleton
class PhoneStepSensor @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    /** Current cumulative step count since boot, or null on timeout / when no sensor is present. */
    fun readCumulativeSteps(timeoutMs: Long = 3000): Long? {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        var value: Long? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val raw = event?.values?.getOrNull(0) ?: return
                // Guard against a garbage reading (NaN/Inf turns into 0 or Long.MAX on toLong()); a
                // large-but-finite bogus value still passes here and is rejected by the delta bound.
                if (raw.isFinite() && raw >= 0f) {
                    value = raw.toLong()
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        return try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
                aapsLogger.debug(LTag.CORE, "Phone step counter read timed out")
            value
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Phone step counter read failed", e)
            null
        } finally {
            runCatching { sensorManager.unregisterListener(listener) }
        }
    }
}
