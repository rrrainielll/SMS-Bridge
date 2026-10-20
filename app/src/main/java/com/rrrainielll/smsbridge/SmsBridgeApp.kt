package com.rrrainielll.smsbridge

import android.app.Application
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.receiver.RecordingMonitorService
import com.rrrainielll.smsbridge.work.Scheduler

class SmsBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val settings = SecureSettings(this)
        // Idempotent (KEEP): makes sure the periodic safety-net exists whenever forwarding is on.
        if (settings.forwardingEnabled) Scheduler.ensureSweep(this)
        // Restart the real-time recording monitor if recording forwarding is on.
        if (settings.recordingForwardingEnabled) {
            Scheduler.ensureRecordingSweep(this)
            RecordingMonitorService.start(this)
        }
    }
}
