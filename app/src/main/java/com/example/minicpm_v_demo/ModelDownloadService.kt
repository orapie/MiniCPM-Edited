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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the selected model download outside the
 * Activity's lifecycle. Survives:
 *   - user switching to another app
 *   - the screen turning off (Doze still throttles network somewhat, but the
 *     PARTIAL_WAKE_LOCK keeps the CPU awake long enough to keep the socket
 *     alive; combined with the HTTP Range resume in the downloader that's
 *     enough to make multi-GB downloads reliable)
 *   - Activity being destroyed (we're a service, not bound to it)
 *
 * Coordinates with the UI through [ModelDownloadController] (a simple
 * StateFlow singleton) - we deliberately don't expose a binder so observing
 * the service requires no IPC plumbing in the Activity.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var downloadJob: Job? = null
    private lateinit var downloadManager: LlamaDownloadManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        downloadManager = LlamaDownloadManager(applicationContext)
        ensureNotificationChannel(this)
    }

    /** 处理前台下载或取消指令，并将进度与终止状态发布给模型管理页。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_CANCEL) {
            Log.i(TAG, "Cancel requested")
            downloadJob?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }

        // Only one download at a time. If a job is already running, just
        // re-attach the foreground notification and bail.
        if (downloadJob?.isCompleted == false) {
            startForegroundWithNotification(buildNotification(getString(R.string.status_downloading)))
            return START_NOT_STICKY
        }

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = modelId?.let {
            HarnessModelRegistry.findLegacyModel(it) ?: AttributionModelRegistry.findEligibleEntry(it)?.model
        } ?: LlamaEngine.getSelectedModel(applicationContext)

        startForegroundWithNotification(buildNotification(getString(R.string.download_preparing)))
        acquireWakeLock()
        ModelDownloadController.markStarted(model.id)

        downloadJob = scope.launch {
            try {
                downloadManager.downloadModel(model) { message ->
                    ModelDownloadController.publishProgress(message)
                    updateNotification(message)
                }
                ModelDownloadController.markCompleted(model.id)
                updateNotification(getString(R.string.download_complete_short))
            } catch (e: CancellationException) {
                ModelDownloadController.markCancelled(model.id)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                ModelDownloadController.markFailed(model.id, e.message ?: e::class.java.simpleName)
                updateNotification(getString(R.string.download_failed, e.message))
            } finally {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        releaseWakeLock()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun updateNotification(message: String) {
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, ModelManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelPi = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_download_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPi
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        // PARTIAL_WAKE_LOCK keeps the CPU running with the screen off, but
        // *not* the radio - that's fine, OkHttp/HttpURLConnection only need
        // the CPU awake to drive the socket; the modem stays in low power.
        // 30 minute hard timeout as a safety belt against leaks: a typical
        // download is well under that.
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MiniCPMV:ModelDownload"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 0xD0_C1

        const val ACTION_START = "com.example.minicpm_v_demo.action.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.example.minicpm_v_demo.action.DOWNLOAD_CANCEL"

        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            ensureNotificationChannel(context)
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            // Use startService instead of startForegroundService for the
            // cancel signal to avoid a stray foreground transition.
            context.startService(intent)
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

/**
 * Singleton that decouples the service from any specific Activity. The
 * Activity (or other UI) collects [status] to render progress; the service
 * publishes updates via the package-private [publishProgress] / [markFoo]
 * functions.
 *
 * This lives outside the service so that observers don't need to bind to the
 * service or worry about IPC - the JVM-local StateFlow is enough for our
 * single-process app.
 * 前台服务通过此控制器发布下载状态，界面重新创建后仍可订阅当前结果。
 */
object ModelDownloadController {

    sealed class Status(val modelId: String?) {
        object Idle : Status(null)
        data class Running(val id: String, val message: String) : Status(id)
        data class Completed(val id: String) : Status(id)
        data class Cancelled(val id: String) : Status(id)
        data class Failed(val id: String, val message: String) : Status(id)
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isRunning: Boolean
        get() = _status.value is Status.Running

    internal fun markStarted(modelId: String) {
        _status.value = Status.Running(modelId, "")
    }

    internal fun publishProgress(message: String) {
        val running = _status.value as? Status.Running ?: return
        _status.value = running.copy(message = message)
    }

    internal fun markCompleted(modelId: String) {
        _status.value = Status.Completed(modelId)
    }

    internal fun markFailed(modelId: String, reason: String) {
        _status.value = Status.Failed(modelId, reason)
    }

    internal fun markCancelled(modelId: String) {
        _status.value = Status.Cancelled(modelId)
    }

    fun acknowledge() {
        val current = _status.value
        if (current !is Status.Running) _status.compareAndSet(current, Status.Idle)
    }
}
