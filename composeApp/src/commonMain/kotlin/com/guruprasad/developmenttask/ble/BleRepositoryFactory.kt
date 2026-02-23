package com.guruprasad.developmenttask.ble

/**
 * Factory function to create the platform-specific [BleRepository] implementation.
 *
 * @param context On Android, pass the [android.content.Context]. On iOS, pass null.
 */
expect fun createBleRepository(context: Any?): BleRepository

