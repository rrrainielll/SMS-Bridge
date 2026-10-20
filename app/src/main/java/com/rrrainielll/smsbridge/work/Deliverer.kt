package com.rrrainielll.smsbridge.work

import android.content.Context
import android.util.Log
import com.rrrainielll.smsbridge.Notifier
import com.rrrainielll.smsbridge.data.ConnectionState
import com.rrrainielll.smsbridge.data.MessageStore
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.net.SmsFallbackSender
import com.rrrainielll.smsbridge.net.TelegramClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Sends queued SMS to Telegram, oldest first.
 *
 * A single process-wide lock guarantees that the receiver's immediate attempt, WorkManager retries and
 * the manual "retry" button can never deliver the same message concurrently (no duplicates). A message
 * is only marked as sent after Telegram acknowledged it, so a crash can at worst cause a re-send, never a loss.
 */
object Deliverer {

    private const val TAG = "Deliverer"

    enum class Mode(
        val connectMs: Int,
        val readMs: Int,
        val budgetMs: Long,
        val pauseBetweenMs: Long
    ) {
        /** From the SMS receiver: must finish well inside the ~10 s broadcast window. */
        QUICK(3_000, 4_000, 7_000, 0),

        /** From WorkManager / user action: patient, and polite about Telegram's per-chat rate limit. */
        FULL(15_000, 20_000, Long.MAX_VALUE, 1_100)
    }

    enum class Outcome {
        /** Queue is empty. */
        DONE,

        /** Temporary failure; try again later. */
        RETRY,

        /** Credentials/chat problem. Won't succeed until the user fixes the configuration. */
        BLOCKED,
        NOT_CONFIGURED,
        DISABLED
    }

    class TestResult(val success: Boolean, val message: String)

    private val lock = ReentrantLock()

    fun processPending(
        context: Context,
        mode: Mode,
        shouldStop: () -> Boolean = { false }
    ): Outcome {
        val app = context.applicationContext
        val settings = SecureSettings(app)

        if (!settings.forwardingEnabled) {
            Log.d(TAG, "processPending: forwarding disabled.")
            return Outcome.DISABLED
        }
        val token = settings.botToken
        val chatId = settings.chatId
        if (token == null || chatId == null) {
            Log.w(TAG, "processPending: missing credentials (token=${token != null}, chatId=${chatId != null}).")
            return Outcome.NOT_CONFIGURED
        }
        Log.d(TAG, "processPending: mode=$mode, token present, chatId present.")

        val acquired = if (mode == Mode.QUICK) {
            lock.tryLock(1_500, TimeUnit.MILLISECONDS)
        } else {
            lock.lockInterruptibly(); true
        }
        if (!acquired) {
            Log.w(TAG, "processPending: could not acquire lock in QUICK mode; returning RETRY.")
            return Outcome.RETRY
        }

        try {
            val store = MessageStore.get(app)
            val started = System.currentTimeMillis()
            var deliveredCount = 0

            while (true) {
                if (shouldStop()) {
                    Log.d(TAG, "processPending: shouldStop() returned true.")
                    return Outcome.RETRY
                }
                if (System.currentTimeMillis() - started > mode.budgetMs) {
                    Log.d(TAG, "processPending: time budget exhausted after ${deliveredCount} message(s).")
                    return Outcome.RETRY
                }
                // Re-check on every message so "Stop" takes effect immediately.
                if (!settings.forwardingEnabled) {
                    Log.d(TAG, "processPending: forwarding was disabled mid-loop.")
                    return Outcome.DISABLED
                }

                val msg = store.nextPending()
                if (msg == null) {
                    Log.i(TAG, "processPending: queue empty; delivered $deliveredCount message(s).")
                    return Outcome.DONE
                }

                val sender = msg.sender
                val body = msg.body
                if (sender == null || body == null) {
                    // Encryption key was lost (e.g. app data restored on another device).
                    Log.w(TAG, "processPending: cannot decrypt message id=${msg.id}; marking failed.")
                    store.markFailed(msg.id)
                    continue
                }

                Log.d(TAG, "processPending: delivering message id=${msg.id}, body length=${body.length}")
                val parts = buildParts(sender, body, msg.receivedAt)
                var telegramFailed = false
                var telegramOutcome: Outcome? = null

                partLoop@ for (i in msg.partsSent until parts.size) {
                    when (val r = TelegramClient.sendMessage(token, chatId, parts[i], mode.connectMs, mode.readMs, "HTML")) {
                        TelegramClient.Result.Success -> {
                            Log.d(TAG, "Part ${i + 1}/${parts.size} sent for message id=${msg.id}.")
                            store.setPartsSent(msg.id, i + 1)
                            if (i + 1 < parts.size && mode.pauseBetweenMs > 0 && !pause(mode.pauseBetweenMs)) {
                                return Outcome.RETRY
                            }
                        }

                        is TelegramClient.Result.Transient -> {
                            Log.w(TAG, "Transient failure for message id=${msg.id}: ${r.reason}")
                            store.recordFailedAttempt(msg.id)
                            settings.setLastError(r.reason)
                            telegramFailed = true
                            telegramOutcome = Outcome.RETRY
                            break@partLoop
                        }

                        is TelegramClient.Result.Credentials -> {
                            Log.e(TAG, "Credentials failure for message id=${msg.id}: ${r.reason}")
                            store.recordFailedAttempt(msg.id)
                            settings.setConnection(ConnectionState.FAILED, r.reason)
                            Notifier.showIssue(app)
                            telegramFailed = true
                            telegramOutcome = Outcome.BLOCKED
                            break@partLoop
                        }

                        is TelegramClient.Result.Rejected -> {
                            Log.e(TAG, "Message id=${msg.id} rejected by Telegram: ${r.reason}")
                            store.markFailed(msg.id)
                            settings.setLastError(r.reason)
                            telegramFailed = true
                            telegramOutcome = null // rejected = permanently failed; continue with next message
                            break@partLoop
                        }
                    }
                }

                if (telegramFailed) {
                    // Attempt SMS fallback for any Telegram failure, but only once per message.
                    val fallbackNumber = settings.fallbackSmsNumber
                    if (fallbackNumber != null && !msg.smsFallbackSent) {
                        Log.i(TAG, "Telegram failed for message id=${msg.id}; attempting SMS fallback to $fallbackNumber.")
                        val fallbackText = buildFallbackText(sender, body, msg.receivedAt)
                        val fbResult = SmsFallbackSender.send(app, fallbackNumber, fallbackText)
                        if (fbResult is SmsFallbackSender.Result.Success) {
                            Log.i(TAG, "SMS fallback succeeded for message id=${msg.id}.")
                            store.markSmsFallbackSent(msg.id)
                        } else if (fbResult is SmsFallbackSender.Result.Failure) {
                            Log.e(TAG, "SMS fallback ALSO failed for message id=${msg.id}: ${fbResult.reason}")
                        }
                    } else if (fallbackNumber == null) {
                        Log.d(TAG, "No fallback number configured; skipping SMS fallback for message id=${msg.id}.")
                    } else {
                        Log.d(TAG, "SMS fallback already sent for message id=${msg.id}; skipping duplicate.")
                    }

                    // Return or continue based on the Telegram failure type.
                    if (telegramOutcome != null) return telegramOutcome
                    continue // Rejected: move to the next queued message
                }

                store.markSent(msg.id)
                settings.setConnection(ConnectionState.CONNECTED, null)
                Notifier.clearIssue(app)
                deliveredCount++
                Log.i(TAG, "Message id=${msg.id} delivered successfully (total this run: $deliveredCount).")

                if (mode.pauseBetweenMs > 0 && store.hasPending() && !pause(mode.pauseBetweenMs)) {
                    return Outcome.RETRY
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** Sends a test message using the saved credentials and updates the connection status. */
    fun testConnection(context: Context): TestResult {
        val app = context.applicationContext
        val settings = SecureSettings(app)
        val token = settings.botToken
        val chatId = settings.chatId
        if (token == null || chatId == null) {
            return TestResult(false, "Enter your bot token and chat ID in Settings first.")
        }
        val text = "✅ SMSBridge test message\n" +
            "Sent ${formatTime(System.currentTimeMillis())}\n\n" +
            "If you can read this, incoming SMS will be forwarded to this chat."
        Log.d(TAG, "testConnection: sending test message.")
        return when (val r = TelegramClient.sendMessage(token, chatId, text, Mode.FULL.connectMs, Mode.FULL.readMs)) {
            TelegramClient.Result.Success -> {
                Log.i(TAG, "testConnection: success.")
                settings.setConnection(ConnectionState.CONNECTED, null)
                Notifier.clearIssue(app)
                TestResult(true, "Test message sent. Check Telegram.")
            }

            is TelegramClient.Result.Credentials -> {
                Log.e(TAG, "testConnection: credentials error: ${r.reason}")
                settings.setConnection(ConnectionState.FAILED, r.reason)
                TestResult(false, r.reason)
            }

            is TelegramClient.Result.Transient -> {
                Log.w(TAG, "testConnection: transient error: ${r.reason}")
                TestResult(false, r.reason)
            }
            is TelegramClient.Result.Rejected -> {
                Log.w(TAG, "testConnection: rejected: ${r.reason}")
                TestResult(false, r.reason)
            }
        }
    }

    /** Formats one SMS as one or more Telegram HTML messages (Telegram's limit is 4096 characters). */
    internal fun buildParts(sender: String, body: String, receivedAt: Long): List<String> {
        val content = body.ifEmpty { "(empty message)" }
        val highlighted = OtpHighlighter.highlight(content)
        val escapedSender = OtpHighlighter.escapeHtml(sender)
        val chunks = chunk(highlighted, MAX_BODY_CHARS)
        val time = formatTime(receivedAt)
        return chunks.mapIndexed { index, chunk ->
            val suffix = if (chunks.size > 1) " (part ${index + 1}/${chunks.size})" else ""
            "💬 SMS From: $escapedSender$suffix\n🕐 Time: $time\n\n$chunk"
        }
    }

    /**
     * Builds a compact single-part SMS fallback text (≤ 160 chars for single GSM-7 message where
     * possible). Truncates the body rather than splitting, since SMS fallback is a last resort.
     */
    internal fun buildFallbackText(sender: String, body: String, receivedAt: Long): String {
        val header = "SMS from $sender [${formatTime(receivedAt)}]: "
        val maxBody = 160 - header.length
        val content = if (maxBody > 0) body.take(maxBody) else body.take(100)
        return "$header$content"
    }

    private fun chunk(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            // Don't split a surrogate pair (emoji) across two messages.
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).format(Date(millis))

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    private const val MAX_BODY_CHARS = 3_500
}
