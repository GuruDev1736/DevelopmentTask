package com.guruprasad.developmenttask.ble

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
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
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnConnectionKey
import platform.CoreBluetooth.CBConnectPeripheralOptionNotifyOnDisconnectionKey
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * iOS implementation of [BleRepository] using CoreBluetooth.
 *
 * ## Scanning
 * Delegates to `CBCentralManager.scanForPeripheralsWithServices` with `nil` service filter
 * so all nearby peripherals are discovered. Results are sorted by descending RSSI and
 * filtered by the current [BleFilterConfig].
 *
 * ## Connection
 * Retrieves a known `CBPeripheral` by UUID via `CBCentralManager.retrievePeripheralsWithIdentifiers`.
 * Falls back to a fresh scan if the peripheral is not cached.
 *
 * ## Battery reading
 * After service discovery, reads Battery Level (0x2A19) immediately and subscribes to
 * notifications so subsequent changes are pushed in real-time.
 *
 * ## Heart Rate (optional)
 * Enables notifications on Heart Rate Measurement (0x2A37). HR values are emitted via
 * [characteristicUpdates] and stored in [deviceInfo].
 *
 * ## Background operation
 * Initialised with `CBCentralManagerOptionRestoreIdentifierKey` so iOS can wake the app
 * in the background. `bluetooth-central` UIBackgroundMode is declared in `Info.plist`.
 *
 * ## Reconnection
 * On unexpected disconnect: retries up to [MAX_RECONNECT_ATTEMPTS] times with linear
 * back-off ([RECONNECT_DELAY_MS] × attempt number).
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleRepository : BleRepository {

    companion object {
        private const val RESTORE_ID = "com.guruprasad.ble.central"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L

        @Suppress("unused") val BATTERY_SERVICE_UUID: CBUUID = CBUUID.UUIDWithString("180F")
        val BATTERY_CHAR_UUID: CBUUID = CBUUID.UUIDWithString("2A19")
        @Suppress("unused") val HEART_RATE_SERVICE_UUID: CBUUID = CBUUID.UUIDWithString("180D")
        val HEART_RATE_CHAR_UUID: CBUUID = CBUUID.UUIDWithString("2A37")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectJob: Job? = null

    private val _rawScannedDevices = mutableListOf<BleDevice>()
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: Flow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private var currentFilter = BleFilterConfig()

    override fun applyFilter(config: BleFilterConfig) {
        currentFilter = config
        publishFiltered()
    }

    private fun publishFiltered() {
        _scannedDevices.value = BleDeviceFilter.apply(
            _rawScannedDevices.sortedByDescending { it.rssi }, currentFilter
        )
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    override val deviceInfo: Flow<BleDevice?> = _deviceInfo.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(true)
    override val isBluetoothEnabled: Flow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _characteristicUpdates = MutableSharedFlow<GattCharacteristicUpdate>(extraBufferCapacity = 16)
    override val characteristicUpdates: Flow<GattCharacteristicUpdate> = _characteristicUpdates.asSharedFlow()

    private var centralManager: CBCentralManager? = null
    private var connectedPeripheral: CBPeripheral? = null
    private var targetDevice: BleDevice? = null
    private var reconnectAttempts = 0

    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()

    init {
        centralManager = CBCentralManager(
            delegate = centralDelegate,
            queue = null,
            options = mapOf(
                CBCentralManagerOptionRestoreIdentifierKey to RESTORE_ID,
                CBCentralManagerOptionShowPowerAlertKey to NSNumber(bool = true)
            )
        )
    }

    override fun startScan() {
        _rawScannedDevices.clear()
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        if (centralManager?.state == CBCentralManagerStatePoweredOn) {
            centralManager?.scanForPeripheralsWithServices(null, options = null)
        }
    }

    override fun stopScan() {
        centralManager?.stopScan()
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun connect(device: BleDevice) {
        targetDevice = device
        reconnectAttempts = 0
        performConnect(device)
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        targetDevice = null
        reconnectAttempts = 0
        connectedPeripheral?.let { centralManager?.cancelPeripheralConnection(it) }
        connectedPeripheral = null
        _connectionState.value = ConnectionState.Disconnected
        _deviceInfo.value = null
    }

    private fun performConnect(device: BleDevice) {
        _connectionState.value = ConnectionState.Connecting
        val uuids = listOf(NSUUID(uUIDString = device.address))
        @Suppress("UNCHECKED_CAST")
        val known = centralManager?.retrievePeripheralsWithIdentifiers(uuids) as? List<CBPeripheral>
        val peripheral = known?.firstOrNull()
        if (peripheral != null) {
            connectToPeripheral(peripheral)
        } else {
            _connectionState.value = ConnectionState.Scanning
            centralManager?.scanForPeripheralsWithServices(null, options = null)
        }
    }

    private fun connectToPeripheral(peripheral: CBPeripheral) {
        connectedPeripheral = peripheral
        peripheral.delegate = peripheralDelegate
        centralManager?.connectPeripheral(
            peripheral,
            options = mapOf(
                CBConnectPeripheralOptionNotifyOnConnectionKey to NSNumber(bool = true),
                CBConnectPeripheralOptionNotifyOnDisconnectionKey to NSNumber(bool = true)
            )
        )
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        _connectionState.value = ConnectionState.Reconnecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS * reconnectAttempts)
            targetDevice?.let { performConnect(it) }
        }
    }

    private fun handleCharacteristicBytes(uuidString: String, byteArray: ByteArray) {
        when (uuidString.uppercase()) {
            "2A19" -> {
                val battery = GattParser.parseBatteryLevel(byteArray)
                _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = battery)
                if (battery != null) scope.launch {
                    _characteristicUpdates.emit(GattCharacteristicUpdate.BatteryLevel(battery))
                }
            }
            "2A37" -> {
                val hr = GattParser.parseHeartRate(byteArray)
                _deviceInfo.value = _deviceInfo.value?.copy(heartRate = hr)
                if (hr != null) scope.launch {
                    _characteristicUpdates.emit(GattCharacteristicUpdate.HeartRate(hr))
                }
            }
            else -> scope.launch {
                _characteristicUpdates.emit(GattCharacteristicUpdate.CustomData(uuidString, byteArray.copyOf()))
            }
        }
    }

    inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBCentralManagerStatePoweredOn) {
                _isBluetoothEnabled.value = true
                when (_connectionState.value) {
                    is ConnectionState.Scanning ->
                        central.scanForPeripheralsWithServices(null, options = null)
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting ->
                        targetDevice?.let { performConnect(it) }
                    is ConnectionState.BluetoothDisabled ->
                        _connectionState.value = ConnectionState.Disconnected
                    else -> Unit
                }
            } else {
                _isBluetoothEnabled.value = false
                _rawScannedDevices.clear()
                _scannedDevices.value = emptyList()
                _deviceInfo.value = null
                _connectionState.value = ConnectionState.BluetoothDisabled
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val name = didDiscoverPeripheral.name
            val address = didDiscoverPeripheral.identifier.UUIDString
            val rssi = RSSI.intValue
            val idx = _rawScannedDevices.indexOfFirst { it.address == address }
            if (idx >= 0) {
                _rawScannedDevices[idx] = _rawScannedDevices[idx].copy(name = name, rssi = rssi)
            } else {
                _rawScannedDevices.add(BleDevice(name = name, address = address, rssi = rssi))
            }
            publishFiltered()
            if (_connectionState.value is ConnectionState.Scanning && targetDevice?.address == address) {
                central.stopScan()
                connectToPeripheral(didDiscoverPeripheral)
            }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            targetDevice?.let { _deviceInfo.value = it }
            didConnectPeripheral.discoverServices(null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            if (targetDevice != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
                _deviceInfo.value = null
                connectedPeripheral = null
            }
        }

        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            @Suppress("UNCHECKED_CAST")
            val peripherals = willRestoreState["kCBRestoredPeripherals"] as? List<CBPeripheral>
            peripherals?.forEach { it.delegate = peripheralDelegate }
        }
    }

    inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            peripheral.services?.filterIsInstance<CBService>()?.forEach { service ->
                when (service.UUID.UUIDString.uppercase()) {
                    "180F" -> peripheral.discoverCharacteristics(listOf(BATTERY_CHAR_UUID), service)
                    "180D" -> peripheral.discoverCharacteristics(listOf(HEART_RATE_CHAR_UUID), service)
                    else   -> peripheral.discoverCharacteristics(null, service)
                }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                ?.forEach { characteristic ->
                    when (characteristic.UUID.UUIDString.uppercase()) {
                        "2A19" -> {
                            peripheral.readValueForCharacteristic(characteristic)
                            peripheral.setNotifyValue(true, characteristic)
                        }
                        "2A37" -> peripheral.setNotifyValue(true, characteristic)
                    }
                }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) return
            val data = didUpdateValueForCharacteristic.value ?: return
            val length = data.length.toInt()
            val byteArray = ByteArray(length).also { arr ->
                val ptr = data.bytes?.reinterpret<ByteVar>() ?: return
                for (i in 0 until length) arr[i] = ptr[i]
            }
            handleCharacteristicBytes(didUpdateValueForCharacteristic.UUID.UUIDString, byteArray)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?
        ) { }
    }
}
