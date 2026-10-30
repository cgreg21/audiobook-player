package org.nativescript.audiobookplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class DownloadForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var queue: PodcastDownloadQueue

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
        queue = PodcastDownloadQueue.shared(
            this,
            serviceAlreadyStarted = true,
        )
        val downloads = queue.items()
        if (downloads.none(::isActive)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } else {
            updateNotification(downloads)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (::queue.isInitialized) queue.pauseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun updateNotification(downloads: List<PodcastDownload>) {
        if (downloads.none(::isActive)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(downloads))
    }

    private fun buildNotification(downloads: List<PodcastDownload>): Notification {
        val active = downloads.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val pendingCount = downloads.count(::isActive)
        val title = active?.title ?: "Téléchargements en attente"
        val text = if (active == null) {
            "$pendingCount épisode(s) dans la file"
        } else {
            val percentage = if (active.total > 0) {
                "${(active.done * 100 / active.total).coerceIn(0, 100)} %"
            } else {
                "Téléchargement en cours"
            }
            "$percentage · $pendingCount épisode(s) dans la file"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (active != null && active.total > 0) {
            builder.setProgress(
                100,
                (active.done * 100 / active.total).toInt().coerceIn(0, 100),
                false,
            )
        } else if (active != null) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Téléchargements",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "podcast_downloads"
        private const val NOTIFICATION_ID = 4203

        @Volatile
        private var activeService: DownloadForegroundService? = null

        fun intent(context: Context): Intent =
            Intent(context, DownloadForegroundService::class.java)

        fun updateIfRunning(downloads: List<PodcastDownload>): Boolean {
            val service = activeService ?: return false
            service.handler.post { service.updateNotification(downloads) }
            return true
        }

        private fun isActive(download: PodcastDownload) =
            download.status == DownloadStatus.QUEUED ||
                download.status == DownloadStatus.DOWNLOADING
    }
}
