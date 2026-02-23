package com.guruprasad.developmenttask.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.guruprasad.developmenttask.ble.BleForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBleRepository(private val context: Context) : BleRepository {

    companion object {
        private const val TAG = "AndroidBleRepository"

        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SCAN_AUTO_STOP_MS = 30_000L
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var scanAutoStopJob: Job? = null

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: Flow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    override val deviceInfo: Flow<BleDevice?> = _deviceInfo.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BleDevice? = null
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    override fun startScan() {
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.Error("Bluetooth is not enabled")
            return
        }

        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE scan started")

        scanAutoStopJob?.cancel()
        scanAutoStopJob = scope.launch {
            delay(SCAN_AUTO_STOP_MS)
            stopScan()
        }
    }

    override fun stopScan() {
        scanAutoStopJob?.cancel()
        if (hasBluetoothPermissions()) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
        Log.d(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.toMyBleDevice()
            val current = _scannedDevices.value.toMutableList()
            val idx = current.indexOfFirst { it.address == device.address }
            if (idx >= 0) {
                current[idx] = current[idx].copy(rssi = device.rssi)
            } else {
                current.add(device)
            }
            _scannedDevices.value = current.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed (error $errorCode)")
        }
    }

    private fun ScanResult.toMyBleDevice(): BleDevice = BleDevice(
        name = (scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() }),
        address = device.address,
        rssi = rssi
    )

    override suspend fun connect(device: BleDevice) {
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        targetDevice = device
        reconnectAttempts = 0
        performConnect(device.address)
    }

    private fun performConnect(address: String) {
        val btDevice: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(address)
        if (btDevice == null) {
            _connectionState.value = ConnectionState.Error("Device not found: $address")
            return
        }
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Connecting to $address …")

        mainHandler.post {
            bluetoothGatt?.close()
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                btDevice.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK
                )
            } else {
                btDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        targetDevice = null
        reconnectAttempts = 0
        mainHandler.post {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        _connectionState.value = ConnectionState.Disconnected
        _deviceInfo.value = null
        BleForegroundService.stop(context)
        Log.d(TAG, "Disconnected and cleaned up")
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "Connected – discovering services…")
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0
                    BleForegroundService.start(context)
                    mainHandler.post { gatt.discoverServices() }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    gatt.close()
                    bluetoothGatt = null

                    val wasIntentional = targetDevice == null
                    if (!wasIntentional && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                        _deviceInfo.value = null
                        BleForegroundService.stop(context)
                    }
                }

                else -> {
                    Log.w(TAG, "GATT error: status=$status newState=$newState")
                    gatt.close()
                    bluetoothGatt = null
                    if (targetDevice != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = ConnectionState.Error("GATT error: status $status")
                        BleForegroundService.stop(context)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            Log.d(TAG, "Services discovered")
            targetDevice?.let { dev -> _deviceInfo.value = dev }
            readBatteryLevel(gatt)
            enableHeartRateNotifications(gatt)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic.uuid, characteristic.value)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic.uuid, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(characteristic.uuid, characteristic.value)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor written: ${descriptor.characteristic.uuid} status=$status")
        }
    }

    private fun readBatteryLevel(gatt: BluetoothGatt) {
        val service = gatt.getService(BATTERY_SERVICE_UUID) ?: run {
            Log.w(TAG, "Battery Service (0x180F) not found on this device")
            return
        }
        val characteristic = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: run {
            Log.w(TAG, "Battery Level characteristic (0x2A19) not found")
            return
        }
        mainHandler.post {
            @Suppress("DEPRECATION")
            gatt.readCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableHeartRateNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(HEART_RATE_CHAR_UUID) ?: return

        mainHandler.post {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
        Log.d(TAG, "Heart Rate notifications enabled")
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray?) {
        when (uuid) {
            BATTERY_LEVEL_CHAR_UUID -> {
                val battery = value?.firstOrNull()?.toInt()?.and(0xFF)
                Log.d(TAG, "Battery level: $battery%")
                _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = battery)
            }
            HEART_RATE_CHAR_UUID -> {
                val hr = value?.let {
                    if (it.isNotEmpty()) {
                        val flag = it[0].toInt()
                        if (flag and 0x01 == 0) it.getOrNull(1)?.toInt()?.and(0xFF)
                        else if (it.size >= 3) ((it[2].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
                        else null
                    } else null
                }
                Log.d(TAG, "Heart rate: $hr bpm")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        _connectionState.value = ConnectionState.Reconnecting

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            targetDevice?.let { dev ->
                Log.d(TAG, "Reconnect attempt $reconnectAttempts for ${dev.address}")
                performConnect(dev.address)
            }
        }
    }
}
