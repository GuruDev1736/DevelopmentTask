package com.guruprasad.developmenttask.ble

import kotlinx.coroutines.flow.Flow

interface BleRepository {
    val scannedDevices: Flow<List<BleDevice>>
    val connectionState: Flow<ConnectionState>
    val deviceInfo: Flow<BleDevice?>

    fun startScan()
    fun stopScan()
    suspend fun connect(device: BleDevice)
    fun disconnect()
}
