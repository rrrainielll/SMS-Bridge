package com.rrrainielll.smsbridge.net

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Sends an audio file to Telegram using the sendAudio Bot API method with multipart/form-data.
 * All logic is kept inside [TelegramClient] so no third-party code ever touches the token.
 *
 * Extension of [TelegramClient] — placed in a separate file to keep it focused.
 */
object TelegramAudioSender {

    private const val TAG = "TelegramAudioSender"
    private const val BOUNDARY = "smsbridge_boundary_12345"
    private const val CRLF = "\r\n"

    /**
     * Uploads [file] to Telegram as an audio attachment.
     *
     * [caption] is optional text shown below the file in Telegram.
     * Returns the same [TelegramClient.Result] sealed type as [TelegramClient.sendMessage].
     *
     * Telegram sendAudio accepts voice messages via the audio file types; to surface the file as
     * a playable voice note in Telegram we use sendVoice when the file is an OGG/OPUS recording,
     * and sendAudio otherwise so quality is preserved regardless of format.
     */
    fun sendAudioFile(
        token: String,
        chatId: String,
        file: File,
        caption: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): TelegramClient.Result {
        Log.d(TAG, "sendAudioFile: file=${file.name}, size=${file.length()}")
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "sendAudioFile: file not readable: ${file.absolutePath}")
            return TelegramClient.Result.Rejected("Recording file is not readable.")
        }
        if (file.length() == 0L) {
            Log.w(TAG, "sendAudioFile: file is empty: ${file.absolutePath}")
            return TelegramClient.Result.Rejected("Recording file is empty.")
        }

        // Choose sendVoice for OGG/OPUS (shows as voice note in Telegram) otherwise sendAudio.
        val ext = file.extension.lowercase()
        val method = if (ext == "ogg" || ext == "opus") "sendVoice" else "sendAudio"
        val fieldName = if (method == "sendVoice") "voice" else "audio"

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$token/$method")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            }

            conn.outputStream.use { out ->
                writeField(out, "chat_id", chatIdStr(chatId))
                if (caption.isNotBlank()) writeField(out, "caption", caption)
                writeFilePart(out, fieldName, file)
                out.write(("--$BOUNDARY--$CRLF").toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            Log.d(TAG, "sendAudioFile: HTTP response code=$code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            classifyResponse(code, body)

        } catch (e: MalformedURLException) {
            Log.e(TAG, "sendAudioFile: malformed URL (bad token format)")
            TelegramClient.Result.Credentials("The bot token is not valid.")
        } catch (e: UnknownHostException) {
            Log.w(TAG, "sendAudioFile: UnknownHostException — no internet")
            TelegramClient.Result.Transient("No internet connection.")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "sendAudioFile: SocketTimeoutException")
            TelegramClient.Result.Transient("Timed out while uploading to Telegram.")
        } catch (e: SSLException) {
            Log.w(TAG, "sendAudioFile: SSLException")
            TelegramClient.Result.Transient("Secure connection to Telegram failed.")
        } catch (e: IOException) {
            Log.w(TAG, "sendAudioFile: IOException: ${e.javaClass.simpleName}")
            TelegramClient.Result.Transient("Network error while uploading to Telegram.")
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFile: unexpected: ${e.javaClass.simpleName}: ${e.message}")
            TelegramClient.Result.Transient("Unexpected error while uploading to Telegram.")
        } finally {
            conn?.disconnect()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Multipart helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun writeField(out: OutputStream, name: String, value: String) {
        val header = "--$BOUNDARY${CRLF}Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write(CRLF.toByteArray(Charsets.UTF_8))
    }

    private fun writeFilePart(out: OutputStream, fieldName: String, file: File) {
        val mime = mimeType(file.extension.lowercase())
        val header = "--$BOUNDARY${CRLF}" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$CRLF" +
            "Content-Type: $mime$CRLF$CRLF"
        out.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.write(CRLF.toByteArray(Charsets.UTF_8))
    }

    private fun mimeType(ext: String) = when (ext) {
        "ogg", "opus" -> "audio/ogg"
        "m4a"         -> "audio/mp4"
        "mp3"         -> "audio/mpeg"
        "aac"         -> "audio/aac"
        "wav"         -> "audio/wav"
        "flac"        -> "audio/flac"
        "3gp", "3gpp" -> "audio/3gpp"
        "amr"         -> "audio/amr"
        else          -> "audio/octet-stream"
    }

    private fun chatIdStr(chatId: String): String = chatId

    // ──────────────────────────────────────────────────────────────────────────────
    // Response classification (mirrors TelegramClient.classify)
    // ──────────────────────────────────────────────────────────────────────────────

    private fun classifyResponse(code: Int, body: String): TelegramClient.Result {
        val json = try { org.json.JSONObject(body) } catch (_: Exception) { null }
        if (code in 200..299 && json?.optBoolean("ok") == true) return TelegramClient.Result.Success

        val description = json?.optString("description").orEmpty()
        val lower = description.lowercase()
        val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 0)?.takeIf { it > 0 }

        Log.w(TAG, "classifyResponse: HTTP $code, description=$description")
        return when {
            code == 429  -> TelegramClient.Result.Transient("Telegram is rate limiting requests.", retryAfter)
            code >= 500  -> TelegramClient.Result.Transient("Telegram is temporarily unavailable.")
            code == 401 || code == 404 -> TelegramClient.Result.Credentials("Telegram rejected the bot token.")
            code == 403  -> TelegramClient.Result.Credentials(
                "The bot is not allowed to message this chat. Open the bot in Telegram and press Start."
            )
            code == 400 && ("chat" in lower || "peer" in lower || "user" in lower) ->
                TelegramClient.Result.Credentials("Telegram could not find or use this chat ID.")
            code == 400  -> TelegramClient.Result.Rejected(
                "Telegram rejected the audio" + (description.take(100).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
            )
            else -> TelegramClient.Result.Transient("Unexpected response from Telegram (HTTP $code).")
        }
    }
}
