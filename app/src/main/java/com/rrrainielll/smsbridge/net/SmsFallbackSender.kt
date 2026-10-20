package com.rrrainielll.smsbridge.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sends a plain SMS via the device's telephony stack.
 *
 * Used as a last-resort fallback when every Telegram delivery attempt fails (e.g. no internet,
 * bad credentials, or Telegram refusing the message). Requires [Manifest.permission.SEND_SMS].
 *
 * The message is split by [SmsManager.divideMessage] to respect the GSM-7 / UCS-2 length limits.
 */
object SmsFallbackSender {

    private const val TAG = "SmsFallbackSender"

    sealed interface Result {
        data object Success : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Sends [text] as one or more SMS parts to [phoneNumber].
     * Returns [Result.Failure] (and logs) without throwing if the permission is missing or if the
     * telephony stack rejects the request.
     */
    fun send(context: Context, phoneNumber: String, text: String): Result {
        // Guard: SEND_SMS is a runtime-granted dangerous permission; check before calling SmsManager.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val msg = "SEND_SMS permission not granted; cannot send fallback SMS."
            Log.e(TAG, msg)
            return Result.Failure(msg)
        }

        return try {
            val manager = getSmsManager(context)
            val parts = manager.divideMessage(text)
            Log.i(TAG, "Sending fallback SMS to $phoneNumber (${parts.size} part(s), ~${text.length} chars).")
            if (parts.size == 1) {
                manager.sendTextMessage(phoneNumber, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            Log.i(TAG, "Fallback SMS dispatched successfully to $phoneNumber.")
            Result.Success
        } catch (e: Exception) {
            val msg = "SMS fallback failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg)
            Result.Failure(msg)
        }
    }

    @Suppress("DEPRECATION") // getDefault() used only on API < 31
    private fun getSmsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
}
