package com.guruprasad.developmenttask.ble

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic contract for all BLE operations.
 *
 * Each platform provides a concrete implementation:
 * - **Android** — `AndroidBleRepository` uses `BluetoothLeScanner` and `BluetoothGatt`,
 *   wrapped in a Foreground Service for background operation.
 * - **iOS** — `IosBleRepository` uses CoreBluetooth (`CBCentralManager` / `CBPeripheral`)
 *   with state restoration for background operation.
 *
 * All reactive streams are exposed as [Flow] so that shared ViewModels and Compose UIs
 * can collect them identically on both platforms.
 */
interface BleRepository {

    /**
     * Live list of [BleDevice]s discovered during an active scan.
     * Emits an updated list every time a new device is found or an existing
     * device's RSSI changes. Sorted by descending RSSI.
     * Cleared automatically when [startScan] is called again or Bluetooth is disabled.
     */
    val scannedDevices: Flow<List<BleDevice>>

    /**
     * Current state of the BLE connection lifecycle.
     * Always emits the latest [ConnectionState] to new collectors.
     */
    val connectionState: Flow<ConnectionState>

    /**
     * Details of the currently connected device. Updated whenever GATT
     * characteristic reads complete (battery level, heart rate, etc.).
     * Null when no device is connected.
     */
    val deviceInfo: Flow<BleDevice?>

    /**
     * Emits `true` when the Bluetooth adapter is enabled, `false` otherwise.
     * On Android, backed by `ACTION_STATE_CHANGED` broadcast receiver.
     * On iOS, backed by `CBCentralManagerStatePoweredOn` state updates.
     */
    val isBluetoothEnabled: Flow<Boolean>

    /**
     * Hot stream of individual GATT characteristic update events.
     * Each emission is a [GattCharacteristicUpdate] subtype:
     * - [GattCharacteristicUpdate.BatteryLevel] — battery percentage (0–100)
     * - [GattCharacteristicUpdate.HeartRate] — heart rate in bpm
     * - [GattCharacteristicUpdate.CustomData] — raw bytes from any other characteristic
     */
    val characteristicUpdates: Flow<GattCharacteristicUpdate>

    /** Begin scanning for nearby BLE peripherals. Clears the previous scan results. */
    fun startScan()

    /** Stop an in-progress scan. No-op if not currently scanning. */
    fun stopScan()

    /**
     * Initiate a GATT connection to [device].
     * Emits [ConnectionState.Connecting] immediately, then [ConnectionState.Connected]
     * once services are discovered, or [ConnectionState.Reconnecting] on failure.
     */
    suspend fun connect(device: BleDevice)

    /**
     * Disconnect from the currently connected device and cancel any pending
     * reconnection attempts. Emits [ConnectionState.Disconnected].
     */
    fun disconnect()

    /**
     * Apply a [BleFilterConfig] to the scanned device list.
     * The filter is applied reactively — [scannedDevices] re-emits immediately
     * with the filtered result.
     */
    fun applyFilter(config: BleFilterConfig)
}
