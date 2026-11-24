package com.muratkirazkaya.meiro.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.muratkirazkaya.meiro.AppContainer
import com.muratkirazkaya.meiro.MainActivity
import com.muratkirazkaya.meiro.MeiroApplication
import com.muratkirazkaya.meiro.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps downloads going with the app in the background or the screen off (M6e, type
 * dataSync). It shows the mock's download notification (lock screen `.notif`): "Meiro · İndirme", the "İniyor" row's
 * name and line, its bar and "Hepsini durdur"; and it stops itself once the queue is empty. The rules are in
 * [DownloadForeground]; the queue itself ([Downloader], 2 at a time, Wi-Fi only) is untouched.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastStartId = 0
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // Default importance, so the notification shows on the lock screen as in the mock (Android hides silent, low
        // importance ones there, and so a FLAG_SILENT one); no sound or vibration on the channel, alerting once.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        // The screen off lets the CPU sleep; a download keeps it up while the queue runs (10 minutes, renewed on each step).
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meiro:downloads").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val c = (application as MeiroApplication).containerNow()
        if (intent?.action == ACTION_STOP) {
            Log.i(AppContainer.DL_TAG, "download service: Hepsini durdur from the notification")
            c?.downloader?.cancelAll()
            if (job?.isActive != true) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // Started with startForegroundService: it must go to the foreground at once, even if the queue emptied meanwhile.
        foreground(c?.downloadForeground?.now() ?: DownloadNotice("İndirme", "", 0))
        if (c == null) { stopSelfResult(startId); return START_NOT_STICKY }
        if (job?.isActive != true) job = scope.launch {
            while (true) {
                var gone = false
                c.downloadForeground.serve(show = ::show) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    wake?.takeIf { it.isHeld }?.release()
                    gone = stopSelfResult(lastStartId)
                }
                if (gone) break
                // A new batch was asked for while it stopped: go on.
                foreground(c.downloadForeground.now() ?: break)
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground(n: DownloadNotice) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(n), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        wake?.acquire(WAKE_MS)
    }

    private fun show(n: DownloadNotice) {
        wake?.acquire(WAKE_MS)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(n))
        }
    }

    private fun build(n: DownloadNotice) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_download)
        .setSubText("İndirme")
        .setContentTitle(n.title)
        .setContentText(n.text)
        .setProgress(100, n.percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "Hepsini durdur", PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    /** Android 15+: a dataSync service may run 6 hours a day; past that it must stop (the queue goes on while the process lives). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(AppContainer.DL_TAG, "download service: Android's time limit, stopping")
        stopNow()
    }

    private fun stopNow() {
        job?.cancel()
        (application as MeiroApplication).containerNow()?.downloadForeground?.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(AppContainer.DL_TAG, "download service: destroyed")
        (application as MeiroApplication).containerNow()?.downloadForeground?.let { if (job?.isActive == true) it.stopped() }
        wake?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 7301
        const val ACTION_STOP = "com.muratkirazkaya.meiro.downloads.STOP"
        private const val WAKE_MS = 10L * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
