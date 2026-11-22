package com.emptysol.aeroplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.emptysol.aeroplayer.MainActivity
import com.emptysol.aeroplayer.R
import com.emptysol.aeroplayer.data.database.AppDatabase
import com.emptysol.aeroplayer.torrent.TorrentEngineHolder
import kotlinx.coroutines.*

/**
 * Android 14+ compliant `dataSync` Foreground Service that runs P2P BitTorrent transfers
 * reliably in the background with live progress, speed, folder info, and Pause/Resume/Stop controls.
 */
class TorrentDownloadService : Service() {

    private val scopeExceptionHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.w("TorrentDownloadService", "Service coroutine handled error: ${t.message}")
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scopeExceptionHandler)
    private var monitorJob: Job? = null
    private var lastCompletedAnnouncedCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        TorrentEngineHolder.getOrCreate(applicationContext)
        promoteForeground(
            buildActiveNotification(
                activeCount = 1,
                title = "Starting background downloader...",
                folderLabel = "AeroTorrents",
                progressPct = 0,
                dlKbps = 0,
                ulKbps = 0
            )
        )
        startMonitorLoop()
    }

    private fun promoteForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("TorrentDownloadService", "Foreground promotion skipped: ${t.message}")
            stopSelf()
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    try {
                        TorrentEngineHolder.getOrCreate(applicationContext).pauseAllTorrents()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                    withContext(Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE_ALL -> {
                scope.launch {
                    try {
                        TorrentEngineHolder.getOrCreate(applicationContext).pauseAllTorrents()
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val tasks = AppDatabase.getDatabase(applicationContext).torrentDao().getAllTorrentsDirect()
                        val pausedCount = tasks.count { it.status in setOf("PAUSED", "VPN_PAUSED", "SCHEDULE_PAUSED") }
                        if (pausedCount > 0) {
                            nm.notify(
                                PAUSED_NOTIFICATION_ID,
                                buildPausedNotification(pausedCount)
                            )
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
            }
            ACTION_RESUME_ALL -> {
                scope.launch {
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(PAUSED_NOTIFICATION_ID)
                        TorrentEngineHolder.getOrCreate(applicationContext).resumeAllTorrents()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitorLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val engine = TorrentEngineHolder.getOrCreate(applicationContext)
            val dao = AppDatabase.getDatabase(applicationContext).torrentDao()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var idleTicks = 0
            while (isActive) {
                try {
                    val tasks = dao.getAllTorrentsDirect()
                    val active = tasks.filter { it.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED") }
                    val completed = tasks.filter { it.status == "COMPLETED" }
                    if (completed.size > lastCompletedAnnouncedCount) {
                        lastCompletedAnnouncedCount = completed.size
                        val latest = completed.maxByOrNull { it.completedAtMs ?: 0L }
                        if (latest != null) {
                            nm.notify(
                                COMPLETED_NOTIFICATION_ID,
                                buildCompletedNotification(
                                    title = latest.title,
                                    folderLabel = engine.getDisplayFolderLabel(latest)
                                )
                            )
                        }
                    }

                    if (active.isEmpty()) {
                        idleTicks++
                        if (idleTicks >= 4) {
                            withContext(Dispatchers.Main) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                            break
                        }
                    } else {
                        idleTicks = 0
                        nm.cancel(PAUSED_NOTIFICATION_ID)
                        val totalBytes = active.sumOf { it.totalBytes }.coerceAtLeast(1L)
                        val dlBytes = active.sumOf { it.downloadedBytes }
                        val pct = ((dlBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        val dlKbps = active.sumOf { it.downloadRateBps } / 1024L
                        val ulKbps = active.sumOf { it.uploadRateBps } / 1024L
                        val title = if (active.size == 1) active.first().title else "${active.size} active torrents"
                        val folder = if (active.size == 1) {
                            engine.resolveStagingDirForTask(active.first()).name
                        } else {
                            "AeroTorrents"
                        }
                        val notif = buildActiveNotification(active.size, title, folder, pct, dlKbps, ulKbps)
                        nm.notify(NOTIFICATION_ID, notif)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
                delay(1500L)
            }
        }
    }

    private fun openTorrentsPendingIntent(reqCode: Int): PendingIntent {
        return PendingIntent.getActivity(
            this,
            reqCode,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_tab", "Torrents")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActiveNotification(
        activeCount: Int,
        title: String,
        folderLabel: String,
        progressPct: Int,
        dlKbps: Long,
        ulKbps: Long
    ): Notification {
        val pauseIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, TorrentDownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, TorrentDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("$progressPct% • ↓ ${dlKbps} KB/s ↑ ${ulKbps} KB/s • $folderLabel ($activeCount active)")
            .setProgress(100, progressPct, progressPct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openTorrentsPendingIntent(10))
            .addAction(0, "Pause All", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun buildPausedNotification(pausedCount: Int): Notification {
        val resumeIntent = PendingIntent.getService(
            this,
            13,
            Intent(this, TorrentDownloadService::class.java).setAction(ACTION_RESUME_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Torrent Downloads Paused")
            .setContentText("$pausedCount torrent(s) paused — tap Resume to continue in background")
            .setAutoCancel(true)
            .setContentIntent(openTorrentsPendingIntent(14))
            .addAction(0, "Resume All", resumeIntent)
            .build()
    }

    private fun buildCompletedNotification(title: String, folderLabel: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Download Complete: $title")
            .setContentText("Saved to $folderLabel")
            .setAutoCancel(true)
            .setContentIntent(openTorrentsPendingIntent(15))
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "aero_torrent_downloads"
        const val NOTIFICATION_ID = 4092
        const val PAUSED_NOTIFICATION_ID = 4093
        const val COMPLETED_NOTIFICATION_ID = 4094
        const val ACTION_PAUSE_ALL = "com.emptysol.aeroplayer.TORRENT_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.emptysol.aeroplayer.TORRENT_RESUME_ALL"
        const val ACTION_STOP = "com.emptysol.aeroplayer.TORRENT_STOP_SERVICE"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "Torrent & Media Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Live progress and speed for P2P BitTorrent transfers"
                    }
                    nm.createNotificationChannel(ch)
                }
            }
        }
    }
}
