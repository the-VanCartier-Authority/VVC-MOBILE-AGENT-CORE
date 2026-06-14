package com.vancartier.vvcmobileagentcore.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.vancartier.vvcmobileagentcore.MainActivity
import com.vancartier.vvcmobileagentcore.R
import java.util.concurrent.atomic.AtomicInteger

class VvcNotificationScheduler(private val context: Context) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val notificationIds = AtomicInteger(BASE_NOTIFICATION_ID)

    fun ensureAnomalyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun scheduleModelAnomalyAlert(title: String, message: String) {
        ensureAnomalyChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = createNotification(title, message)
        notificationManager.notify(notificationIds.incrementAndGet(), notification)
    }

    private fun createNotification(title: String, message: String): Notification {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            Notification.Builder(applicationContext)
        }

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vvc_model_anomaly_channel"
        private const val CHANNEL_NAME = "Alertas de modelos VVC"
        private const val CHANNEL_DESCRIPTION = "Alertas locales cuando un modelo offline detecta una anomalía o falla de integridad."
        private const val REQUEST_CODE_OPEN_APP = 4100
        private const val BASE_NOTIFICATION_ID = 7000
    }
}
