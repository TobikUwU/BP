package com.example.bp.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.bp.MainActivity
import com.example.bp.R
import kotlinx.coroutines.*


class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentDownloadJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private var currentModelName: String? = null

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"

        const val ACTION_START_DOWNLOAD = "com.example.bp.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.bp.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.bp.ACTION_RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.bp.ACTION_CANCEL_DOWNLOAD"

        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_MODEL_INFO = "model_info"

        fun startDownload(context: Context, modelName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context, modelName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, modelName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, modelName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                if (modelName != null) {
                    startDownloadInternal(modelName)
                }
            }
            ACTION_PAUSE_DOWNLOAD -> {
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                if (modelName != null) {
                    pauseDownloadInternal(modelName)
                }
            }
            ACTION_RESUME_DOWNLOAD -> {
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                if (modelName != null) {
                    resumeDownloadInternal(modelName)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                if (modelName != null) {
                    cancelDownloadInternal(modelName)
                }
            }
        }

        return START_STICKY
    }

    private fun startDownloadInternal(modelName: String) {
        currentModelName = modelName

        startForeground(NOTIFICATION_ID, createNotification(
            modelName = modelName,
            progress = 0,
            isPaused = false
        ))

        currentDownloadJob = serviceScope.launch {
            try {
                val downloadManager = DownloadManager(applicationContext)
                val modelInfo = ModelInfo(modelName, 0.0, "", false, 0)

                downloadManager.downloadModelSmart(modelInfo) { progress ->
                    updateNotification(
                        modelName = modelName,
                        progress = (progress.downloadedBytes * 100 / progress.totalBytes).toInt(),
                        isPaused = false,
                        speed = progress.currentSpeed,
                        eta = progress.eta
                    )
                }

                showCompletedNotification(modelName)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $modelName")
                showPausedNotification(modelName)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: $modelName", e)
                showErrorNotification(modelName, e.message ?: "Neznámá chyba")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseDownloadInternal(modelName: String) {
        serviceScope.launch {
            try {
                val downloadManager = DownloadManager(applicationContext)
                val parallelDownloader = ParallelModelDownloader(applicationContext)
                parallelDownloader.pauseDownload(modelName)

                showPausedNotification(modelName)
                stopForeground(STOP_FOREGROUND_DETACH)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause download", e)
            }
        }
    }

    private fun resumeDownloadInternal(modelName: String) {
        currentModelName = modelName

        startForeground(NOTIFICATION_ID, createNotification(
            modelName = modelName,
            progress = 0,
            isPaused = false,
            isResuming = true
        ))

        currentDownloadJob = serviceScope.launch {
            try {
                val parallelDownloader = ParallelModelDownloader(applicationContext)

                parallelDownloader.resumeDownload(modelName) { progress ->
                    updateNotification(
                        modelName = modelName,
                        progress = (progress.downloadedBytes * 100 / progress.totalBytes).toInt(),
                        isPaused = false,
                        speed = progress.currentSpeed,
                        eta = progress.eta
                    )
                }

                showCompletedNotification(modelName)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: CancellationException) {
                Log.d(TAG, "Download paused again: $modelName")
                showPausedNotification(modelName)
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed: $modelName", e)
                showErrorNotification(modelName, e.message ?: "Neznámá chyba")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelDownloadInternal(modelName: String) {
        serviceScope.launch {
            try {
                currentDownloadJob?.cancel()
                val parallelDownloader = ParallelModelDownloader(applicationContext)
                parallelDownloader.cancelDownload(modelName)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel download", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stahování modelů",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zobrazuje průběh stahování 3D modelů"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        modelName: String,
        progress: Int,
        isPaused: Boolean,
        isResuming: Boolean = false
    ): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            isResuming -> "Obnovování stahování..."
            isPaused -> "Stahování pozastaveno"
            else -> "Stahování modelu"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(modelName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(!isPaused)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!isPaused) {
            builder.setProgress(100, progress, progress == 0)
        }

        if (!isPaused) {
            val pauseIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                1,
                pauseIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pozastavit",
                pausePendingIntent
            )
        } else {
            val resumeIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_RESUME_DOWNLOAD
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            val resumePendingIntent = PendingIntent.getService(
                this,
                2,
                resumeIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Pokračovat",
                resumePendingIntent
            )
        }

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_MODEL_NAME, modelName)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            3,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Zrušit",
            cancelPendingIntent
        )

        return builder.build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Int,
        isPaused: Boolean,
        speed: Long = 0,
        eta: Long = 0
    ) {
        val notification = createNotification(modelName, progress, isPaused)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification(modelName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stahování dokončeno")
            .setContentText(modelName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showPausedNotification(modelName: String) {
        val notification = createNotification(modelName, 0, isPaused = true)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(modelName: String, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chyba stahování")
            .setContentText("$modelName: $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentDownloadJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
