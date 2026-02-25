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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.guruprasad.developmenttask.BleApplication
import com.guruprasad.developmenttask.MainActivity
import com.guruprasad.developmenttask.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleForegroundService"
        private const val CHANNEL_ID = "ble_connection_channel"
        private const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "ble_prefs"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val WAKE_LOCK_TAG = "BleApp:BleWakeLock"

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

        fun saveLastDevice(context: Context, address: String, name: String?) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putString(KEY_LAST_DEVICE_ADDRESS, address)
                putString(KEY_LAST_DEVICE_NAME, name ?: "")
            }
        }

        fun clearLastDevice(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                remove(KEY_LAST_DEVICE_ADDRESS)
                remove(KEY_LAST_DEVICE_NAME)
            }
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
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // On API 34+, startForeground with type connectedDevice throws SecurityException
            // if BLUETOOTH_CONNECT permission has not been granted yet.
            Log.w(TAG, "startForeground failed — BT permission not yet granted: ${e.message}")
        }
        Log.d(TAG, "Service onStartCommand — ensuring BLE connection is live")
        ensureConnected()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — service stays alive, checking BLE connection")
        serviceScope.launch {
            delay(1000)
            ensureConnected()
        }
        val restartIntent = Intent(applicationContext, BleForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 2000,
            pendingIntent
        )
        Log.d(TAG, "Scheduled service restart via AlarmManager")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed — scheduling restart")
        val restartIntent = Intent(applicationContext, BleForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 2000,
            pendingIntent
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).also {
            it.acquire(60 * 60 * 1000L)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    private fun ensureConnected() {
        val repository: AndroidBleRepository = (application as? BleApplication)?.bleRepository ?: run {
            Log.w(TAG, "BleApplication not available — cannot ensure connection")
            return
        }
        serviceScope.launch {
            val state = repository.connectionState.first()
            Log.d(TAG, "Current connection state: $state")
            if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting && state !is ConnectionState.Reconnecting) {
                val lastDevice = getLastDevice(applicationContext)
                if (lastDevice != null) {
                    Log.d(TAG, "Reconnecting to saved device: ${lastDevice.address}")
                    repository.connectFromService(lastDevice)
                } else {
                    Log.d(TAG, "No saved device found — nothing to reconnect to")
                }
            } else {
                Log.d(TAG, "Already connected or connecting — no action needed")
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
            .setSmallIcon(R.drawable.ic_bluetooth_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
