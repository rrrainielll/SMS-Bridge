package com.rrrainielll.smsbridge.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rrrainielll.smsbridge.data.SecureSettings

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settings: SecureSettings

    private var showIntro by mutableStateOf(false)
    private var smsPermanentlyDenied by mutableStateOf(false)
    /** True when the user has navigated to the Settings screen. */
    private var showSettings by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionFlags()
            viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = SecureSettings(this)
        showIntro = !settings.introSeen

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Back-press on Settings screen navigates home.
            BackHandler(enabled = showSettings) { showSettings = false }

            SmsBridgeTheme {
                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        initialChatId = viewModel.savedChatId(),
                        initialFallbackSmsNumber = viewModel.savedFallbackSmsNumber(),
                        onNavigateBack = { showSettings = false },
                        onToggleForwarding = viewModel::toggleForwarding,
                        onToggleRecordingForwarding = viewModel::toggleRecordingForwarding,
                        onRequestStoragePermission = ::requestStoragePermission,
                        onSaveCredentials = viewModel::saveCredentials,
                        onResolveAndSave = viewModel::resolveAndSave,
                        onSetBatteryReportInterval = viewModel::setBatteryReportInterval,
                        onTest = viewModel::testConnection,
                        onMessageShown = viewModel::consumeMessage,
                    )
                } else {
                    MainScreen(
                        state = state,
                        showIntro = showIntro,
                        smsPermanentlyDenied = smsPermanentlyDenied,
                        onIntroContinue = {
                            settings.introSeen = true
                            showIntro = false
                            requestPermissions()
                        },
                        onRequestPermissions = ::requestPermissions,
                        onOpenAppSettings = ::openAppSettings,
                        onOpenBatterySettings = ::openBatterySettings,
                        onToggleForwarding = viewModel::toggleForwarding,
                        onRetry = viewModel::retryNow,
                        onTest = viewModel::testConnection,
                        onOpenSettings = { showSettings = true },
                        onMessageShown = viewModel::consumeMessage,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionFlags()
        // If SMS permission not granted and not permanently denied, prompt the user.
        if (!granted(Manifest.permission.RECEIVE_SMS) && !smsPermanentlyDenied) {
            requestPermissions()
        }
        viewModel.refresh()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updatePermissionFlags() {
        smsPermanentlyDenied = !granted(Manifest.permission.RECEIVE_SMS) &&
            settings.smsPermissionRequested &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)
    }

    private fun requestPermissions() {
        val needed = buildList {
            if (!granted(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (!granted(Manifest.permission.READ_SMS)) add(Manifest.permission.READ_SMS)
            // SEND_SMS is required for the SMS fallback feature.
            if (!granted(Manifest.permission.SEND_SMS)) add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) return
        settings.smsPermissionRequested = true
        permissionLauncher.launch(needed.toTypedArray())
    }

    /** Requests the storage + call-log permissions needed for reading recordings and caller info. */
    private fun requestStoragePermission() {
        val needed = buildList {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (!granted(audioPermission)) add(audioPermission)
            // READ_CALL_LOG lets us look up the caller number by timestamp when the filename
            // doesn't embed it. Optional — recording forwarding works without it.
            if (!granted(Manifest.permission.READ_CALL_LOG)) add(Manifest.permission.READ_CALL_LOG)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun openBatterySettings() {
        // Opens the system list; needs no extra permission (unlike requesting the exemption directly).
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent) else openAppSettings()
    }
}
