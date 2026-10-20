package com.rrrainielll.smsbridge.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** WorkManager scheduling for (re)delivery and battery reports. Work survives process death and reboots. */
object Scheduler {
    private const val ONE_TIME = "smsbridge-deliver"
    private const val SWEEP = "smsbridge-sweep"

    private val needsNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Runs as soon as a network is available, retrying with exponential back-off if Telegram is unreachable. */
    fun enqueueDelivery(context: Context) {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a message arriving while a worker runs gets its own run afterwards.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net: every ~15 min, flush anything that is still queued (also re-tries after config fixes). */
    fun ensureSweep(context: Context) {
        val request = PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setInputData(workDataOf(DeliveryWorker.KEY_PERIODIC to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SWEEP, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Schedules (or replaces) the periodic battery report at the given [intervalMinutes].
     * Calling this with [intervalMinutes] <= 0 cancels any existing battery report job.
     */
    fun ensureBatteryReport(context: Context, intervalMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes <= 0) {
            wm.cancelUniqueWork(BatteryReportWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BatteryReportWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setConstraints(needsNetwork)
            .build()
        // REPLACE so a change in interval takes effect immediately rather than after the old period expires.
        wm.enqueueUniquePeriodicWork(
            BatteryReportWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Stops all battery report jobs. */
    fun cancelBatteryReport(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BatteryReportWorker.WORK_NAME)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Recording upload scheduling
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Schedules a one-time recording scan + upload as soon as network is available.
     * Uses APPEND_OR_REPLACE so an in-progress worker is followed by another run.
     */
    fun enqueueRecordingUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                RecordingUploadWorker.WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
    }

    /** Safety net: every ~15 min, scan and flush any pending recording uploads. */
    fun ensureRecordingSweep(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecordingUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setInputData(workDataOf(RecordingUploadWorker.KEY_PERIODIC to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                RecordingUploadWorker.WORK_NAME_SWEEP,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    /** Cancels all recording upload work. */
    fun cancelRecordingWork(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(RecordingUploadWorker.WORK_NAME_ONE_TIME)
        wm.cancelUniqueWork(RecordingUploadWorker.WORK_NAME_SWEEP)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_TIME)
        wm.cancelUniqueWork(SWEEP)
        wm.cancelUniqueWork(BatteryReportWorker.WORK_NAME)
        wm.cancelUniqueWork(RecordingUploadWorker.WORK_NAME_ONE_TIME)
        wm.cancelUniqueWork(RecordingUploadWorker.WORK_NAME_SWEEP)
    }
}
