package com.guruprasad.developmenttask.ble

/**
 * Represents a discovered or connected BLE peripheral.
 *
 * @property name Advertised device name. Null if the device does not broadcast a name.
 * @property address MAC address on Android; UUID string on iOS.
 * @property rssi Received Signal Strength Indicator in dBm. Higher (less negative) = stronger signal.
 * @property batteryLevel Battery percentage read from Battery Service (0x180F / 0x2A19), or from a
 *   custom vendor characteristic. Null until the first successful GATT read.
 * @property heartRate Heart rate in bpm from Heart Rate Service (0x180D / 0x2A37). Null if the
 *   device does not expose this characteristic or no notification has arrived yet.
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val batteryLevel: Int? = null,
    val heartRate: Int? = null
) {
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else "Unknown Device"

    val hasName: Boolean
        get() = !name.isNullOrBlank()
}
