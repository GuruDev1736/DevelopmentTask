package com.guruprasad.developmenttask.ble

actual fun createBleRepository(context: Any?): BleRepository {
    return IosBleRepository()
}

