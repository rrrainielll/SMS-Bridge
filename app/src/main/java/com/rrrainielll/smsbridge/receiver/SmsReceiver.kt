package com.rrrainielll.smsbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.rrrainielll.smsbridge.data.MessageStore
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.work.Deliverer
import com.rrrainielll.smsbridge.work.Scheduler
import java.util.concurrent.Executors

/**
 * Receives every incoming SMS (no content filtering). The message is first written to the durable
 * queue, then WorkManager is scheduled as the guaranteed fallback, and finally a quick immediate
 * delivery is attempted so OTPs arrive on Telegram within a second or two when online.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        val app = context.applicationContext
        val settings = SecureSettings(app)

        if (!settings.forwardingEnabled) {
            Log.i(TAG, "Forwarding is disabled; ignoring incoming SMS.")
            return
        }

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) {
            Log.w(TAG, "SMS_RECEIVED broadcast contained no PDU parts.")
            return
        }
        Log.d(TAG, "Received ${parts.size} PDU part(s).")

        // Multi-part SMS arrive as several PDUs of the same message: join them in order.
        val grouped = LinkedHashMap<String, StringBuilder>()
        val timestamps = HashMap<String, Long>()
        for (p in parts) {
            val sender = p.originatingAddress ?: "Unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(p.messageBody ?: "")
            timestamps.putIfAbsent(sender, p.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
        }
        Log.d(TAG, "Grouped into ${grouped.size} message(s) from distinct senders.")

        val pending = goAsync()
        executor.execute {
            try {
                val store = MessageStore.get(app)
                var added = false
                for ((sender, body) in grouped) {
                    val bodyStr = body.toString()
                    Log.d(TAG, "Inserting message from sender (length=${sender.length}), body length=${bodyStr.length}")
                    val wasNew = store.insertIfNew(sender, bodyStr, timestamps.getValue(sender))
                    Log.d(TAG, "insertIfNew returned: $wasNew")
                    added = wasNew || added
                }
                if (added) {
                    Log.i(TAG, "New message(s) queued. Scheduling delivery.")
                    Scheduler.enqueueDelivery(app)
                    Deliverer.processPending(app, Deliverer.Mode.QUICK)
                } else {
                    Log.i(TAG, "All messages were duplicates; no delivery scheduled.")
                }
            } catch (e: Exception) {
                // Log the exception class and message but never the SMS content.
                Log.e(TAG, "Error processing incoming SMS: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
        val executor = Executors.newSingleThreadExecutor()
    }
}
