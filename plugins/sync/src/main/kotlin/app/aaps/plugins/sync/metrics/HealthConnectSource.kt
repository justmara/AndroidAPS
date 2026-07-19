package app.aaps.plugins.sync.metrics

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, defensive wrapper around the Health Connect client.
 *
 * Every public method returns null when Health Connect is unavailable, not installed, or the
 * required read permission has not been granted, so callers can transparently fall back to another
 * source. All Health Connect APIs are confined to this class.
 */
@Singleton
class HealthConnectSource @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    private val client: HealthConnectClient? by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
                HealthConnectClient.getOrCreate(context)
            else {
                aapsLogger.info(LTag.CORE, "Health Connect SDK not available")
                null
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Cannot create Health Connect client", e)
            null
        }
    }

    fun isAvailable(): Boolean = client != null

    private suspend fun isGranted(permission: String): Boolean =
        try {
            client?.permissionController?.getGrantedPermissions()?.contains(permission) == true
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Health Connect permission check failed", e)
            false
        }

    /**
     * Returns the total number of steps in each window (length in minutes) ending at [now], or null
     * if Health Connect is unavailable or the steps read permission is missing.
     */
    fun readSteps(now: Instant, windowsMin: List<Long>): Map<Long, Int>? {
        val c = client ?: return null
        val permission = HealthPermission.getReadPermission(StepsRecord::class)
        return try {
            runBlocking {
                if (!isGranted(permission)) return@runBlocking null
                windowsMin.associateWith { minutes ->
                    val result: AggregationResult = c.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(minutes * 60), now)
                        )
                    )
                    (result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Health Connect steps read failed", e)
            null
        }
    }

    /**
     * Returns the average heart rate (BPM) over the [windowMin]-minute window ending at [now], or
     * null if unavailable, not permitted, or there were no samples.
     */
    fun readAverageHeartRate(now: Instant, windowMin: Long): Int? {
        val c = client ?: return null
        val permission = HealthPermission.getReadPermission(HeartRateRecord::class)
        return try {
            runBlocking {
                if (!isGranted(permission)) return@runBlocking null
                val result: AggregationResult = c.aggregate(
                    AggregateRequest(
                        metrics = setOf(HeartRateRecord.BPM_AVG),
                        timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(windowMin * 60), now)
                    )
                )
                result[HeartRateRecord.BPM_AVG]?.toInt()
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Health Connect heart rate read failed", e)
            null
        }
    }
}
