package com.gsvn.aabrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * A lightweight foreground service that keeps the app process alive
 * while audio/video continues playing in the WebView background.
 *
 * This service does NOT handle media playback itself — it only prevents
 * the OS from killing the process when the Activity goes to the background.
 */
class BackgroundPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBroadcast(Intent(ACTION_STOP_PLAYBACK))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps audio playing when the app is in the background"
            setShowBadge(false)
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tap notification → open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AABrowser")
            .setContentText("Playing in background")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AABrowser::BackgroundPlayback"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "background_playback"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "com.gsvn.aabrowser.STOP_BG_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "com.gsvn.aabrowser.STOP_PLAYBACK_BROADCAST"

        fun start(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
            context.stopService(intent)
        }
    }
}
