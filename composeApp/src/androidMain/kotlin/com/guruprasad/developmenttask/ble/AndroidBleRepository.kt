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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android implementation of [BleRepository].
 *
 * ## Scanning
 * Uses [android.bluetooth.le.BluetoothLeScanner] with [android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY].
 * Scan auto-stops after [SCAN_AUTO_STOP_MS] to preserve battery.
 *
 * ## Connection
 * Connects via [android.bluetooth.BluetoothDevice.connectGatt] with [android.bluetooth.BluetoothDevice.TRANSPORT_LE].
 * On success, service discovery triggers sequential GATT reads via an internal [readQueue].
 *
 * ## Battery reading strategy
 * 1. Checks for standard Battery Service (0x180F / 0x2A19).
 * 2. Falls back to custom service (0x0AF0) — reads all readable characteristics.
 * 3. Auto-detects the battery characteristic by heuristic: a single-byte value in 1–100.
 * Subscribes to CCCD notifications so updates stream in real-time without polling.
 *
 * ## Heart Rate (optional)
 * If the device exposes Heart Rate Service (0x180D / 0x2A37), notifications are enabled
 * and HR values are emitted via [characteristicUpdates] and stored in [deviceInfo].
 *
 * ## Background operation
 * Starts [BleForegroundService] when a device connects so the OS does not kill the process.
 * Stops the service on disconnect or when Bluetooth is disabled.
 *
 * ## Reconnection
 * On unexpected disconnect: retries up to [MAX_RECONNECT_ATTEMPTS] times with linear
 * back-off ([RECONNECT_DELAY_MS] × attempt number).
 *
 * ## Bluetooth state
 * Registers a [android.content.BroadcastReceiver] for [android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED].
 * When Bluetooth turns off: clears scan results, closes GATT, stops the service, and emits
 * [ConnectionState.BluetoothDisabled].
 *
 * Call [release] when the owning component is destroyed to unregister the receiver.
 */
@SuppressLint("MissingPermission")
class AndroidBleRepository(private val context: Context) : BleRepository {

    companion object {
        private const val TAG = "AndroidBleRepository"

        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        val CUSTOM_SERVICE_UUID: UUID = UUID.fromString("00000af0-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val SCAN_AUTO_STOP_MS = 30_000L
    }

    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var isReadingCharacteristic = false

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var scanAutoStopJob: Job? = null

    private val _rawScannedDevices = mutableListOf<BleDevice>()
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: Flow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private var currentFilter = BleFilterConfig()

    private val _connectionState = MutableStateFlow<ConnectionState>(
        if (bluetoothAdapter?.isEnabled == true) ConnectionState.Disconnected
        else ConnectionState.BluetoothDisabled
    )
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    override val deviceInfo: Flow<BleDevice?> = _deviceInfo.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    override val isBluetoothEnabled: Flow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _characteristicUpdates = MutableSharedFlow<GattCharacteristicUpdate>(extraBufferCapacity = 16)
    override val characteristicUpdates: Flow<GattCharacteristicUpdate> = _characteristicUpdates.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BleDevice? = null
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "Bluetooth turned ON")
                    _isBluetoothEnabled.value = true
                    if (_connectionState.value is ConnectionState.BluetoothDisabled) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "Bluetooth turned OFF")
                    _isBluetoothEnabled.value = false
                    reconnectJob?.cancel()
                    scanAutoStopJob?.cancel()
                    mainHandler.post {
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                    }
                    _rawScannedDevices.clear()
                    _scannedDevices.value = emptyList()
                    _deviceInfo.value = null
                    _connectionState.value = ConnectionState.BluetoothDisabled
                    BleForegroundService.stop(context)
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.d(TAG, "Bluetooth turning off…")
                    _connectionState.value = ConnectionState.BluetoothDisabled
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    fun release() {
        try { context.unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        reconnectJob?.cancel()
        scanAutoStopJob?.cancel()
        mainHandler.post {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }

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
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.BluetoothDisabled
            return
        }
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }

        _rawScannedDevices.clear()
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
            val idx = _rawScannedDevices.indexOfFirst { it.address == device.address }
            if (idx >= 0) {
                _rawScannedDevices[idx] = _rawScannedDevices[idx].copy(rssi = device.rssi, name = device.name ?: _rawScannedDevices[idx].name)
            } else {
                _rawScannedDevices.add(device)
            }
            publishFiltered()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed (error $errorCode)")
        }
    }

    private fun publishFiltered() {
        val sorted = _rawScannedDevices.sortedByDescending { it.rssi }
        _scannedDevices.value = BleDeviceFilter.apply(sorted, currentFilter)
    }

    override fun applyFilter(config: BleFilterConfig) {
        currentFilter = config
        publishFiltered()
    }

    private fun ScanResult.toMyBleDevice(): BleDevice = BleDevice(
        name = (scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() }),
        address = device.address,
        rssi = rssi
    )

    override suspend fun connect(device: BleDevice) {
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.BluetoothDisabled
            return
        }
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        targetDevice = device
        reconnectAttempts = 0
        // Persist device so the foreground service can reconnect after process restart
        BleForegroundService.saveLastDevice(context, device.address, device.name)
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
                    context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK
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
        readQueue.clear()
        isReadingCharacteristic = false
        customBatteryCharUuid = null
        // Clear saved device — user explicitly disconnected, service must NOT reconnect
        BleForegroundService.clearLastDevice(context)
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
                    _connectionState.value = ConnectionState.Connected
                    reconnectAttempts = 0
                    BleForegroundService.start(context)
                    mainHandler.post { gatt.discoverServices() }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    bluetoothGatt = null
                    val wasIntentional = targetDevice == null
                    if (!wasIntentional && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else if (!wasIntentional) {
                        // Reconnect attempts exhausted — reset counter so service can retry later
                        Log.w(TAG, "Max reconnect attempts reached, resetting for service retry")
                        reconnectAttempts = 0
                        _connectionState.value = ConnectionState.Disconnected
                        _deviceInfo.value = null
                        // Keep foreground service alive — it will call ensureConnected again
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                        _deviceInfo.value = null
                        BleForegroundService.stop(context)
                    }
                }
                else -> {
                    gatt.close()
                    bluetoothGatt = null
                    if (targetDevice != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else if (targetDevice != null) {
                        Log.w(TAG, "Max reconnect attempts reached on GATT error, resetting for service retry")
                        reconnectAttempts = 0
                        _connectionState.value = ConnectionState.Disconnected
                        _deviceInfo.value = null
                        // Keep foreground service alive — it will call ensureConnected again
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
            gatt.services.forEach { service ->
                Log.d(TAG, "Service: ${service.uuid}")
                service.characteristics.forEach { char ->
                    val props = buildString {
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("READ ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIFY ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("INDICATE ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("WRITE ")
                    }
                    Log.d(TAG, "  Characteristic: ${char.uuid} [$props]")
                }
            }

            targetDevice?.let { dev -> _deviceInfo.value = dev }

            scope.launch {
                delay(600)
                mainHandler.post {
                    readQueue.clear()
                    isReadingCharacteristic = false

                    val standardBattery = gatt.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)

                    val customService = gatt.getService(CUSTOM_SERVICE_UUID)

                    if (standardBattery != null) {
                        Log.d(TAG, "Standard Battery Service found — reading 0x2A19")
                        enqueueRead(standardBattery)
                        enableBatteryNotifications(gatt)
                    } else if (customService != null) {
                        Log.d(TAG, "Custom service 0x0AF0 found — reading all readable characteristics")
                        customService.characteristics
                            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                            .forEach { enqueueRead(it) }

                        customService.characteristics
                            .filter {
                                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                                it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                            }
                            .forEach { subscribeToCharacteristic(gatt, it) }
                    } else {
                        Log.w(TAG, "No battery service found. Trying to read all readable characteristics from all services.")
                        gatt.services.flatMap { it.characteristics }
                            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                            .forEach { enqueueRead(it) }
                    }

                    enableHeartRateNotifications(gatt)
                    processReadQueue(gatt)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            isReadingCharacteristic = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic.uuid, value)
            } else {
                Log.w(TAG, "Read failed for ${characteristic.uuid} status=$status")
            }
            mainHandler.post { processReadQueue(gatt) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isReadingCharacteristic = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic.uuid, characteristic.value)
            } else {
                Log.w(TAG, "Read failed for ${characteristic.uuid} status=$status")
            }
            mainHandler.post { processReadQueue(gatt) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
            val charUuid = descriptor.characteristic.uuid
            Log.d(TAG, "Descriptor written for $charUuid status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    mainHandler.postDelayed({
                        enqueueRead(descriptor.characteristic)
                        processReadQueue(gatt)
                    }, 200)
                }
            }
        }
    }

    private fun enqueueRead(characteristic: BluetoothGattCharacteristic) {
        readQueue.addLast(characteristic)
    }

    private fun processReadQueue(gatt: BluetoothGatt) {
        if (isReadingCharacteristic || readQueue.isEmpty()) return
        val next = readQueue.removeFirst()
        isReadingCharacteristic = true
        Log.d(TAG, "Reading characteristic: ${next.uuid}")
        @Suppress("DEPRECATION")
        val ok = gatt.readCharacteristic(next)
        if (!ok) {
            Log.w(TAG, "readCharacteristic returned false for ${next.uuid}, retrying after delay")
            isReadingCharacteristic = false
            mainHandler.postDelayed({ processReadQueue(gatt) }, 200)
        }
    }

    private fun subscribeToCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            Log.d(TAG, "No CCCD for ${characteristic.uuid}, skipping subscribe")
            return
        }
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        Log.d(TAG, "Subscribed to ${characteristic.uuid}")
    }


    private fun enableBatteryNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(BATTERY_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: return
        subscribeToCharacteristic(gatt, characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun enableHeartRateNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(HEART_RATE_CHAR_UUID) ?: return
        subscribeToCharacteristic(gatt, characteristic)
    }

    private var customBatteryCharUuid: UUID? = null

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray?) {
        val bytes = value ?: run {
            Log.w(TAG, "Null value for $uuid")
            return
        }
        Log.d(TAG, "Characteristic $uuid raw (${bytes.size}B): ${bytes.joinToString(" ") { "%02X".format(it) }}")

        when {
            uuid == BATTERY_LEVEL_CHAR_UUID -> {
                val battery = GattParser.parseBatteryLevel(bytes)
                Log.d(TAG, "Battery level (standard): $battery%")
                if (battery != null) {
                    mainHandler.post { _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = battery) }
                    scope.launch { _characteristicUpdates.emit(GattCharacteristicUpdate.BatteryLevel(battery)) }
                }
            }

            uuid == HEART_RATE_CHAR_UUID -> {
                val hr = GattParser.parseHeartRate(bytes)
                Log.d(TAG, "Heart rate: $hr bpm")
                if (hr != null) {
                    mainHandler.post { _deviceInfo.value = _deviceInfo.value?.copy(heartRate = hr) }
                    scope.launch { _characteristicUpdates.emit(GattCharacteristicUpdate.HeartRate(hr)) }
                }
            }

            uuid == customBatteryCharUuid -> {
                val battery = GattParser.parseBatteryLevel(bytes)
                Log.d(TAG, "Battery level (custom $uuid): $battery%")
                if (battery != null) {
                    mainHandler.post { _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = battery) }
                    scope.launch { _characteristicUpdates.emit(GattCharacteristicUpdate.BatteryLevel(battery)) }
                }
            }

            else -> {
                scope.launch { _characteristicUpdates.emit(GattCharacteristicUpdate.CustomData(uuid.toString(), bytes.copyOf())) }
                if (bytes.size == 1) {
                    val v = bytes[0].toInt() and 0xFF
                    if (v in 1..100 && customBatteryCharUuid == null) {
                        Log.d(TAG, "Likely battery characteristic found: $uuid = $v%")
                        customBatteryCharUuid = uuid
                        mainHandler.post { _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = v) }
                        scope.launch { _characteristicUpdates.emit(GattCharacteristicUpdate.BatteryLevel(v)) }
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        _connectionState.value = ConnectionState.Reconnecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            targetDevice?.let { dev -> performConnect(dev.address) }
        }
    }
}
