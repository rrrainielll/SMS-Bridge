package com.rrrainielll.smsbridge.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rrrainielll.smsbridge.data.AppEvents
import com.rrrainielll.smsbridge.data.ConnectionState
import com.rrrainielll.smsbridge.data.MessageStore
import com.rrrainielll.smsbridge.data.RecordingStats
import com.rrrainielll.smsbridge.data.RecordingStore
import com.rrrainielll.smsbridge.data.SecureSettings
import com.rrrainielll.smsbridge.net.TelegramClient
import com.rrrainielll.smsbridge.receiver.RecordingMonitorService
import com.rrrainielll.smsbridge.work.Deliverer
import com.rrrainielll.smsbridge.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Busy { NONE, TESTING, RETRYING, RESOLVING }

data class UiState(
    val forwardingEnabled: Boolean = false,
    val smsGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val hasCredentials: Boolean = false,
    val hasSmsNumber: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null,
    val forwarded: Int = 0,
    val failed: Int = 0,
    val queued: Int = 0,
    val busy: Busy = Busy.NONE,
    val message: String? = null,
    /** Battery report interval in minutes; 0 = disabled. */
    val batteryReportIntervalMinutes: Int = 0,
    /** Whether recording (voicemail + call) forwarding is enabled. */
    val recordingForwardingEnabled: Boolean = false,
    /** Whether the storage permission required for reading recordings is granted. */
    val storageGranted: Boolean = false,
    /** Stats for recording forwarding (forwarded, failed, queued). */
    val recordingStats: RecordingStats = RecordingStats(0, 0, 0)
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SecureSettings(app)
    private val store = MessageStore.get(app)
    private val recordingStore = RecordingStore.get(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            // Re-read whenever the receiver/worker changes the queue or settings.
            AppEvents.tick.collect { refresh() }
        }
    }

    /** Re-reads settings, permissions and statistics (cheap; runs off the main thread). */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { load() }
            _state.update {
                snapshot.copy(busy = it.busy, message = it.message)
            }
        }
    }

    private fun load(): UiState {
        val ctx = getApplication<Application>()
        val stats = store.stats()
        val recStats = recordingStore.stats()
        val storageGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return UiState(
            forwardingEnabled = settings.forwardingEnabled,
            smsGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            hasCredentials = settings.hasCredentials(),
            hasSmsNumber = settings.hasSmsNumber(),
            connected = settings.hasCredentials() && settings.connection == ConnectionState.CONNECTED,
            lastError = settings.lastError,
            forwarded = stats.forwarded,
            failed = stats.failed,
            queued = stats.queued,
            batteryReportIntervalMinutes = settings.batteryReportIntervalMinutes,
            recordingForwardingEnabled = settings.recordingForwardingEnabled,
            storageGranted = storageGranted,
            recordingStats = recStats
        )
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** The saved chat ID, for pre-filling the settings dialog. The bot token is never exposed to the UI. */
    fun savedChatId(): String = settings.chatId.orEmpty()

    /** The saved fallback SMS number, for pre-filling the settings dialog. */
    fun savedFallbackSmsNumber(): String = settings.fallbackSmsNumber.orEmpty()

    private fun toast(text: String) = _state.update { it.copy(message = text) }

    fun toggleForwarding() {
        val ctx = getApplication<Application>()
        val s = _state.value
        if (s.forwardingEnabled) {
            settings.forwardingEnabled = false
            Scheduler.cancelAll(ctx)
            toast("Forwarding stopped. Incoming SMS are no longer forwarded.")
            return
        }
        when {
            !s.smsGranted -> toast("Allow SMS access first.")
            !s.hasCredentials -> toast("Add your Telegram bot token and chat ID first.")
            else -> {
                settings.forwardingEnabled = true
                Scheduler.ensureSweep(ctx)
                Scheduler.enqueueDelivery(ctx)
                toast("Forwarding started.")
            }
        }
    }

    /** Returns an error to display in the dialog, or null on success. */
    fun saveCredentials(token: String, chatId: String, fallbackSmsNumber: String): String? {
        val t = token.trim()
        val c = chatId.trim()
        val f = fallbackSmsNumber.trim()
        val keepToken = t.isEmpty() && settings.botToken != null
        if (!keepToken && !TOKEN_REGEX.matches(t)) {
            return "That doesn't look like a bot token (expected 123456789:AA… from @BotFather)."
        }
        if (!CHAT_REGEX.matches(c)) {
            return "Enter your Telegram username (e.g. @yourname) or numeric chat ID."
        }
        if (f.isNotEmpty() && !PHONE_REGEX.matches(f)) {
            return "The fallback number should be in international format, e.g. +60123456789."
        }
        settings.saveCredentials(if (keepToken) null else t, c, f.ifEmpty { null })
        if (settings.forwardingEnabled) Scheduler.enqueueDelivery(getApplication())
        toast("Settings saved. Use \"Test connection\" to verify.")
        return null
    }

    /**
     * Like [saveCredentials], but if [chatId] starts with '@', first calls the Telegram
     * [TelegramClient.resolveUsername] API to convert the username to a numeric chat ID.
     * Returns an error string on failure, or null on success. Runs on the IO dispatcher.
     */
    fun resolveAndSave(
        token: String,
        chatId: String,
        fallbackSmsNumber: String,
        onResult: (error: String?) -> Unit
    ) {
        val c = chatId.trim()
        if (!c.startsWith("@")) {
            // Not a username — validate and save synchronously.
            onResult(saveCredentials(token, c, fallbackSmsNumber))
            return
        }
        // Need the resolved token to call the API.
        val t = token.trim()
        val effectiveToken = if (t.isEmpty()) settings.botToken else t
        if (effectiveToken == null) {
            onResult("Add your bot token first so we can look up the username.")
            return
        }
        _state.update { it.copy(busy = Busy.RESOLVING) }
        viewModelScope.launch {
            val resolveResult = withContext(Dispatchers.IO) {
                TelegramClient.resolveUsername(effectiveToken, c)
            }
            _state.update { it.copy(busy = Busy.NONE) }
            when (resolveResult) {
                is TelegramClient.ResolveResult.Resolved -> {
                    val err = saveCredentials(token, resolveResult.chatId, fallbackSmsNumber)
                    onResult(err)
                }
                is TelegramClient.ResolveResult.NotFound ->
                    onResult(resolveResult.reason)
                is TelegramClient.ResolveResult.Transient ->
                    onResult(resolveResult.reason)
                is TelegramClient.ResolveResult.Credentials ->
                    onResult(resolveResult.reason)
            }
        }
    }

    fun testConnection() {
        if (_state.value.busy != Busy.NONE) return
        _state.update { it.copy(busy = Busy.TESTING) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Deliverer.testConnection(getApplication()) }
            _state.update { it.copy(busy = Busy.NONE, message = result.message) }
        }
    }

    /** Re-queues rejected messages and tries to deliver everything that is waiting, right now. */
    fun retryNow() {
        if (_state.value.busy != Busy.NONE) return
        if (!settings.forwardingEnabled) {
            toast("Start forwarding first.")
            return
        }
        _state.update { it.copy(busy = Busy.RETRYING) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                store.requeueFailed()
                Deliverer.processPending(getApplication(), Deliverer.Mode.FULL)
            }
            val text = when (outcome) {
                Deliverer.Outcome.DONE -> "All messages delivered."
                Deliverer.Outcome.RETRY -> settings.lastError ?: "Couldn't deliver yet. Will retry automatically."
                Deliverer.Outcome.BLOCKED -> settings.lastError ?: "Telegram settings need attention."
                Deliverer.Outcome.NOT_CONFIGURED -> "Add your Telegram bot token and chat ID first."
                Deliverer.Outcome.DISABLED -> "Start forwarding first."
            }
            _state.update { it.copy(busy = Busy.NONE, message = text) }
        }
    }

    /**
     * Persists the battery report interval and re-arms (or cancels) the WorkManager schedule.
     * [intervalMinutes] must be one of the allowed values (0 = disabled, or a positive number of minutes).
     */
    fun setBatteryReportInterval(intervalMinutes: Int) {
        val ctx = getApplication<Application>()
        settings.batteryReportIntervalMinutes = intervalMinutes
        Scheduler.ensureBatteryReport(ctx, intervalMinutes)
        val msg = if (intervalMinutes <= 0) {
            "Battery reports disabled."
        } else {
            val label = batteryIntervalLabel(intervalMinutes)
            "Battery reports set to every $label."
        }
        toast(msg)
    }

    /** Returns the saved battery report interval in minutes. */
    fun savedBatteryReportInterval(): Int = settings.batteryReportIntervalMinutes

    /**
     * Enables or disables recording forwarding. When enabled, starts the [RecordingMonitorService]
     * for real-time detection and schedules the periodic sweep worker.
     * Requires storage permission to be granted first.
     */
    fun toggleRecordingForwarding() {
        val ctx = getApplication<Application>()
        val s = _state.value
        if (s.recordingForwardingEnabled) {
            settings.recordingForwardingEnabled = false
            Scheduler.cancelRecordingWork(ctx)
            RecordingMonitorService.stop(ctx)
            toast("Recording forwarding stopped.")
        } else {
            when {
                !s.storageGranted -> toast("Allow storage access first (grant \"Files and media\" or \"Audio\" permission).")
                !s.hasCredentials -> toast("Add your Telegram bot token and chat ID first.")
                else -> {
                    settings.recordingForwardingEnabled = true
                    Scheduler.ensureRecordingSweep(ctx)
                    Scheduler.enqueueRecordingUpload(ctx)
                    RecordingMonitorService.start(ctx)
                    toast("Recording forwarding started.")
                }
            }
        }
    }

    private companion object {
        val TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")
        val CHAT_REGEX = Regex("^(-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,})$")
        /** International phone number: optional +, then 7-15 digits (allows spaces/dashes). */
        val PHONE_REGEX = Regex("^\\+?[\\d\\s\\-]{7,20}$")

        fun batteryIntervalLabel(minutes: Int) = when (minutes) {
            15   -> "15 minutes"
            30   -> "30 minutes"
            60   -> "1 hour"
            360  -> "6 hours"
            720  -> "12 hours"
            1440 -> "24 hours"
            else -> "$minutes minutes"
        }
    }
}
