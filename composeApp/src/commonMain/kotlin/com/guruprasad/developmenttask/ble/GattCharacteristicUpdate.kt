package com.guruprasad.developmenttask.ble

/**
 * Sealed hierarchy of typed GATT characteristic update events emitted by [BleRepository.characteristicUpdates].
 *
 * Consumers can collect this flow and pattern-match on the subtype to handle
 * specific characteristic data without parsing raw bytes themselves.
 */
@Suppress("unused")
sealed class GattCharacteristicUpdate {
    /**
     * Battery level notification from Battery Service (UUID 0x180F / Characteristic 0x2A19),
     * or from a vendor-specific characteristic auto-detected by [AndroidBleRepository].
     *
     * @property percent Battery percentage in the range 0–100.
     */
    data class BatteryLevel(val percent: Int) : GattCharacteristicUpdate()

    /**
     * Heart rate measurement notification from Heart Rate Service (UUID 0x180D / Characteristic 0x2A37).
     * Parsed according to the Heart Rate Measurement characteristic format (flags byte).
     *
     * @property bpm Instantaneous heart rate in beats per minute.
     */
    data class HeartRate(val bpm: Int) : GattCharacteristicUpdate()

    /**
     * Raw bytes from any characteristic not explicitly handled above.
     * Useful for custom vendor services.
     *
     * @property uuid UUID string of the characteristic.
     * @property raw Raw byte array as received from the device.
     */
    data class CustomData(val uuid: String, val raw: ByteArray) : GattCharacteristicUpdate() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CustomData) return false
            return uuid == other.uuid && raw.contentEquals(other.raw)
        }
        override fun hashCode(): Int = 31 * uuid.hashCode() + raw.contentHashCode()
    }
}
