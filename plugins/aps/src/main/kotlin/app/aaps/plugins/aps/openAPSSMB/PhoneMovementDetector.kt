package app.aaps.plugins.aps.openAPSSMB

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * Detects whether the phone has moved within the last 15 minutes. Used by AutoISF activity
 * detection ([phoneMoved]).
 *
 * Battery: registers the accelerometer at 1 Hz with a 2-minute FIFO batch (maxReportLatency) so the
 * hardware buffers samples while the CPU sleeps — instead of the previous always-on
 * SENSOR_DELAY_NORMAL (~5 Hz, no batching) stream that kept the application processor awake
 * continuously. The accelerometer is the primary sensor because it keeps [lastUpdateTimestamp]
 * fresh continuously while the phone is in motion, preserving the original "moved in the last
 * 15 min" semantics. On the (rare) device with no accelerometer it falls back to the hardware
 * [Sensor.TYPE_SIGNIFICANT_MOTION] one-shot trigger.
 *
 * Neither sensor requires the ACTIVITY_RECOGNITION permission.
 *
 * Call [register] once when the consuming plugin is active; [unregister] releases the sensor.
 */
object PhoneMovementDetector {

    private const val MOVEMENT_THRESHOLD: Double = 1.05
    private const val WINDOW_MS: Long = 15 * 60000

    // 1 Hz is far more than enough for a 15-minute window, and a 2-minute FIFO batch lets the
    // hardware buffer samples while the CPU sleeps.
    private const val ACCEL_SAMPLING_PERIOD_US = 1_000_000
    private const val ACCEL_MAX_REPORT_LATENCY_US = 2 * 60 * 1000 * 1000

    // Written on the sensor delivery thread, read on the APS calculation thread.
    @Volatile private var lastUpdateTimestamp: Long = 0
    private var sensorManager: SensorManager? = null
    private var significantMotion: Sensor? = null

    private val accelerometerListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(sensorEvent: SensorEvent?) {
            sensorEvent ?: return
            val x = sensorEvent.values[0]
            val y = sensorEvent.values[1]
            val z = sensorEvent.values[2]
            val acceleration = (x * x + y * y + z * z) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH)
            if (acceleration > MOVEMENT_THRESHOLD) lastUpdateTimestamp = System.currentTimeMillis()
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            lastUpdateTimestamp = System.currentTimeMillis()
            // Significant-motion auto-disarms after firing; re-arm to keep detecting.
            significantMotion?.let { sensor -> sensorManager?.requestTriggerSensor(this, sensor) }
        }
    }

    /** Registers the lowest-power available movement sensor. Returns true if a sensor was registered. */
    fun register(sm: SensorManager): Boolean {
        sensorManager = sm
        // Primary: batched accelerometer — continuous "is it moving" semantics, CPU stays asleep
        // between FIFO flushes.
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
            return sm.registerListener(accelerometerListener, accelerometer, ACCEL_SAMPLING_PERIOD_US, ACCEL_MAX_REPORT_LATENCY_US)
        }
        // Last resort for devices without an accelerometer: significant-motion trigger.
        sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensor ->
            significantMotion = sensor
            return sm.requestTriggerSensor(triggerListener, sensor)
        }
        return false
    }

    // Not called yet: sensors are currently registered for the whole app lifetime in
    // MainApp.registerActivitySensors(). Kept as the hook for the planned move to per-plugin
    // lifecycle registration (register in the consuming plugin's onStart, unregister in onStop).
    fun unregister() {
        sensorManager?.unregisterListener(accelerometerListener)
        significantMotion?.let { sensor -> sensorManager?.cancelTriggerSensor(triggerListener, sensor) }
        significantMotion = null
        sensorManager = null
    }

    fun phoneMoved(): Boolean = (lastUpdateTimestamp + WINDOW_MS) > System.currentTimeMillis()
}
