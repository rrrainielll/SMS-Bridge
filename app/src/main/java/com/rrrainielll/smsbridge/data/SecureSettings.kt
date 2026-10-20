package com.rrrainielll.smsbridge.data

import android.content.Context
import androidx.core.content.edit

enum class ConnectionState { UNKNOWN, CONNECTED, FAILED }

/**
 * App configuration. The bot token and chat ID are stored encrypted (see [Crypto]); they are never
 * logged and never shown again in the UI once saved.
 */
class SecureSettings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether SMS forwarding is switched on ("service running"). */
    var forwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
            AppEvents.bump()
        }

    var introSeen: Boolean
        get() = prefs.getBoolean(KEY_INTRO, false)
        set(value) = prefs.edit { putBoolean(KEY_INTRO, value) }

    /** True once the user has been asked for the SMS permission (used to detect "don't ask again"). */
    var smsPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_SMS_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_SMS_REQUESTED, value) }

    val botToken: String?
        get() = prefs.getString(KEY_TOKEN, null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotEmpty() }

    val chatId: String?
        get() = prefs.getString(KEY_CHAT, null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotEmpty() }

    /**
     * Phone number to fall back to when Telegram is unreachable.
     * Null means SMS fallback is disabled.
     */
    val fallbackSmsNumber: String?
        get() = prefs.getString(KEY_FALLBACK_SMS, null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotEmpty() }

    fun hasSmsNumber(): Boolean = fallbackSmsNumber != null

    fun hasCredentials(): Boolean = botToken != null && chatId != null

    /** Saves the chat ID and, if [token] is non-null, the bot token. Resets the connection state. */
    fun saveCredentials(token: String?, chatId: String, fallbackSmsNumber: String?) {
        prefs.edit {
            if (token != null) putString(KEY_TOKEN, Crypto.encrypt(token))
            putString(KEY_CHAT, Crypto.encrypt(chatId))
            putInt(KEY_CONN, ConnectionState.UNKNOWN.ordinal)
            remove(KEY_ERROR)
            if (fallbackSmsNumber != null) {
                if (fallbackSmsNumber.isEmpty()) remove(KEY_FALLBACK_SMS)
                else putString(KEY_FALLBACK_SMS, Crypto.encrypt(fallbackSmsNumber))
            }
        }
        AppEvents.bump()
    }

    fun clearCredentials() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_CHAT)
            remove(KEY_FALLBACK_SMS)
            putInt(KEY_CONN, ConnectionState.UNKNOWN.ordinal)
            remove(KEY_ERROR)
        }
        AppEvents.bump()
    }

    val connection: ConnectionState
        get() = ConnectionState.entries.getOrElse(prefs.getInt(KEY_CONN, 0)) { ConnectionState.UNKNOWN }

    /** A short, non-sensitive description of the most recent delivery problem, if any. */
    val lastError: String?
        get() = prefs.getString(KEY_ERROR, null)

    fun setConnection(state: ConnectionState, error: String?) {
        prefs.edit {
            putInt(KEY_CONN, state.ordinal)
            if (error == null) remove(KEY_ERROR) else putString(KEY_ERROR, error)
        }
        AppEvents.bump()
    }

    /** Records a transient problem without changing the connection state. */
    fun setLastError(error: String?) {
        prefs.edit { if (error == null) remove(KEY_ERROR) else putString(KEY_ERROR, error) }
        AppEvents.bump()
    }

    /**
     * How often (in minutes) to send a battery status report to Telegram.
     * 0 means disabled.
     */
    var batteryReportIntervalMinutes: Int
        get() = prefs.getInt(KEY_BATTERY_INTERVAL, 0)
        set(value) {
            prefs.edit { putInt(KEY_BATTERY_INTERVAL, value) }
            AppEvents.bump()
        }

    /** Whether automatic voicemail and call recording forwarding is enabled. */
    var recordingForwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECORDING_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_RECORDING_ENABLED, value) }
            AppEvents.bump()
        }

    private companion object {
        const val FILE = "smsbridge_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTRO = "intro_seen"
        const val KEY_SMS_REQUESTED = "sms_requested"
        const val KEY_TOKEN = "token_enc"
        const val KEY_CHAT = "chat_enc"
        const val KEY_FALLBACK_SMS = "fallback_sms_enc"
        const val KEY_CONN = "conn_state"
        const val KEY_ERROR = "last_error"
        const val KEY_BATTERY_INTERVAL = "battery_report_interval_min"
        const val KEY_RECORDING_ENABLED = "recording_forwarding_enabled"
    }
}
