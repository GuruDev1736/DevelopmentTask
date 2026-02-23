package com.guruprasad.developmenttask.ble

import android.content.Context

actual fun createBleRepository(context: Any?): BleRepository {
    return AndroidBleRepository(context as Context)
}

