package com.guruprasad.developmenttask.ble

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic interface for all BLE operations.
 *
 * Implementations exist for Android (BluetoothLeScanner + BluetoothGatt)
 * and iOS (CoreBluetooth CBCentralManager).
 */
interface BleRepository {

    /** Live list of BLE devices discovered during an active scan. */
    val scannedDevices: Flow<List<BleDevice>>

    /** Tracks the current BLE connection lifecycle state. */
    val connectionState: Flow<ConnectionState>

    /**
     * Device info of the currently connected device, updated whenever
     * characteristic reads complete (e.g., battery level refresh).
     */
    val deviceInfo: Flow<BleDevice?>

    /** Begin scanning for nearby BLE peripherals. */
    fun startScan()

    /** Stop an in-progress BLE scan. */
    fun stopScan()

    /**
     * Connect to the given [device].
     * Emits [ConnectionState.Connecting] immediately, then [ConnectionState.Connected]
     * once services are discovered.
     */
    suspend fun connect(device: BleDevice)

    /**
     * Disconnect from the currently connected device and cancel any
     * pending reconnection attempts.
     */
    fun disconnect()
}

