package com.guruprasad.developmenttask.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guruprasad.developmenttask.ble.BleDevice
import com.guruprasad.developmenttask.ble.BleViewModel
import com.guruprasad.developmenttask.ble.ConnectionState

/**
 * Scan screen — lists all nearby BLE devices discovered by [BleViewModel.startScan].
 *
 * Features:
 * - Real-time device list sorted by descending RSSI, updated via [BleViewModel.scannedDevices].
 * - Search field and "Named only" toggle backed by [BleViewModel.filterConfig] / [BleFilterConfig].
 * - Signal strength badge: Strong (≥ -60 dBm) / Medium (≥ -80 dBm) / Weak (< -80 dBm).
 * - Bluetooth disabled banner shown when [BleViewModel.isBluetoothEnabled] is `false`.
 * - Scan / Stop button with animated progress indicator while scanning.
 *
 * @param viewModel Shared [BleViewModel] instance.
 * @param onDeviceSelected Callback invoked when the user taps a [BleDevice] row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: BleViewModel,
    onDeviceSelected: (BleDevice) -> Unit
) {
    val devices by viewModel.scannedDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val filterConfig by viewModel.filterConfig.collectAsState()
    val isScanning = connectionState is ConnectionState.Scanning

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "BLE Scanner", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            if (!isBluetoothEnabled) {
                BluetoothDisabledBanner()
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = filterConfig.nameQuery,
                onValueChange = { viewModel.updateFilter(filterConfig.copy(nameQuery = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by name or address…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Named devices only", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = filterConfig.namedOnly,
                    onCheckedChange = { viewModel.updateFilter(filterConfig.copy(namedOnly = it)) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isScanning) "Scanning for devices..." else "Tap to start scanning",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${devices.size} device(s) found",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.stopScan() }) { Text("Stop") }
                    }
                } else {
                    Button(onClick = { viewModel.startScan() }) { Text("Scan") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (devices.isEmpty() && !isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "🔵", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No devices found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Press Scan to discover nearby BLE devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.address }) { device ->
                        DeviceItem(device = device, onClick = { onDeviceSelected(device) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(device: BleDevice, onClick: () -> Unit) {
    val rssiColor = when {
        device.rssi >= -60 -> Color(0xFF4CAF50)
        device.rssi >= -80 -> Color(0xFFFF9800)
        else               -> Color(0xFFF44336)
    }
    val rssiLabel = when {
        device.rssi >= -60 -> "Strong"
        device.rssi >= -80 -> "Medium"
        else               -> "Weak"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.hasName)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(44.dp)
            ) {
                Text(text = "📶", fontSize = 20.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = rssiLabel, fontSize = 9.sp, color = rssiColor, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (device.hasName)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    letterSpacing = 0.5.sp
                )
                if (!device.hasName) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "No name advertised",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .background(rssiColor.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${device.rssi}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = rssiColor
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun BluetoothDisabledBanner() {
    val color = Color(0xFFF44336)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "🔴", fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "Bluetooth is Disabled",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = "Please enable Bluetooth to scan for devices",
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}

