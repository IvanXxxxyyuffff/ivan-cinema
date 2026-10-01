package com.ivan.cinema.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements

@UnstableApi
class IVANDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    0,
    0
) {

    override fun getDownloadManager(): DownloadManager {
        DownloadCenter.ensureInit(this)
        return DownloadCenter.managerRef!!
    }

    override fun getScheduler(): androidx.media3.exoplayer.scheduler.Scheduler? {
        return null
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                null,
                "IVAN CINEMA 正在下载",
                downloads,
                notMetRequirements
            )
    }

    companion object {
        private const val CHANNEL_ID = "ivan_download"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return super.onStartCommand(intent, flags, startId)
    }
}
