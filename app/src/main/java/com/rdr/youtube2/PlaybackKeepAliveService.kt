package com.rdr.youtube2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PlaybackKeepAliveService : Service() {

    private var nowPlayingVideoId: String = ""
    private var nowPlayingTitle: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                nowPlayingVideoId = intent?.getStringExtra(VideoDetailActivity.EXTRA_VIDEO_ID).orEmpty()
                nowPlayingTitle = intent?.getStringExtra(VideoDetailActivity.EXTRA_TITLE).orEmpty()
                ensureNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = if (nowPlayingVideoId.isBlank()) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, VideoDetailActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(VideoDetailActivity.EXTRA_VIDEO_ID, nowPlayingVideoId)
                putExtra(VideoDetailActivity.EXTRA_TITLE, nowPlayingTitle)
                putExtra(VideoDetailActivity.EXTRA_SECTION, "Background")
            }
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.player_background_title))
            .setContentText(
                if (nowPlayingTitle.isBlank()) {
                    getString(R.string.player_background_text)
                } else {
                    nowPlayingTitle
                }
            )
            .setSmallIcon(R.drawable.icon)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.player_background_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.player_background_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.rdr.youtube2.action.START_BACKGROUND_PLAYBACK"
        const val ACTION_STOP = "com.rdr.youtube2.action.STOP_BACKGROUND_PLAYBACK"
        private const val CHANNEL_ID = "youtube2_background_playback"
        private const val NOTIFICATION_ID = 2204
    }
}
