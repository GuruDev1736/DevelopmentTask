package com.guruprasad.developmenttask

import android.app.Application
import com.guruprasad.developmenttask.ble.AndroidBleRepository

/**
 * Custom Application class that holds a process-scoped singleton [AndroidBleRepository].
 *
 * By keeping the repository at the Application level the GATT connection and the
 * foreground service survive Activity recreation (rotation, back-press to home, etc.)
 * as well as the user swiping the app away from the Recents screen — as long as the
 * foreground service keeps the process alive.
 */
class BleApplication : Application() {

    val bleRepository: AndroidBleRepository by lazy {
        AndroidBleRepository(applicationContext)
    }

    override fun onTerminate() {
        super.onTerminate()
        bleRepository.release()
    }
}

