package com.rrrainielll.smsbridge.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import com.rrrainielll.smsbridge.R
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.work.RecordingScanner
import com.rrrainielll.smsbridge.work.Scheduler
import java.io.File

/**
 * Foreground service that uses [FileObserver] to detect new audio recordings in real-time.
 *
 * Android requires a foreground notification for services that do continuous file observation.
 * The notification is silent, low-importance, and contains no sensitive content.
 *
 * When a new audio file is created or closed-after-write in a watched directory, the service
 * schedules a one-time [com.rrrainielll.smsbridge.work.RecordingUploadWorker] to scan and
 * upload it. The worker runs the actual upload so it benefits from WorkManager's network
 * constraints and retry logic.
 *
 * The service is started by [BootReceiver] and by the ViewModel when recording forwarding is
 * enabled, and stopped when the feature is disabled.
 */
class RecordingMonitorService : Service() {

    private val observers = mutableListOf<FileObserver>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification())
        startObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: stopping observers")
        observers.forEach { it.stopWatching() }
        observers.clear()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // FileObserver setup
    // ──────────────────────────────────────────────────────────────────────────────

    private fun startObservers() {
        val dirs = RecordingScanner.watchedDirectories()
        dirs.forEach { dir ->
            if (!dir.exists()) {
                // Try to create the directory so future recordings will be picked up.
                dir.mkdirs()
            }
            if (dir.isDirectory) {
                val observer = createObserver(dir)
                observer.startWatching()
                observers += observer
                Log.d(TAG, "startObservers: watching ${dir.absolutePath}")
            } else {
                Log.d(TAG, "startObservers: skipping missing directory ${dir.absolutePath}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createObserver(dir: File): FileObserver {
        val ctx = applicationContext
        // CLOSE_WRITE fires after a process finishes writing and closes the file — ideal for recordings.
        // CREATE fires immediately; we listen to it too for short/live recordings, but the worker
        // uses the STABLE_DELAY_MS check to skip files still being written.
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleNewFile(ctx, File(dir, path))
                }
            }
        } else {
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleNewFile(ctx, File(dir, path))
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Event handling
    // ──────────────────────────────────────────────────────────────────────────────

    private fun handleNewFile(ctx: Context, file: File) {
        val ext = file.extension.lowercase()
        if (ext !in AUDIO_EXTENSIONS) return

        Log.i(TAG, "handleNewFile: detected ${file.name}")
        val settings = SecureSettings(ctx)
        if (!settings.recordingForwardingEnabled) return

        // CLOSE_WRITE / MOVED_TO guarantees the file is already fully written.
        // Queue it immediately into RecordingStore so the upload worker finds it as
        // PENDING without going through isStable() — which would skip a file modified
        // less than STABLE_DELAY_MS ago (i.e. right now).
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "handleNewFile: file not ready yet, skipping immediate queue: ${file.name}")
            // Worker's periodic sweep will catch it once stable.
            Scheduler.enqueueRecordingUpload(ctx)
            return
        }

        val key   = RecordingScanner.dedupeKey(file)
        val store = com.rrrainielll.smsbridge.data.RecordingStore.get(ctx)
        val isNew = store.insertIfNew(key, file.absolutePath)
        Log.i(TAG, "handleNewFile: insertIfNew=${isNew} for ${file.name}")

        // Schedule the upload — WorkManager will honour the network constraint and retry.
        Scheduler.enqueueRecordingUpload(ctx)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ──────────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording Monitor",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Monitors recording folders to forward voicemails and call recordings to Telegram."
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("SMSBridge")
            .setContentText("Monitoring recordings for forwarding")
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val TAG = "RecordingMonitorService"
        private const val NOTIF_ID  = 1002
        private const val CHANNEL_ID = "recording_monitor"

        private val AUDIO_EXTENSIONS = setOf(
            "ogg", "opus", "m4a", "mp3", "aac", "wav", "flac", "3gp", "3gpp", "amr"
        )

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, RecordingMonitorService::class.java)
            context.applicationContext.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, RecordingMonitorService::class.java)
            )
        }
    }
}
