package com.rrrainielll.smsbridge.net

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Minimal WhatsApp Business Cloud API client (text messages only), built on [HttpURLConnection]
 * so no third-party library ever sees the access token or the SMS content.
 *
 * Security: the access token is passed as a Bearer header; it never appears in a URL, so it is
 * not included in any exception message. Only the fixed human-readable reason strings below leave
 * this class.
 *
 * API reference: https://developers.facebook.com/docs/whatsapp/cloud-api/messages/text-messages
 */
object WhatsAppClient {

    private const val TAG = "WhatsAppClient"
    private const val API_VERSION = "v21.0"

    sealed interface Result {
        data object Success : Result

        /** Temporary problem (offline, timeout, rate limit, server error). Retry later. */
        data class Transient(val reason: String) : Result

        /** Bad token / phone number ID or permission error. Retrying won't help until fixed. */
        data class Credentials(val reason: String) : Result

        /** Meta rejected this particular message (bad recipient, policy, etc.). */
        data class Rejected(val reason: String) : Result
    }

    /**
     * Sends [text] to [recipientWaId] (E.164 phone number, e.g. "+60123456789") via the
     * WhatsApp Cloud API.
     *
     * @param phoneNumberId  The numeric Phone Number ID from the Meta developer dashboard
     *                       (NOT the actual phone number).
     * @param accessToken    A permanent System User access token with whatsapp_business_messaging
     *                       permission.
     * @param recipientWaId  The recipient's WhatsApp number in E.164 format.
     */
    fun sendMessage(
        phoneNumberId: String,
        accessToken: String,
        recipientWaId: String,
        text: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Result {
        Log.d(TAG, "sendMessage: recipient=$recipientWaId, text length=${text.length}")
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://graph.facebook.com/$API_VERSION/$phoneNumberId/messages")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val payload = JSONObject()
                .put("messaging_product", "whatsapp")
                .put("to", recipientWaId)
                .put("type", "text")
                .put("text", JSONObject().put("body", text).put("preview_url", false))
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.d(TAG, "sendMessage: HTTP response code=$code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = classify(code, body)
            Log.d(TAG, "sendMessage: result=${result.javaClass.simpleName}")
            return result
        } catch (e: UnknownHostException) {
            Log.w(TAG, "sendMessage: UnknownHostException — no internet")
            return Result.Transient("No internet connection.")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "sendMessage: SocketTimeoutException")
            return Result.Transient("Timed out while contacting WhatsApp.")
        } catch (e: SSLException) {
            Log.w(TAG, "sendMessage: SSLException")
            return Result.Transient("Secure connection to WhatsApp failed. Check the phone's date and time.")
        } catch (e: IOException) {
            Log.w(TAG, "sendMessage: IOException: ${e.javaClass.simpleName}")
            return Result.Transient("Network error while contacting WhatsApp.")
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
            return Result.Transient("Unexpected error while contacting WhatsApp.")
        } finally {
            conn?.disconnect()
        }
    }

    private fun classify(code: Int, body: String): Result {
        val json = try { JSONObject(body) } catch (e: JSONException) { null }

        if (code in 200..299) {
            val hasId = json?.optJSONArray("messages")?.optJSONObject(0)?.optString("id")?.isNotEmpty() == true
            if (hasId) return Result.Success
            if (json != null) return Result.Success
        }

        val errorObj = json?.optJSONObject("error")
        val errorCode = errorObj?.optInt("code", 0) ?: 0
        val errorMsg = errorObj?.optString("message").orEmpty()
        val errorType = errorObj?.optString("type").orEmpty()

        Log.w(TAG, "classify: HTTP $code, errorCode=$errorCode, type=$errorType, msg=$errorMsg")

        return when {
            code == 401 || errorCode == 190 ->
                Result.Credentials("WhatsApp access token is invalid or has expired.")
            code == 403 || errorCode == 200 || errorCode == 10 ->
                Result.Credentials("WhatsApp: permission denied. Check the token scope.")
            errorCode == 131030 ->
                Result.Credentials("WhatsApp recipient is not on WhatsApp or has not opted in.")
            code == 429 || errorCode == 130429 ->
                Result.Transient("WhatsApp is rate limiting requests.")
            code >= 500 || errorCode == 131056 ->
                Result.Transient("WhatsApp service is temporarily unavailable.")
            code == 400 && (errorCode == 100 || "invalid" in errorType.lowercase()) ->
                Result.Rejected(
                    "WhatsApp rejected the message" +
                        errorMsg.take(100).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                )
            else ->
                Result.Transient("Unexpected response from WhatsApp (HTTP $code).")
        }
    }
}
