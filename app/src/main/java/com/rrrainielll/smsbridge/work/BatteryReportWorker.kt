package com.rrrainielll.smsbridge.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.net.TelegramClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic WorkManager worker that sends a battery status report (level, charging state,
 * local timestamp) to the configured Telegram bot.
 *
 * Runs independently of SMS forwarding — only valid credentials are required.
 */
class BatteryReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "doWork: battery report triggered")
        val app = applicationContext
        val settings = SecureSettings(app)

        val token = settings.botToken
        val chatId = settings.chatId
        if (token == null || chatId == null) {
            Log.w(TAG, "doWork: missing credentials; skipping battery report")
            // Return success — retrying won't help until the user configures credentials.
            return Result.success()
        }

        val info = readBattery(app)
        val text = buildMessage(info)
        Log.d(TAG, "doWork: sending battery report — level=${info.level}%, charging=${info.isCharging}")

        return when (val r = TelegramClient.sendMessage(token, chatId, text, CONNECT_MS, READ_MS)) {
            TelegramClient.Result.Success -> {
                Log.i(TAG, "doWork: battery report sent successfully")
                Result.success()
            }
            is TelegramClient.Result.Transient -> {
                Log.w(TAG, "doWork: transient error — ${r.reason}; will retry")
                Result.retry()
            }
            is TelegramClient.Result.Credentials -> {
                Log.e(TAG, "doWork: credentials error — ${r.reason}")
                Result.success()   // Configuration problem; retrying won't help.
            }
            is TelegramClient.Result.Rejected -> {
                Log.e(TAG, "doWork: message rejected — ${r.reason}")
                Result.success()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Battery reading
    // ──────────────────────────────────────────────────────────────────────────────

    private data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean,
        val chargingLabel: String
    )

    private fun readBattery(context: Context): BatteryInfo {
        // ACTION_BATTERY_CHANGED is a sticky broadcast — pass null receiver to read the current value.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val rawScale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status   = intent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged  = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val level = if (rawScale > 0) rawLevel * 100 / rawScale else rawLevel

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val chargingLabel = when {
            status == BatteryManager.BATTERY_STATUS_FULL        -> "Full / Charged"
            status == BatteryManager.BATTERY_STATUS_CHARGING    -> when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC       -> "Charging (AC)"
                BatteryManager.BATTERY_PLUGGED_USB      -> "Charging (USB)"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Charging (Wireless)"
                else                                    -> "Charging"
            }
            status == BatteryManager.BATTERY_STATUS_DISCHARGING  -> "Discharging"
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING  -> "Not charging"
            else                                                   -> "Unknown"
        }

        return BatteryInfo(level, isCharging, chargingLabel)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Message formatting
    // ──────────────────────────────────────────────────────────────────────────────

    private fun buildMessage(info: BatteryInfo): String {
        val plugEmoji    = if (info.isCharging) " ⚡" else ""
        val batteryEmoji = when {
            info.level < 0  -> "🔋"
            info.level >= 50 -> "🔋"
            else             -> "🪫"
        }
        val levelStr = if (info.level < 0) "Unknown" else "${info.level}%"
        val time     = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault())
            .format(Date())
        return "$batteryEmoji Battery Report$plugEmoji\n" +
            "Level: $levelStr\n" +
            "Status: ${info.chargingLabel}\n" +
            "Time: $time"
    }

    companion object {
        private const val TAG        = "BatteryReportWorker"
        private const val CONNECT_MS = 15_000
        private const val READ_MS    = 20_000

        /** Unique WorkManager tag for the periodic battery report job. */
        const val WORK_NAME = "smsbridge-battery-report"
    }
}
