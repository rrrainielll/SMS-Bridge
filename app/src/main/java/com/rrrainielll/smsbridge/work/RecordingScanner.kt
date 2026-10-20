package com.rrrainielll.smsbridge.work

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.rrrainielll.smsbridge.data.RecordingStore
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.net.TelegramAudioSender
import com.rrrainielll.smsbridge.net.TelegramClient
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Scans configured folders for audio recordings and forwards new ones to Telegram.
 *
 * Folders monitored (all relative to external storage root):
 *   - Recordings/Voicemail    (Samsung One UI voicemail recordings)
 *   - Recordings/Call         (Samsung One UI call recordings)
 *   - MIUI/sound_recorder/call_rec  (Xiaomi — ignored if absent)
 *   - CallRecordings          (various OEMs)
 *
 * A file is "new" if its path + last-modified time has not been seen before (per [RecordingStore]).
 *
 * For the periodic sweep worker, files must have been stable (not modified) for at least
 * [STABLE_DELAY_MS] to avoid uploading partial recordings still being written. Files queued
 * directly from [com.rrrainielll.smsbridge.receiver.RecordingMonitorService] via CLOSE_WRITE
 * bypass this check — by definition the file is complete at that point.
 */
object RecordingScanner {

    private const val TAG = "RecordingScanner"

    /**
     * Minimum milliseconds a file must be unchanged before the periodic sweep considers it
     * complete. Not applied to files queued by the FileObserver (CLOSE_WRITE = already done).
     */
    private const val STABLE_DELAY_MS = 10_000L

    /** Call log window: look for calls within ±5 minutes of the file timestamp. */
    private val CALL_LOG_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)

    /** Audio file extensions we recognise. */
    val AUDIO_EXTENSIONS = setOf("ogg", "opus", "m4a", "mp3", "aac", "wav", "flac", "3gp", "3gpp", "amr")

    /** Telegram Bot API timeouts — generous because files can be large. */
    private const val CONNECT_MS = 15_000
    private const val READ_MS    = 120_000   // 2 min — large files may take time

    // ──────────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Scans all watched folders for new STABLE recordings and enqueues them in [RecordingStore].
     * Called by the periodic sweep worker; the FileObserver service queues files directly.
     * Returns the number of newly discovered files.
     */
    fun scan(context: Context): Int {
        val store = RecordingStore.get(context)
        var newCount = 0
        for (dir in watchedDirectories()) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                if (ext !in AUDIO_EXTENSIONS) return@forEach
                if (!isStable(file)) {
                    Log.d(TAG, "scan: skipping unstable file: ${file.name}")
                    return@forEach
                }

                val key = dedupeKey(file)
                if (store.insertIfNew(key, file.absolutePath)) {
                    Log.i(TAG, "scan: new recording queued: ${file.name}")
                    newCount++
                }
            }
        }
        return newCount
    }

    /**
     * Uploads all pending recordings from [RecordingStore] to Telegram.
     *
     * @return [Outcome] describing the result of the batch.
     */
    fun uploadPending(context: Context): Outcome {
        val app = context.applicationContext
        val settings = SecureSettings(app)

        if (!settings.recordingForwardingEnabled) {
            Log.d(TAG, "uploadPending: recording forwarding disabled")
            return Outcome.DISABLED
        }

        val token  = settings.botToken
        val chatId = settings.chatId
        if (token == null || chatId == null) {
            Log.w(TAG, "uploadPending: missing credentials")
            return Outcome.NOT_CONFIGURED
        }

        val store   = RecordingStore.get(app)
        val pending = store.pendingPaths()
        if (pending.isEmpty()) {
            Log.d(TAG, "uploadPending: no pending recordings")
            return Outcome.DONE
        }

        Log.d(TAG, "uploadPending: uploading ${pending.size} recording(s)")
        var hadTransientError = false

        for ((id, path) in pending) {
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "uploadPending: file gone, marking failed: $path")
                store.markFailed(id)
                continue
            }

            val caption = buildCaption(app, file)
            Log.d(TAG, "uploadPending: sending ${file.name}")
            when (val result = TelegramAudioSender.sendAudioFile(
                token, chatId, file, caption, CONNECT_MS, READ_MS
            )) {
                TelegramClient.Result.Success -> {
                    Log.i(TAG, "uploadPending: uploaded ${file.name}")
                    store.markSent(id)
                }
                is TelegramClient.Result.Transient -> {
                    Log.w(TAG, "uploadPending: transient error for ${file.name}: ${result.reason}")
                    hadTransientError = true
                    // Leave status as PENDING — will retry next time.
                    break  // Stop on first transient error to avoid hammering the API.
                }
                is TelegramClient.Result.Credentials -> {
                    Log.e(TAG, "uploadPending: credentials error: ${result.reason}")
                    return Outcome.CREDENTIALS_ERROR
                }
                is TelegramClient.Result.Rejected -> {
                    Log.e(TAG, "uploadPending: rejected ${file.name}: ${result.reason}")
                    store.markFailed(id)
                }
            }
        }

        return if (hadTransientError) Outcome.RETRY else Outcome.DONE
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Dedup key — public so RecordingMonitorService can insert immediately on CLOSE_WRITE
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * A unique key combining absolute path and last-modified time so the same file is never
     * re-sent. Public so the monitor service can queue a file directly on CLOSE_WRITE without
     * waiting for the periodic sweep.
     */
    fun dedupeKey(file: File): String {
        val raw = "${file.absolutePath}:${file.lastModified()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Caption building
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the Telegram caption for a recording.
     *
     * ```
     * 🎙 Voicemail
     * 📞 From: +639171234567
     * 🕒 2026-10-20 02:30:00 PM
     * 📄 filename.amr
     * ```
     *
     * The caller number is extracted from the filename first (Samsung encodes it there);
     * if not found, the Call Log is queried by timestamp (requires READ_CALL_LOG permission).
     */
    internal fun buildCaption(context: Context, file: File): String {
        val typeLabel = recordingTypeLabel(file)
        val time = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault())
            .format(Date(file.lastModified()))
        val caller = extractCallerNumber(context, file)

        return buildString {
            append("\uD83C\uDF99 $typeLabel\n")          // 🎙 Voicemail / Call Recording / Recording
            if (caller != null) {
                append("\uD83D\uDCDE From: $caller\n")   // 📞 From: +639171234567
            }
            append("\uD83D\uDD52 $time\n")               // 🕒 timestamp
            append("\uD83D\uDCC4 ${file.name}")          // 📄 filename
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Caller number extraction
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Tries to find the caller's phone number for [file]:
     * 1. Parse the filename — Samsung (and most OEMs) embed the number in the name.
     * 2. Query the Call Log within ±[CALL_LOG_WINDOW_MS] of the file's last-modified time
     *    (requires READ_CALL_LOG permission; silently skipped if not granted).
     */
    fun extractCallerNumber(context: Context, file: File): String? =
        extractFromFilename(file.nameWithoutExtension)
            ?: extractFromCallLog(context, file.lastModified())

    /**
     * Parses a caller number from common Samsung / OEM recording filename patterns.
     *
     * Known formats:
     * - `Voicemail_+639171234567_20261020_143023`      (Samsung One UI, international)
     * - `Voicemail_639171234567_20261020_143023`        (Samsung One UI, local)
     * - `[2026-10-20 14:30:23] +639171234567`          (some Samsung call recordings)
     * - `Record_20261020_143023_+639171234567`
     * - `Call_+639171234567_20261020_143023`
     * - `20261020_143023_+639171234567`
     *
     * Heuristic: extract the first token that looks like a phone number (7–15 digits, optional
     * leading +) and whose digit-only form is NOT a pure date (8 digits) or time (6 digits),
     * unless it starts with + (which clearly marks it as a number, not a timestamp).
     */
    private fun extractFromFilename(name: String): String? {
        val tokenRegex = Regex("""(?<![0-9])(\+?[0-9]{7,15})(?![0-9])""")
        return tokenRegex.findAll(name)
            .map { it.groupValues[1] }
            .firstOrNull { token ->
                val digits = token.trimStart('+')
                // Keep if it starts with + (definitely a number) OR digit count is not 6 or 8
                token.startsWith('+') || digits.length !in setOf(6, 8)
            }
    }

    /**
     * Queries the system Call Log for the most recent call within ±[CALL_LOG_WINDOW_MS] of
     * [fileTimeMs]. Returns the caller number, or null.
     *
     * Requires [android.Manifest.permission.READ_CALL_LOG]; silently returns null otherwise.
     */
    private fun extractFromCallLog(context: Context, fileTimeMs: Long): String? {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "extractFromCallLog: READ_CALL_LOG not granted, skipping")
            return null
        }
        return try {
            val from = fileTimeMs - CALL_LOG_WINDOW_MS
            val to   = fileTimeMs + CALL_LOG_WINDOW_MS
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFromCallLog: exception: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun recordingTypeLabel(file: File): String {
        val path = file.absolutePath
        return when {
            path.contains("voicemail", ignoreCase = true) -> "Voicemail"
            path.contains("call", ignoreCase = true)      -> "Call Recording"
            else                                           -> "Recording"
        }
    }

    /** Stable = file was last modified more than [STABLE_DELAY_MS] ago. */
    private fun isStable(file: File): Boolean =
        System.currentTimeMillis() - file.lastModified() >= STABLE_DELAY_MS

    /**
     * Returns the list of directories to watch. Samsung One UI stores voicemails in
     * `Recordings/Voicemail` and call recordings in `Recordings/Call` under external storage.
     * Additional common OEM paths are included as a convenience.
     */
    fun watchedDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            // Samsung One UI (primary targets)
            File(root, "Recordings/Voicemail"),
            File(root, "Recordings/Call"),
            // Alternative Samsung path
            File(root, "DCIM/Voicemail"),
            // Other common OEM paths
            File(root, "CallRecordings"),
            File(root, "PhoneRecord"),
            File(root, "Record/Call Recording"),
            // Xiaomi MIUI
            File(root, "MIUI/sound_recorder/call_rec"),
        )
    }

    enum class Outcome {
        DONE,
        RETRY,
        DISABLED,
        NOT_CONFIGURED,
        CREDENTIALS_ERROR
    }
}
