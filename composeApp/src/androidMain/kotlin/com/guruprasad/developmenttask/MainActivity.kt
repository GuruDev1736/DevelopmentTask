package com.guruprasad.developmenttask

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.guruprasad.developmenttask.ble.createBleRepository
import com.guruprasad.developmenttask.ui.BleNavigation

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    // Lazy-create the repository once (lives for the lifetime of the Activity)
    private val bleRepository by lazy { createBleRepository(applicationContext) }

    private var showPermissionRationale by mutableStateOf(false)

    // ── Permission launcher ────────────────────────────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) {
                Log.d(tag, "All BLE permissions granted")
            } else {
                Log.w(tag, "Some BLE permissions denied: $grants")
                showPermissionRationale = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestBlePermissions()

        setContent {
            MaterialTheme {
                BleNavigation(repository = bleRepository)

                if (showPermissionRationale) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale = false },
                        title = { Text("Bluetooth Permission Required") },
                        text = {
                            Text(
                                "This app needs Bluetooth and Location permissions to scan " +
                                        "and connect to BLE devices. Please grant them in Settings."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionRationale = false
                                openAppSettings()
                            }) { Text("Open Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionRationale = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        permissionLauncher.launch(permissions)
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}