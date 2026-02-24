package com.guruprasad.developmenttask.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d("BleBootReceiver", "Boot completed — checking for saved BLE device")
        val savedDevice = BleForegroundService.getLastDevice(context)
        if (savedDevice != null) {
            Log.d("BleBootReceiver", "Found saved device ${savedDevice.address}, starting foreground service")
            BleForegroundService.start(context)
        } else {
            Log.d("BleBootReceiver", "No saved BLE device — skipping service start")
        }
    }
}

