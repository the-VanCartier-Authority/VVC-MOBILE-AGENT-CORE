package com.vancartier.vvcmobileagentcore

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.vancartier.vvcmobileagentcore.databinding.ActivityMainBinding
import com.vancartier.vvcmobileagentcore.edge.VvcEdgeModelManager
import com.vancartier.vvcmobileagentcore.notification.VvcNotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var notificationScheduler: VvcNotificationScheduler
    private lateinit var edgeModelManager: VvcEdgeModelManager
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notificationScheduler = VvcNotificationScheduler(this)
        notificationScheduler.ensureAnomalyChannel()
        requestNotificationPermissionIfNeeded()

        edgeModelManager = VvcEdgeModelManager(
            context = this,
            notificationScheduler = notificationScheduler
        )
        binding.statusTextView.setText(R.string.status_loading)
        observeModelInitialization()
    }

    override fun onDestroy() {
        edgeModelManager.close()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun observeModelInitialization() {
        activityScope.launch {
            val report = withContext(Dispatchers.Default) {
                edgeModelManager.awaitReady()
                edgeModelManager.loadedModelReport()
            }
            binding.statusTextView.setText(R.string.status_ready)
            binding.auditLogTextView.text = buildString {
                appendLine("EDGE_GALLERY://LOCAL_ONLY")
                appendLine("AUDIO_SCRIBE: READY")
                appendLine("ASK_IMAGE: READY")
                appendLine("MOBILE_ACTIONS: READY")
                appendLine("HASH_GUARD: ACTIVE")
                appendLine("ANOMALY_NOTIFIER: ACTIVE")
                append(report)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
        }
    }

    companion object {
        private const val REQUEST_CODE_NOTIFICATIONS = 4300
    }
}
