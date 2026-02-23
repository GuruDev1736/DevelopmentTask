package com.guruprasad.developmenttask.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.guruprasad.developmenttask.BleApplication
import com.guruprasad.developmenttask.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleForegroundService"
        private const val CHANNEL_ID = "ble_connection_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "ble_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        fun start(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleForegroundService::class.java))
        }

        @Suppress("unused")
        fun saveLastDevice(context: Context, address: String, name: String?) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_LAST_DEVICE_ADDRESS, address)
                .putString(KEY_LAST_DEVICE_NAME, name ?: "")
                .apply()
        }

        @Suppress("unused")
        fun clearLastDevice(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(KEY_LAST_DEVICE_ADDRESS)
                .remove(KEY_LAST_DEVICE_NAME)
                .apply()
        }

        fun getLastDevice(context: Context): BleDevice? {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val address = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null) ?: return null
            val name = prefs.getString(KEY_LAST_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            return BleDevice(name = name, address = address, rssi = 0)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleForegroundService = this@BleForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "Service onStartCommand — ensuring BLE connection is live")
        ensureConnected()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * With android:stopWithTask="false" this service is NOT stopped when the task is removed.
     * onTaskRemoved is still called — we use it to re-trigger connection insurance.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — BLE service stays alive (stopWithTask=false)")
        ensureConnected()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Checks if the repository is currently connected. If not, looks up the last saved
     * device from SharedPreferences and reconnects. This handles both START_STICKY restarts
     * and task-removed events where the GATT may have been dropped.
     */
    private fun ensureConnected() {
        val repository = (application as? BleApplication)?.bleRepository ?: run {
            Log.w(TAG, "BleApplication not available — cannot ensure connection")
            return
        }
        serviceScope.launch {
            val state = repository.connectionState.first()
            Log.d(TAG, "Current connection state in service: $state")
            if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting && state !is ConnectionState.Reconnecting) {
                val lastDevice = getLastDevice(applicationContext)
                if (lastDevice != null) {
                    Log.d(TAG, "Reconnecting to last device: ${lastDevice.address}")
                    repository.connect(lastDevice)
                } else {
                    Log.d(TAG, "No saved device to reconnect to")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the BLE connection active in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Connected")
            .setContentText("Maintaining connection to your BLE device")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
