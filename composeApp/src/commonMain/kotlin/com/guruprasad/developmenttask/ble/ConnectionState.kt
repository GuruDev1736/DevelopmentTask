package com.guruprasad.developmenttask.ble

/**
 * Represents the current state of the BLE connection lifecycle.
 */
sealed class ConnectionState {
    /** No active connection; idle. */
    data object Disconnected : ConnectionState()

    /** Currently scanning for nearby BLE devices. */
    data object Scanning : ConnectionState()

    /** Attempting to establish a GATT connection to a device. */
    data object Connecting : ConnectionState()

    /** Successfully connected to a BLE device and services are discovered. */
    data object Connected : ConnectionState()

    /** Connection was lost; attempting to reconnect automatically. */
    data object Reconnecting : ConnectionState()

    /** An unrecoverable error occurred. */
    data class Error(val message: String) : ConnectionState()
}

