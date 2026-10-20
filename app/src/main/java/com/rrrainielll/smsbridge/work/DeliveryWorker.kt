package com.rrrainielll.smsbridge.work

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class DeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val periodic = inputData.getBoolean(KEY_PERIODIC, false)
        Log.d(TAG, "doWork: periodic=$periodic, runAttemptCount=$runAttemptCount")
        val outcome = try {
            Deliverer.processPending(applicationContext, Deliverer.Mode.FULL) { isStopped }
        } catch (e: Exception) {
            Log.e(TAG, "doWork: exception: ${e.javaClass.simpleName}: ${e.message}")
            Deliverer.Outcome.RETRY
        }
        Log.d(TAG, "doWork: outcome=$outcome")
        return when (outcome) {
            // Periodic runs are rescheduled by WorkManager anyway; one-time runs use exponential back-off.
            Deliverer.Outcome.RETRY -> if (periodic) Result.success() else Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val TAG = "DeliveryWorker"
        const val KEY_PERIODIC = "periodic"
    }
}
