package com.guruprasad.developmenttask

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.guruprasad.developmenttask.accessibility.BleAccessibilityService
import com.guruprasad.developmenttask.ble.AndroidBleRepository
import com.guruprasad.developmenttask.ble.BleViewModel
import com.guruprasad.developmenttask.ble.BleForegroundService
import com.guruprasad.developmenttask.ble.ConnectionState
import com.guruprasad.developmenttask.ui.BleNavigation
import kotlin.reflect.KClass

/**
 * Application entry point for Android.
 *
 * Responsibilities:
 * - Requests BLE permissions at launch (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+;
 *   ACCESS_FINE_LOCATION + BLUETOOTH + BLUETOOTH_ADMIN on API ≤ 30).
 * - Observes [BleViewModel.isBluetoothEnabled] and shows a system
 *   [BluetoothAdapter.ACTION_REQUEST_ENABLE] dialog when Bluetooth is off.
 * - Binds to [BleForegroundService] when the GATT connection is established so the
 *   connection survives backgrounding; unbinds on disconnect or Bluetooth-off.
 * - Creates a single [AndroidBleRepository] (lazy) and a single [BleViewModel] scoped
 *   to the Activity, both passed into [BleNavigation] so all screens share one instance.
 * - Calls [AndroidBleRepository.release] in [onDestroy] to unregister the Bluetooth
 *   state receiver and prevent leaks.
 */
class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    private val bleRepository: AndroidBleRepository
        get() = (application as BleApplication).bleRepository

    private var showPermissionRationale by mutableStateOf(false)
    private var showBluetoothEnableDialog by mutableStateOf(false)
    private var showAccessibilityDialog by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            if (!allGranted) {
                Log.w(tag, "Some BLE permissions denied: $grants")
                showPermissionRationale = true
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                showBluetoothEnableDialog = true
            }
        }

    private var foregroundService: BleForegroundService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            foregroundService = (binder as BleForegroundService.LocalBinder).getService()
            Log.d(tag, "Foreground service bound")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            foregroundService = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val viewModelFactory = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            return BleViewModel(bleRepository) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        checkAccessibilityService()

        setContent {
            MaterialTheme {
                val viewModel: BleViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
                val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
                val connectionState by viewModel.connectionState.collectAsState()

                LaunchedEffect(isBluetoothEnabled) {
                    if (!isBluetoothEnabled) {
                        showBluetoothEnableDialog = true
                    }
                }

                LaunchedEffect(connectionState) {
                    when (connectionState) {
                        is ConnectionState.Connected -> bindForegroundService()
                        is ConnectionState.Disconnected,
                        is ConnectionState.BluetoothDisabled -> unbindForegroundService()
                        else -> {}
                    }
                }

                BleNavigation(repository = bleRepository, sharedViewModel = viewModel)

                if (showBluetoothEnableDialog) {
                    AlertDialog(
                        onDismissRequest = { showBluetoothEnableDialog = false },
                        title = { Text("Bluetooth is Off") },
                        text = { Text("Bluetooth is required to scan and connect to BLE devices. Would you like to enable it?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showBluetoothEnableDialog = false
                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }) { Text("Enable") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBluetoothEnableDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showPermissionRationale) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale = false },
                        title = { Text("Bluetooth Permission Required") },
                        text = { Text("This app needs Bluetooth permissions to scan and connect to BLE devices. Please grant them in Settings.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionRationale = false
                                openAppSettings()
                            }) { Text("Open Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionRationale = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showAccessibilityDialog) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityDialog = false },
                        title = { Text("Enable Accessibility Service") },
                        text = { Text("To log click events and visible screen text for debugging, please enable \"BLE App Accessibility Logger\" in Settings → Accessibility.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showAccessibilityDialog = false
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Open Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDialog = false }) { Text("Not Now") }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindForegroundService()
        // Do NOT call bleRepository.release() here — the repository lives at Application scope
        // and the foreground service must remain alive after the Activity is destroyed.
    }

    private fun bindForegroundService() {
        val intent = Intent(this, BleForegroundService::class.java)
        // Use 0 (not BIND_AUTO_CREATE) so the service lifecycle is driven exclusively by
        // startForegroundService / stopService, not by Activity bind/unbind.
        bindService(intent, serviceConnection, 0)
    }

    private fun unbindForegroundService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        foregroundService = null
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissionLauncher.launch(permissions)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    /**
     * Checks whether [BleAccessibilityService] is currently enabled.
     * If not, shows a dialog guiding the user to Settings → Accessibility.
     */
    private fun checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            Log.w(tag, "BleAccessibilityService is not enabled — showing prompt")
            showAccessibilityDialog = true
        } else {
            Log.d(tag, "BleAccessibilityService is already enabled")
        }
    }

    /**
     * Returns true if [BleAccessibilityService] is listed as an enabled accessibility service.
     * Checks [AccessibilityManager] enabled service list and falls back to
     * [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] string for reliability.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expectedComponent = ComponentName(this, BleAccessibilityService::class.java)
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val enabledViaManager = enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == expectedComponent.packageName &&
            it.resolveInfo.serviceInfo.name == expectedComponent.className
        }
        if (enabledViaManager) return true

        val enabledStr = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledStr.split(":").any { entry ->
            entry.equals(expectedComponent.flattenToString(), ignoreCase = true)
        }
    }
}