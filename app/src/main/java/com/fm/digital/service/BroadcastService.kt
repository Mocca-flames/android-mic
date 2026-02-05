package com.fm.digital.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.fm.digital.R

class BroadcastService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    var onServiceDestroyed: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BroadcastService = this@BroadcastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Connecting to server...")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY // Auto-restart if killed by system
    }

    fun updateNotification(status: String, isStreaming: Boolean = false) {
        val notification = createNotification(status, isStreaming)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(status: String, isStreaming: Boolean = false): Notification {
        val intent = Intent(this, com.fm.digital.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = if (isStreaming) R.drawable.ic_mic_on else R.drawable.ic_mic_off
        val color = if (isStreaming) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FM Digital Broadcaster")
            .setContentText(status)
            .setSmallIcon(icon)
            .setColor(color)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Broadcasting Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps microphone active during live broadcast"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FMDigital::BroadcastWakeLock"
        ).apply {
            acquire(3 * 60 * 60 * 1000L) // 3 hours max
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        onServiceDestroyed?.invoke()
    }

    companion object {
        private const val CHANNEL_ID = "broadcast_channel"
        private const val NOTIFICATION_ID = 1001
    }
}