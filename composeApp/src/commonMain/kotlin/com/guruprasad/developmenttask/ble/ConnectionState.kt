package com.guruprasad.developmenttask.ble

/**
 * Represents every possible state in the BLE connection lifecycle.
 *
 * State machine transitions:
 *
 * ```
 * BluetoothDisabled ──(BT on)──► Disconnected
 * Disconnected ──(startScan)──► Scanning
 * Scanning ──(stopScan / timeout)──► Disconnected
 * Scanning ──(connect called)──► Connecting
 * Connecting ──(GATT connected + services discovered)──► Connected
 * Connecting ──(failure, retries remain)──► Reconnecting
 * Connected ──(link lost, retries remain)──► Reconnecting
 * Reconnecting ──(success)──► Connected
 * Reconnecting ──(retries exhausted)──► Disconnected
 * * ──(BT off)──► BluetoothDisabled
 * * ──(unrecoverable)──► Error
 * ```
 */
sealed class ConnectionState {
    /** No device connected; idle and ready to scan. */
    data object Disconnected : ConnectionState()
    /** [BleRepository.startScan] is active; scanning for nearby peripherals. */
    data object Scanning : ConnectionState()
    /** GATT connection attempt in progress. */
    data object Connecting : ConnectionState()
    /** GATT connected and all services have been discovered. */
    data object Connected : ConnectionState()
    /** Connection lost; automatic reconnection attempt in progress. */
    data object Reconnecting : ConnectionState()
    /** Bluetooth adapter is disabled on the device. */
    data object BluetoothDisabled : ConnectionState()
    /** An unrecoverable error occurred. [message] contains a human-readable description. */
    data class Error(val message: String) : ConnectionState()
}
