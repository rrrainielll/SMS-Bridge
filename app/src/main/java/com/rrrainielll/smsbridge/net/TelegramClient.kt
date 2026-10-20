package com.rrrainielll.smsbridge.net

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Minimal Telegram Bot API client (sendMessage only) built on HttpURLConnection, so no third party
 * code ever sees the bot token or the SMS content.
 *
 * Security: the token is part of the request URL, so exception messages (which can contain URLs) are
 * never logged or surfaced. Only the fixed human-readable reasons below leave this class.
 */
object TelegramClient {

    private const val TAG = "TelegramClient"

    sealed interface Result {
        data object Success : Result

        /** Temporary problem (offline, timeout, rate limit, server error). Retry later. */
        data class Transient(val reason: String, val retryAfterSec: Int? = null) : Result

        /** Bad token, bad chat ID, or the bot may not post to the chat. Retrying won't help until fixed. */
        data class Credentials(val reason: String) : Result

        /** Telegram refused this particular message. */
        data class Rejected(val reason: String) : Result
    }

    sealed interface ResolveResult {
        /** Successfully resolved — contains the numeric chat ID as a string. */
        data class Resolved(val chatId: String) : ResolveResult

        /** Temporary network/server problem; caller may retry. */
        data class Transient(val reason: String) : ResolveResult

        /** The username does not exist or the bot cannot access it. */
        data class NotFound(val reason: String) : ResolveResult

        /** Bad token. */
        data class Credentials(val reason: String) : ResolveResult
    }

    fun sendMessage(
        token: String,
        chatId: String,
        text: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        parseMode: String? = null
    ): Result {
        Log.d(TAG, "sendMessage: chatId=$chatId, text length=${text.length}, connectTimeout=$connectTimeoutMs, readTimeout=$readTimeoutMs")
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val payload = JSONObject()
                .put("chat_id", chatIdValue(chatId))
                .put("text", text)
                .put("link_preview_options", JSONObject().put("is_disabled", true))
            if (parseMode != null) payload.put("parse_mode", parseMode)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.d(TAG, "sendMessage: HTTP response code=$code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = classify(code, body)
            Log.d(TAG, "sendMessage: result=${result.javaClass.simpleName}")
            return result
        } catch (e: MalformedURLException) {
            Log.e(TAG, "sendMessage: malformed URL (bad token format)")
            return Result.Credentials("The bot token is not valid.")
        } catch (e: UnknownHostException) {
            Log.w(TAG, "sendMessage: UnknownHostException — no internet")
            return Result.Transient("No internet connection.")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "sendMessage: SocketTimeoutException")
            return Result.Transient("Timed out while contacting Telegram.")
        } catch (e: SSLException) {
            Log.w(TAG, "sendMessage: SSLException")
            return Result.Transient("Secure connection to Telegram failed. Check the phone's date and time.")
        } catch (e: IOException) {
            Log.w(TAG, "sendMessage: IOException: ${e.javaClass.simpleName}")
            return Result.Transient("Network error while contacting Telegram.")
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
            return Result.Transient("Unexpected error while contacting Telegram.")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Resolves a Telegram @username to a numeric chat ID by scanning the bot's recent update
     * history (getUpdates). This is the only reliable way to look up a private user's chat ID —
     * getChat only works for public channels and groups, not personal accounts.
     *
     * The user must have sent at least one message to the bot before this is called.
     * Must be called on a background thread.
     */
    fun resolveUsername(
        token: String,
        username: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): ResolveResult {
        // Strip leading '@' for comparison; Telegram usernames are case-insensitive.
        val target = username.removePrefix("@").lowercase()
        Log.d(TAG, "resolveUsername: target=$target")
        var conn: HttpURLConnection? = null
        try {
            // getUpdates with limit=100 and a large negative offset (-100) to get the most
            // recent 100 updates without consuming (acknowledging) them.
            val url = URL("https://api.telegram.org/bot$token/getUpdates")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val payload = JSONObject()
                .put("limit", 100)
                .put("allowed_updates", org.json.JSONArray().put("message").put("my_chat_member"))
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.d(TAG, "resolveUsername: HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (e: JSONException) { null }

            if (code == 401 || code == 404) return ResolveResult.Credentials("Telegram rejected the bot token.")
            if (code == 429 || code >= 500) return ResolveResult.Transient("Telegram is temporarily unavailable. Try again.")
            if (code !in 200..299 || json?.optBoolean("ok") != true) {
                return ResolveResult.Transient("Unexpected response from Telegram (HTTP $code).")
            }

            val updates = json.optJSONArray("result") ?: return ResolveResult.NotFound(
                "No messages found. Send any message to the bot in Telegram first, then try again."
            )

            // Walk updates newest-first, check message.from.username and chat.username.
            for (i in updates.length() - 1 downTo 0) {
                val update = updates.optJSONObject(i) ?: continue

                // Check message sender
                val msg = update.optJSONObject("message")
                if (msg != null) {
                    val from = msg.optJSONObject("from")
                    val fromUsername = from?.optString("username", "")?.lowercase()
                    if (fromUsername == target) {
                        val chatId = msg.optJSONObject("chat")?.optLong("id", 0)?.takeIf { it != 0L }
                        if (chatId != null) {
                            Log.d(TAG, "resolveUsername: found chat ID $chatId for @$target via message.from")
                            return ResolveResult.Resolved(chatId.toString())
                        }
                    }
                    // Also check chat.username (for group/channel messages)
                    val chatUsername = msg.optJSONObject("chat")?.optString("username", "")?.lowercase()
                    if (chatUsername == target) {
                        val chatId = msg.optJSONObject("chat")?.optLong("id", 0)?.takeIf { it != 0L }
                        if (chatId != null) {
                            Log.d(TAG, "resolveUsername: found chat ID $chatId for @$target via message.chat")
                            return ResolveResult.Resolved(chatId.toString())
                        }
                    }
                }

                // Check my_chat_member (bot was added/started by the user)
                val chatMember = update.optJSONObject("my_chat_member")
                if (chatMember != null) {
                    val from = chatMember.optJSONObject("from")
                    val fromUsername = from?.optString("username", "")?.lowercase()
                    if (fromUsername == target) {
                        val chatId = chatMember.optJSONObject("chat")?.optLong("id", 0)?.takeIf { it != 0L }
                        if (chatId != null) {
                            Log.d(TAG, "resolveUsername: found chat ID $chatId for @$target via my_chat_member")
                            return ResolveResult.Resolved(chatId.toString())
                        }
                    }
                }
            }

            Log.w(TAG, "resolveUsername: @$target not found in ${updates.length()} updates")
            return ResolveResult.NotFound(
                "No messages from @$target found in the bot's history. " +
                    "Send any message (e.g. /start) to the bot in Telegram, then try again."
            )
        } catch (e: MalformedURLException) {
            return ResolveResult.Credentials("The bot token is not valid.")
        } catch (e: UnknownHostException) {
            return ResolveResult.Transient("No internet connection.")
        } catch (e: SocketTimeoutException) {
            return ResolveResult.Transient("Timed out while contacting Telegram.")
        } catch (e: SSLException) {
            return ResolveResult.Transient("Secure connection to Telegram failed.")
        } catch (e: IOException) {
            return ResolveResult.Transient("Network error while contacting Telegram.")
        } catch (e: Exception) {
            Log.e(TAG, "resolveUsername: unexpected: ${e.javaClass.simpleName}: ${e.message}")
            return ResolveResult.Transient("Unexpected error while contacting Telegram.")
        } finally {
            conn?.disconnect()
        }
    }

    /** Numeric IDs are sent as numbers, @channelnames as strings. */
    private fun chatIdValue(chatId: String): Any = chatId.toLongOrNull() ?: chatId

    private fun classify(code: Int, body: String): Result {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            null
        }
        if (code in 200..299 && json?.optBoolean("ok") == true) return Result.Success

        val description = json?.optString("description").orEmpty()
        val lower = description.lowercase()
        val retryAfter = json?.optJSONObject("parameters")?.optInt("retry_after", 0)?.takeIf { it > 0 }

        Log.w(TAG, "classify: HTTP $code, description=$description")

        return when {
            code == 429 -> Result.Transient("Telegram is rate limiting requests.", retryAfter)
            code >= 500 -> Result.Transient("Telegram is temporarily unavailable.")
            code == 401 || code == 404 -> Result.Credentials("Telegram rejected the bot token.")
            code == 403 -> Result.Credentials(
                "The bot is not allowed to message this chat. Open the bot in Telegram and press Start."
            )
            code == 400 && ("chat" in lower || "peer" in lower || "user" in lower) -> Result.Credentials(
                "Telegram could not find or use this chat ID."
            )
            code == 400 -> Result.Rejected(
                "Telegram rejected the message" + (description.take(100).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
            )
            else -> Result.Transient("Unexpected response from Telegram (HTTP $code).")
        }
    }
}
