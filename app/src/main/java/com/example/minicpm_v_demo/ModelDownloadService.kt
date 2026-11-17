package com.example.minicpm_v_demo

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.minicpm_v_demo.harness.HarnessModelRegistry
import com.example.minicpm_v_demo.harness.LlamaDownloadManager
import com.example.minicpm_v_demo.harness.attribution.AttributionModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Keeps independent model downloads running while the management screen is closed. */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()
    private lateinit var downloadManager: LlamaDownloadManager
    private lateinit var notifications: NotificationManager
    private var latestStartId = 0
    private var foreground = false
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        downloadManager = LlamaDownloadManager(applicationContext)
        notifications = requireNotNull(getSystemService(NotificationManager::class.java))
        ensureNotificationChannel(this)
        scope.launch {
            ModelDownloadController.statuses.collect {
                if (foreground && jobs.isNotEmpty()) {
                    notifications.notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (intent?.action == ACTION_CANCEL) {
            jobs.toList().forEach { (id, job) ->
                if ((modelId == null || modelId == id) && job.isActive) {
                    ModelDownloadController.setStatus(id, ModelDownloadController.Status.Cancelling)
                    job.cancel()
                }
            }
            stopIfIdle()
            return START_NOT_STICKY
        }

        val model = if (modelId == null) {
            LlamaEngine.getSelectedModel(applicationContext)
        } else {
            HarnessModelRegistry.findLegacyModel(modelId)
                ?: AttributionModelRegistry.findEligibleEntry(modelId)?.model
        }
        if (model != null && model.id !in jobs) {
            val job = scope.launch(start = CoroutineStart.LAZY) { download(model) }
            jobs[model.id] = job
            notifications.cancel(model.id, RESULT_NOTIFICATION_ID)
            ModelDownloadController.setStatus(model.id, ModelDownloadController.Status.Running())
            startForegroundWithNotification(buildNotification())
            acquireWakeLock()
            job.start()
        } else {
            startForegroundWithNotification(buildNotification())
            if (model == null && modelId != null) {
                ModelDownloadController.setStatus(
                    modelId,
                    ModelDownloadController.Status.Failed(getString(R.string.toast_model_not_found)),
                )
            }
            stopIfIdle()
        }

        return START_NOT_STICKY
    }

    private suspend fun download(model: ModelInfo) {
        val id = model.id
        var result: ModelDownloadController.Status.Finished = ModelDownloadController.Status.Cancelled
        try {
            downloadManager.downloadModel(model) { message ->
                ModelDownloadController.publishProgress(id, message)
            }
            result = ModelDownloadController.Status.Completed
        } catch (error: CancellationException) {
            if (LlamaEngine.modelFile(this, model).isFile) {
                result = ModelDownloadController.Status.Completed
            }
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Download failed for $id", error)
            result = ModelDownloadController.Status.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            jobs.remove(id)
            ModelDownloadController.setStatus(id, result)
            if (!destroyed) {
                try {
                    if (result != ModelDownloadController.Status.Cancelled) {
                        notifications.notify(id, RESULT_NOTIFICATION_ID, buildResultNotification(model, result))
                    }
                } finally {
                    stopIfIdle()
                }
            }
        }
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        releaseWakeLock()
        if (stopSelfResult(latestStartId) && foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        foreground = false
        jobs.keys.forEach { ModelDownloadController.setStatus(it, ModelDownloadController.Status.Cancelling) }
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun openModelManager(): PendingIntent {
        val intent = Intent(this, ModelManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val statuses = ModelDownloadController.statuses.value
        val messages = jobs.keys.map { id ->
            val status = statuses[id]
            if (status is ModelDownloadController.Status.Running && status.progress.isNotBlank()) {
                status.progress
            } else {
                val modelName = HarnessModelRegistry.findLegacyModel(id)?.displayName ?: id
                val message = status?.message(this) ?: getString(R.string.download_preparing)
                getString(R.string.download_model_message, modelName, message)
            }
        }
        val summary = if (messages.size > 1) {
            resources.getQuantityString(R.plurals.download_active_count, messages.size, messages.size)
        } else {
            messages.firstOrNull() ?: getString(R.string.download_preparing)
        }
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle()
        messages.forEach(style::addLine)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_download_title))
            .setContentText(summary)
            .setStyle(style)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openModelManager())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_all_downloads), cancelIntent)
            .build()
    }

    private fun buildResultNotification(
        model: ModelInfo,
        result: ModelDownloadController.Status.Finished,
    ): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(model.displayName)
        .setContentText(result.message(this))
        .setStyle(NotificationCompat.BigTextStyle().bigText(result.message(this)))
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setAutoCancel(true)
        .setContentIntent(openModelManager())
        .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MiniCPM:ModelDownload",
        ).apply { setReferenceCounted(false) }
        wakeLock = lock
        lock.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 0xD0_C1
        private const val RESULT_NOTIFICATION_ID = 0xD0_C2
        private const val ACTION_START = "com.example.minicpm_v_demo.action.DOWNLOAD_START"
        private const val ACTION_CANCEL = "com.example.minicpm_v_demo.action.DOWNLOAD_CANCEL"
        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            if (!ModelDownloadController.requestStart(modelId)) return
            try {
                val intent = Intent(context, ModelDownloadService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_MODEL_ID, modelId)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start download for $modelId", error)
                ModelDownloadController.setStatus(
                    modelId,
                    ModelDownloadController.Status.Failed(error.message ?: error.javaClass.simpleName),
                )
            }
        }

        fun cancel(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(intent)
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}

/** Process-local download state indexed by the shared model ID. */
object ModelDownloadController {

    sealed interface Status {
        sealed interface Active : Status
        data object Starting : Active
        data class Running(val progress: String = "") : Active
        data object Cancelling : Active

        sealed interface Finished : Status
        data object Completed : Finished
        data object Cancelled : Finished
        data class Failed(val reason: String) : Finished

        fun message(context: Context): String = when (this) {
            Starting -> context.getString(R.string.download_preparing)
            is Running -> progress.ifBlank { context.getString(R.string.status_downloading) }
            Cancelling -> context.getString(R.string.download_cancelling)
            Completed -> context.getString(R.string.download_complete_status)
            Cancelled -> context.getString(R.string.download_cancelled)
            is Failed -> context.getString(R.string.download_failed, reason)
        }
    }

    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

    internal fun requestStart(id: String): Boolean {
        while (true) {
            val previous = _statuses.value
            if (previous[id] is Status.Active) return false
            if (_statuses.compareAndSet(previous, previous + (id to Status.Starting))) return true
        }
    }

    internal fun setStatus(id: String, status: Status) {
        _statuses.update { it + (id to status) }
    }

    internal fun publishProgress(id: String, progress: String) {
        _statuses.update { statuses ->
            if (statuses[id] is Status.Running) statuses + (id to Status.Running(progress)) else statuses
        }
    }

    fun acknowledge(id: String, result: Status.Finished) {
        _statuses.update { if (it[id] === result) it - id else it }
    }
}
