package com.rrrainielll.smsbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    initialChatId: String,
    initialFallbackSmsNumber: String,
    onNavigateBack: () -> Unit,
    onToggleForwarding: () -> Unit,
    onToggleRecordingForwarding: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onSaveCredentials: (token: String, chatId: String, fallbackSmsNumber: String) -> String?,
    onResolveAndSave: (token: String, chatId: String, fallbackSmsNumber: String, onResult: (String?) -> Unit) -> Unit,
    onSetBatteryReportInterval: (Int) -> Unit,
    onTest: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // ── Forwarding ────────────────────────────────────────────
                SettingsSectionHeader("Forwarding")

                ToggleSettingRow(
                    title = "SMS Forwarding",
                    subtitle = if (state.forwardingEnabled) "Forwarding incoming SMS to Telegram"
                               else "Not forwarding SMS",
                    checked = state.forwardingEnabled,
                    enabled = true,
                    onCheckedChange = { onToggleForwarding() }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))

                ToggleSettingRow(
                    title = "Voicemail & Call Recording",
                    subtitle = when {
                        !state.storageGranted -> "Storage permission required"
                        state.recordingForwardingEnabled -> "Forwarding recordings to Telegram"
                        else -> "Not forwarding recordings"
                    },
                    checked = state.recordingForwardingEnabled,
                    enabled = state.storageGranted || state.recordingForwardingEnabled,
                    onCheckedChange = { onToggleRecordingForwarding() }
                )

                if (!state.storageGranted) {
                    TextButton(
                        onClick = onRequestStoragePermission,
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text("Grant storage access")
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ── Telegram ──────────────────────────────────────────────
                SettingsSectionHeader("Telegram")
                TelegramCredentialsSection(
                    state = state,
                    initialChatId = initialChatId,
                    onSave = onSaveCredentials,
                    onResolveAndSave = onResolveAndSave,
                    onTest = onTest
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ── SMS Fallback ──────────────────────────────────────────
                SettingsSectionHeader("SMS Fallback")
                SmsFallbackSection(
                    initialFallbackSmsNumber = initialFallbackSmsNumber,
                    initialChatId = initialChatId,
                    onSave = onSaveCredentials
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                // ── Battery Reporting ─────────────────────────────────────
                SettingsSectionHeader("Battery Reporting")
                BatteryReportingSection(
                    initialInterval = state.batteryReportIntervalMinutes,
                    onSetInterval = onSetBatteryReportInterval
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared layout primitives
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

/** A read-only summary row with an "Edit" button on the right. */
@Composable
private fun SummaryRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Telegram credentials — read/edit mode
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramCredentialsSection(
    state: UiState,
    initialChatId: String,
    onSave: (token: String, chatId: String, fallbackSmsNumber: String) -> String?,
    onResolveAndSave: (token: String, chatId: String, fallbackSmsNumber: String, onResult: (String?) -> Unit) -> Unit,
    onTest: () -> Unit
) {
    // Start in edit mode if credentials are not yet configured.
    var editing by rememberSaveable { mutableStateOf(!state.hasCredentials) }

    // Draft state — only matters while editing.
    var token by remember { mutableStateOf("") }
    var chatId by rememberSaveable { mutableStateOf(initialChatId) }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!editing) {
        // ── Read view ──────────────────────────────────────────────────
        SummaryRow(
            label = "Bot token",
            value = "Saved",
            onEdit = { editing = true }
        )
        SummaryRow(
            label = "Chat ID",
            value = initialChatId.ifEmpty { "Not set" },
            onEdit = { editing = true }
        )
        // Always show Test button in read view so users can verify without re-entering the token.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = state.busy == Busy.NONE && state.hasCredentials
            ) {
                if (state.busy == Busy.TESTING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text("Test connection")
            }
        }
    } else {
        // ── Edit view ──────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text("Bot token") },
                placeholder = { if (state.hasCredentials) Text("Saved — leave blank to keep") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chatId,
                onValueChange = { chatId = it; error = null },
                label = { Text("Telegram username or chat ID") },
                placeholder = { Text("@yourusername or 123456789") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Create a bot with @BotFather, then enter your Telegram @username " +
                    "(the app will look up your chat ID automatically) or paste your numeric chat ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // Reset drafts and return to read view.
                        token = ""
                        chatId = initialChatId
                        error = null
                        reveal = false
                        editing = false
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        onResolveAndSave(token, chatId, "") { result ->
                            if (result == null) {
                                token = ""
                                error = null
                                reveal = false
                                editing = false
                            } else {
                                error = result
                            }
                        }
                    },
                    enabled = state.busy == Busy.NONE,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.busy == Busy.RESOLVING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text("Save")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SMS Fallback — read/edit mode
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SmsFallbackSection(
    initialFallbackSmsNumber: String,
    initialChatId: String,
    onSave: (token: String, chatId: String, fallbackSmsNumber: String) -> String?
) {
    val hasNumber = initialFallbackSmsNumber.isNotEmpty()
    var editing by rememberSaveable { mutableStateOf(!hasNumber) }
    var fallbackNumber by rememberSaveable { mutableStateOf(initialFallbackSmsNumber) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!editing) {
        // ── Read view ──────────────────────────────────────────────────
        SummaryRow(
            label = "Fallback number",
            value = fallbackNumber.ifEmpty { "Disabled" },
            onEdit = { editing = true }
        )
    } else {
        // ── Edit view ──────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = fallbackNumber,
                onValueChange = { fallbackNumber = it; error = null },
                label = { Text("Fallback phone number (optional)") },
                placeholder = { Text("e.g. +60123456789") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "When Telegram is unreachable, the incoming SMS is forwarded as a plain SMS to this number. " +
                    "Leave blank to disable. Use international format, e.g. +60123456789.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        fallbackNumber = initialFallbackSmsNumber
                        error = null
                        editing = false
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        val result = onSave("", initialChatId, fallbackNumber)
                        if (result == null) {
                            error = null
                            editing = false
                        } else if (result.contains("fallback", ignoreCase = true) ||
                            result.contains("phone", ignoreCase = true)
                        ) {
                            error = result
                        } else {
                            // Unrelated error (e.g. chatId validation) — just close.
                            editing = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Battery reporting — read/edit mode
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BatteryReportingSection(
    initialInterval: Int,
    onSetInterval: (Int) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var batteryInterval by rememberSaveable { mutableStateOf(initialInterval) }

    if (!editing) {
        // ── Read view ──────────────────────────────────────────────────
        SummaryRow(
            label = "Report interval",
            value = batteryIntervalLabel(initialInterval),
            onEdit = { editing = true }
        )
    } else {
        // ── Edit view ──────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BatteryIntervalDropdown(
                selectedMinutes = batteryInterval,
                onSelect = { batteryInterval = it }
            )
            Text(
                "Sends battery level and charging status to Telegram at the chosen interval. " +
                    "Works even when SMS forwarding is disabled. Subject to Android background execution limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        batteryInterval = initialInterval
                        editing = false
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        onSetInterval(batteryInterval)
                        editing = false
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Battery interval dropdown
// ─────────────────────────────────────────────────────────────────────────────

private data class BatteryOption(val minutes: Int, val label: String)

private val BATTERY_OPTIONS = listOf(
    BatteryOption(0,    "Disabled"),
    BatteryOption(15,   "Every 15 minutes"),
    BatteryOption(30,   "Every 30 minutes"),
    BatteryOption(60,   "Every 1 hour"),
    BatteryOption(360,  "Every 6 hours"),
    BatteryOption(720,  "Every 12 hours"),
    BatteryOption(1440, "Every 24 hours")
)

private fun batteryIntervalLabel(minutes: Int): String =
    BATTERY_OPTIONS.firstOrNull { it.minutes == minutes }?.label ?: "Every $minutes minutes"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatteryIntervalDropdown(selectedMinutes: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = BATTERY_OPTIONS.firstOrNull { it.minutes == selectedMinutes } ?: BATTERY_OPTIONS[0]

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Battery report interval") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            BATTERY_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.minutes); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
