package com.rrrainielll.smsbridge.work

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that scans watched folders for new recordings and uploads pending ones
 * to Telegram. Scheduled periodically (every ~15 minutes) as a safety net, and triggered
 * immediately whenever a new file is detected.
 *
 * Separating the recording upload into its own worker keeps it independent from SMS delivery
 * so that a failed recording upload doesn't block SMS forwarding and vice versa.
 */
class RecordingUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val periodic = inputData.getBoolean(KEY_PERIODIC, false)
        Log.d(TAG, "doWork: periodic=$periodic, runAttemptCount=$runAttemptCount")

        return try {
            // Step 1: scan for newly created files and enqueue them.
            val newFiles = RecordingScanner.scan(applicationContext)
            Log.d(TAG, "doWork: scan found $newFiles new recording(s)")

            // Step 2: upload everything pending.
            val outcome = RecordingScanner.uploadPending(applicationContext)
            Log.d(TAG, "doWork: upload outcome=$outcome")

            when (outcome) {
                RecordingScanner.Outcome.RETRY             -> if (periodic) Result.success() else Result.retry()
                RecordingScanner.Outcome.CREDENTIALS_ERROR -> Result.success()   // Won't improve with retry; user must fix.
                else                                       -> Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "doWork: exception: ${e.javaClass.simpleName}: ${e.message}")
            if (periodic) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "RecordingUploadWorker"
        const val KEY_PERIODIC = "periodic"
        const val WORK_NAME_ONE_TIME = "smsbridge-recording-upload"
        const val WORK_NAME_SWEEP    = "smsbridge-recording-sweep"
    }
}
