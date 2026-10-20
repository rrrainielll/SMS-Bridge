package com.rrrainielll.smsbridge.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    showIntro: Boolean,
    smsPermanentlyDenied: Boolean,
    onIntroContinue: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onToggleForwarding: () -> Unit,
    onRetry: () -> Unit,
    onTest: () -> Unit,
    onOpenSettings: () -> Unit,
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
                title = {
                    Text("SMSBridge", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status card with toggle switch
                ForwardingStatusCard(state = state, onToggle = onToggleForwarding, onTest = onTest)

                // Message stats
                StatsRow(state = state, onRetry = onRetry)

                // Setup checklist (only when setup is incomplete)
                val setupDone = state.smsGranted && state.hasCredentials && state.notificationsGranted
                if (!setupDone) {
                    SetupCard(
                        state = state,
                        smsPermanentlyDenied = smsPermanentlyDenied,
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onConfigure = onOpenSettings
                    )
                }

                InfoCard(onOpenBatterySettings)
            }
        }
    }

    if (showIntro) IntroDialog(onIntroContinue)
}

// ─────────────────────────────────────────────────────────────────────────────
// Forwarding status card — has the main toggle switch and status indicators
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ForwardingStatusCard(state: UiState, onToggle: () -> Unit, onTest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Main forwarding toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SMS Forwarding",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.forwardingEnabled) "Forwarding incoming SMS to Telegram"
                        else "Tap to start forwarding",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.forwardingEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics { contentDescription = "SMS forwarding toggle" }
                )
            }

            // Connection / error detail
            val detail = when {
                !state.hasCredentials -> "Add your bot token and chat ID in Settings."
                !state.connected && state.lastError != null -> state.lastError
                !state.connected -> "Tap \"Test\" to verify the connection."
                state.lastError != null -> "Last problem: ${state.lastError}"
                else -> null
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Status indicators row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusDot(label = "Telegram", positive = state.connected, modifier = Modifier.weight(1f))
                StatusDot(label = "SMS fallback", positive = state.hasSmsNumber, modifier = Modifier.weight(1f))
                StatusDot(
                    label = "Recording",
                    positive = state.recordingForwardingEnabled,
                    modifier = Modifier.weight(1f)
                )
            }

            // Test connection button (only when credentials are saved)
            if (state.hasCredentials) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = state.busy == Busy.NONE,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.busy == Busy.TESTING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Test connection")
                }
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, positive: Boolean, modifier: Modifier = Modifier) {
    val color = if (positive) StatusColors.ok else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (positive) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stats row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(state: UiState, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell("Forwarded", state.forwarded, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatCell(
                "Failed",
                state.failed,
                if (state.failed > 0) StatusColors.bad else MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f)
            )
            StatCell("Queued", state.queued, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (state.failed > 0 || state.queued > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Undelivered messages are kept and retried automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetry, enabled = state.busy == Busy.NONE) {
                    if (state.busy == Busy.RETRYING) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text("Retry now")
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, valueColor: Color, modifier: Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
                fontWeight = FontWeight.SemiBold
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup checklist card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SetupCard(
    state: UiState,
    smsPermanentlyDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onConfigure: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            SetupStep(
                done = state.smsGranted,
                title = "Allow SMS access",
                detail = when {
                    state.smsGranted -> "Granted"
                    smsPermanentlyDenied -> "Android is blocking the request. Open App info and allow SMS" +
                        (if (Build.VERSION.SDK_INT >= 33) " (if it is greyed out, use ⋮ → \"Allow restricted settings\" first)." else ".")
                    else -> "Needed to detect incoming messages."
                },
                actionLabel = if (smsPermanentlyDenied) "App info" else "Allow",
                onAction = if (smsPermanentlyDenied) onOpenAppSettings else onRequestPermissions
            )
            SetupStep(
                done = state.hasCredentials,
                title = "Connect Telegram",
                detail = if (state.hasCredentials) "Saved" else "Enter your bot token and chat ID.",
                actionLabel = "Configure",
                onAction = onConfigure
            )
            if (Build.VERSION.SDK_INT >= 33) {
                SetupStep(
                    done = state.notificationsGranted,
                    title = "Allow notifications (optional)",
                    detail = if (state.notificationsGranted) "Granted" else "Lets SMSBridge alert you if delivery breaks.",
                    actionLabel = "Allow",
                    onAction = onRequestPermissions
                )
            }
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String, detail: String, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (done) StatusColors.ok else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Info / "How it works" card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InfoCard(onOpenBatterySettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "While forwarding is running, every incoming SMS — including one-time codes and bank alerts — " +
                    "is sent, with its sender, text and time, to the Telegram chat you configured. " +
                    "Nothing is sent anywhere else, and the app has no analytics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Your bot token is stored encrypted. Messages that can't be delivered are kept encrypted on " +
                    "this phone, retried, and erased once delivered. Telegram bot chats are not end-to-end encrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenBatterySettings) {
                Text("Battery settings (keep SMSBridge unrestricted)")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Intro dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IntroDialog(onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Before you start") },
        text = {
            Text(
                "SMSBridge reads incoming SMS and forwards each one — sender, message and time — to a Telegram " +
                    "bot chat that you configure.\n\n" +
                    "That includes one-time passcodes and banking alerts, so only use a private chat that only you can read.\n\n" +
                    "Next, Android will ask for permission to receive SMS" +
                    (if (Build.VERSION.SDK_INT >= 33) " and to show notifications" else "") + "."
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } }
    )
}
