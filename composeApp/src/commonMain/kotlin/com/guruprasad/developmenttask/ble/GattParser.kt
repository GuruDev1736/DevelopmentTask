package com.guruprasad.developmenttask.ble

/**
 * Shared utility for parsing raw GATT characteristic byte arrays into typed values.
 *
 * Used by both Android and iOS BLE repository implementations to ensure consistent
 * parsing logic across platforms.
 */
object GattParser {

    /**
     * Parses a Battery Level characteristic (0x2A19) byte array.
     *
     * The BLE spec defines this as a single unsigned byte in the range 0–100.
     * Values are clamped to [0, 100] to handle misbehaving peripherals.
     *
     * @param raw Raw bytes from the characteristic value.
     * @return Battery percentage in [0, 100], or null if [raw] is empty.
     */
    fun parseBatteryLevel(raw: ByteArray): Int? {
        if (raw.isEmpty()) return null
        val value = raw[0].toInt() and 0xFF
        return value.coerceIn(0, 100)
    }

    /**
     * Parses a Heart Rate Measurement characteristic (0x2A37) byte array.
     *
     * Handles both 8-bit and 16-bit heart rate value formats as defined by
     * the Bluetooth Heart Rate Profile specification (bit 0 of the flags byte
     * indicates the value format).
     *
     * @param raw Raw bytes from the characteristic value.
     * @return Heart rate in bpm, or null if [raw] is empty or malformed.
     */
    fun parseHeartRate(raw: ByteArray): Int? {
        if (raw.isEmpty()) return null
        val flags = raw[0].toInt() and 0xFF
        return if (flags and 0x01 == 0) {
            raw.getOrNull(1)?.toInt()?.and(0xFF)
        } else {
            if (raw.size >= 3)
                ((raw[2].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            else null
        }
    }

    /**
     * Decodes a Device Name characteristic (0x2A00) byte array as a UTF-8 string.
     *
     * @param raw Raw bytes from the characteristic value.
     * @return Trimmed device name string, or null if [raw] is empty or not valid UTF-8.
     */
    @Suppress("unused")
    fun parseDeviceName(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        return runCatching { raw.decodeToString().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
