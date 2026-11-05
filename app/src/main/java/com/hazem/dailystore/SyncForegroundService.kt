package com.hazem.dailystore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import com.hazem.dailystore.data.SyncProtocolRepository
import com.hazem.dailystore.data.SyncRelaySession

/**
 * Keeps the authenticated relay session alive while Android allows a visible
 * foreground operation. The durable SQLite outbox remains the source of truth.
 */
class SyncForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val endpoint = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(ENDPOINT_KEY, null)
        if (endpoint.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startVisibleForeground()
        try {
            val repository = SyncProtocolRepository(applicationContext)
            check(repository.localDeviceRole() != null) { "دور جهاز المزامنة غير مهيأ" }
            SyncRelaySession.connect(applicationContext, endpoint, repository)
        } catch (_: Exception) {
            // Keep the foreground service alive; the runtime retries transport
            // failures and the outbox is never cleared by relay delivery notices.
        }
        return START_STICKY
    }

    private fun startVisibleForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("مزامنة The Daily Shop")
            .setContentText("خدمة المزامنة تعمل؛ تبقى العمليات معلقة حتى إقرار الجهاز الأساسي")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "مزامنة المتجر",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إبقاء اتصال المزامنة المشفر بين أجهزة المتجر"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        SyncRelaySession.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.hazem.dailystore.sync.START"
        private const val PREFS = "daily_shop_sync_relay"
        private const val ENDPOINT_KEY = "relay_wss_url"
        private const val CHANNEL_ID = "daily_shop_sync"
        private const val NOTIFICATION_ID = 7401

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
