package com.rrrainielll.smsbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rrrainielll.smsbridge.data.MessageStore
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.work.Scheduler

/**
 * After a reboot or app update, re-arm the periodic safety net and flush anything left in the queue.
 * Also re-arms the battery report and recording forwarding schedules if previously configured.
 * (SMS reception itself needs no restoration: the manifest receiver is active again automatically.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext
        val settings = SecureSettings(app)

        // Re-arm SMS delivery sweep and flush pending queue.
        if (settings.forwardingEnabled) {
            Scheduler.ensureSweep(app)
            if (MessageStore.get(app).hasPending()) Scheduler.enqueueDelivery(app)
        }

        // Re-arm battery report independently of forwarding state.
        val batteryInterval = settings.batteryReportIntervalMinutes
        if (batteryInterval > 0) {
            Scheduler.ensureBatteryReport(app, batteryInterval)
        }

        // Re-arm recording forwarding: periodic sweep + real-time monitor service.
        if (settings.recordingForwardingEnabled) {
            Scheduler.ensureRecordingSweep(app)
            RecordingMonitorService.start(app)
        }
    }
}
