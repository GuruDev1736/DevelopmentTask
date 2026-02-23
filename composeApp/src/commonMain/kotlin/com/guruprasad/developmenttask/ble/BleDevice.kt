package com.guruprasad.developmenttask.ble

/**
 * Represents a discovered BLE device.
 *
 * @param name The advertised device name (may be null if not available)
 * @param address MAC address on Android / UUID string on iOS
 * @param rssi Received Signal Strength Indicator in dBm
 * @param batteryLevel Battery percentage read from Battery Service (0x180F / 0x2A19), null if not read yet
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val batteryLevel: Int? = null
) {
    /** Returns a display-friendly name, falling back to "Unknown Device" if name is null or blank. */
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else "Unknown Device"

    /** True when the device advertises a real name. */
    val hasName: Boolean
        get() = !name.isNullOrBlank()
}

