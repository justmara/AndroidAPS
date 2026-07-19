package app.aaps.plugins.sync.metrics

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import app.aaps.plugins.sync.R

/**
 * Tiny, theme-less activity that asks the user to grant the Health Connect read permissions used by
 * [MetricsSamplerImpl] (steps and heart rate). It also serves as the mandatory Health Connect
 * "permissions rationale" target declared in the manifest.
 */
class HealthConnectPermissionActivity : AppCompatActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val ok = granted.containsAll(permissions)
            Toast.makeText(
                this,
                if (ok) R.string.health_connect_granted else R.string.health_connect_not_granted,
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            Toast.makeText(this, R.string.health_connect_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        runCatching { requestPermissions.launch(permissions) }
            .onFailure {
                Toast.makeText(this, R.string.health_connect_unavailable, Toast.LENGTH_LONG).show()
                finish()
            }
    }
}
