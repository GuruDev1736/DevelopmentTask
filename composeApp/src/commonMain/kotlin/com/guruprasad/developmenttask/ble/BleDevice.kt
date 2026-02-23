package com.guruprasad.developmenttask.ble

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val batteryLevel: Int? = null
) {
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else "Unknown Device"

    val hasName: Boolean
        get() = !name.isNullOrBlank()
}
