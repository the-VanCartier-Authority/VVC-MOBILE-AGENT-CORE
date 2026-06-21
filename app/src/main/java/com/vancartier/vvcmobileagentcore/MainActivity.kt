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
import kotlinx.coroutines.delay
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
        
        // Show loading status immediately
        binding.statusTextView.setText(R.string.status_loading)
        binding.auditLogTextView.text = "Inicializando catálogo de modelos...\n(No se cargan en memoria hasta usarse)"
        
        // Start background initialization
        initializeModelsAsync()
    }

    override fun onDestroy() {
        edgeModelManager.close()
        activityScope.cancel()
        super.onDestroy()
    }

    /**
     * Initialize models in background without blocking UI.
     * The models are now cataloged quickly, and actual loading happens on-demand.
     */
    private fun initializeModelsAsync() {
        activityScope.launch {
            try {
                // Wait for catalog initialization (fast: ~100-500ms)
                withContext(Dispatchers.Default) {
                    edgeModelManager.awaitReady()
                }
                
                // Update UI on main thread
                updateUIWithModelReport()
            } catch (e: Exception) {
                binding.statusTextView.setText(R.string.status_error)
                binding.auditLogTextView.text = "Error durante inicialización: ${e.message}"
            }
        }
    }

    /**
     * Update the UI with the model status report.
     */
    private fun updateUIWithModelReport() {
        binding.statusTextView.setText(R.string.status_ready)
        val report = edgeModelManager.loadedModelReport()
        
        binding.auditLogTextView.text = buildString {
            appendLine("EDGE_GALLERY://LOCAL_ONLY")
            appendLine("AUDIO_SCRIBE: READY")
            appendLine("ASK_IMAGE: READY")
            appendLine("MOBILE_ACTIONS: READY")
            appendLine("HASH_GUARD: ACTIVE")
            appendLine("ANOMALY_NOTIFIER: ACTIVE")
            appendLine("")
            appendLine("═══════════════════════════")
            appendLine("LAZY LOADING ENABLED")
            appendLine("Modelos se cargan bajo demanda")
            appendLine("═══════════════════════════")
            appendLine("")
            append(report)
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
