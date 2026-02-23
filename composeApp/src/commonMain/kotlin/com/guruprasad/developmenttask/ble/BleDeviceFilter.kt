package com.guruprasad.developmenttask.ble

/**
 * Configuration for filtering the BLE scan results list.
 *
 * @property nameQuery Case-insensitive substring matched against [BleDevice.displayName]
 *   and [BleDevice.address]. Empty string disables name filtering.
 * @property minRssi Minimum RSSI threshold in dBm. Devices weaker than this are excluded.
 *   Default is -100 dBm (effectively no filtering).
 * @property namedOnly When `true`, only devices that advertise a name are shown.
 */
data class BleFilterConfig(
    val nameQuery: String = "",
    val minRssi: Int = -100,
    val namedOnly: Boolean = false
)

object BleDeviceFilter {

    fun apply(devices: List<BleDevice>, config: BleFilterConfig): List<BleDevice> {
        return devices.filter { device ->
            val passesName = config.nameQuery.isBlank() ||
                    device.displayName.contains(config.nameQuery, ignoreCase = true) ||
                    device.address.contains(config.nameQuery, ignoreCase = true)

            val passesRssi = device.rssi >= config.minRssi

            val passesNamedOnly = !config.namedOnly || device.hasName

            passesName && passesRssi && passesNamedOnly
        }
    }
}
