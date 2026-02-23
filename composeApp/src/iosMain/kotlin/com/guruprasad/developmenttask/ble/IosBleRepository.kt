package com.guruprasad.developmenttask.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * iOS implementation of [BleRepository] using CoreBluetooth.
 *
 * Uses [CBCentralManager] for scanning / connection management and
 * [CBPeripheral] for GATT characteristic reads.
 *
 * Background operation: The [CBCentralManager] is initialised with a
 * restore identifier so iOS can wake the app in the background and
 * restore the Bluetooth state.
 */
class IosBleRepository : BleRepository {

    companion object {
        private const val RESTORE_ID = "com.guruprasad.ble.central"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L

        // Standard Battery Service
        val BATTERY_SERVICE_UUID: CBUUID = CBUUID.UUIDWithString("180F")
        val BATTERY_CHAR_UUID: CBUUID = CBUUID.UUIDWithString("2A19")

        // Optional Heart-Rate Service
        val HEART_RATE_SERVICE_UUID: CBUUID = CBUUID.UUIDWithString("180D")
        val HEART_RATE_CHAR_UUID: CBUUID = CBUUID.UUIDWithString("2A37")
    }

    // ── Coroutine scope ────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectJob: Job? = null

    // ── State ──────────────────────────────────────────────────────────────
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: Flow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    override val deviceInfo: Flow<BleDevice?> = _deviceInfo.asStateFlow()

    // ── CoreBluetooth objects ──────────────────────────────────────────────
    private var centralManager: CBCentralManager? = null
    private var connectedPeripheral: CBPeripheral? = null
    private var targetDevice: BleDevice? = null
    private var reconnectAttempts = 0

    private val centralDelegate = CentralManagerDelegate()
    private val peripheralDelegate = PeripheralDelegate()

    init {
        // Initialise with restore identifier for background support
        centralManager = CBCentralManager(
            delegate = centralDelegate,
            queue = null,
            options = mapOf(
                CBCentralManagerOptionRestoreIdentifierKey to RESTORE_ID,
                CBCentralManagerOptionShowPowerAlertKey to NSNumber(bool = true)
            )
        )
    }

    // ── BleRepository implementation ───────────────────────────────────────

    override fun startScan() {
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        if (centralManager?.state == CBCentralManagerStatePoweredOn) {
            centralManager?.scanForPeripheralsWithServices(null, options = null)
        }
        // If not yet powered on, scan will be started in centralManagerDidUpdateState
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
        connectedPeripheral?.let { peripheral ->
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        connectedPeripheral = null
        _connectionState.value = ConnectionState.Disconnected
        _deviceInfo.value = null
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun performConnect(device: BleDevice) {
        _connectionState.value = ConnectionState.Connecting

        // Try to find the peripheral in the known/scanned list first
        val uuidString = device.address
        val uuids = listOf(NSUUID(uUIDString = uuidString))

        @Suppress("UNCHECKED_CAST")
        val knownPeripherals = centralManager?.retrievePeripheralsWithIdentifiers(
            uuids as NSArray
        ) as? List<CBPeripheral>

        val peripheral = knownPeripherals?.firstOrNull()
            ?: run {
                // Fallback: search in scanned cache by matching UUID string
                null
            }

        if (peripheral != null) {
            connectToPeripheral(peripheral)
        } else {
            // Re-scan briefly to find the peripheral
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
        val delayMs = RECONNECT_DELAY_MS * reconnectAttempts
        _connectionState.value = ConnectionState.Reconnecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            targetDevice?.let { performConnect(it) }
        }
    }

    // ── CBCentralManager delegate ──────────────────────────────────────────

    inner class CentralManagerDelegate : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBCentralManagerStatePoweredOn) {
                // If a scan was requested before Bluetooth was ready, start it now
                if (_connectionState.value is ConnectionState.Scanning) {
                    central.scanForPeripheralsWithServices(null, options = null)
                }
                // Re-connect to target device if we were trying to connect
                if (_connectionState.value is ConnectionState.Connecting ||
                    _connectionState.value is ConnectionState.Reconnecting
                ) {
                    targetDevice?.let { performConnect(it) }
                }
            } else {
                _connectionState.value = ConnectionState.Error("Bluetooth unavailable (state ${central.state})")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val peripheral = didDiscoverPeripheral
            val name = peripheral.name
            val address = peripheral.identifier.UUIDString
            val rssi = RSSI.intValue

            val device = BleDevice(name = name, address = address, rssi = rssi)
            val current = _scannedDevices.value.toMutableList()
            val idx = current.indexOfFirst { it.address == address }
            if (idx >= 0) {
                current[idx] = current[idx].copy(name = name, rssi = rssi)
            } else {
                current.add(device)
            }
            _scannedDevices.value = current.sortedByDescending { it.rssi }

            // If this is the device we're trying to connect to, connect now
            if (_connectionState.value is ConnectionState.Scanning &&
                targetDevice?.address == address
            ) {
                central.stopScan()
                connectToPeripheral(peripheral)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            targetDevice?.let { _deviceInfo.value = it }

            // Discover all services
            didConnectPeripheral.discoverServices(null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            val intentional = targetDevice == null
            if (!intentional && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
                _deviceInfo.value = null
                connectedPeripheral = null
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            if (targetDevice != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Error(
                    error?.localizedDescription ?: "Failed to connect"
                )
            }
        }

        // State restoration
        override fun centralManager(
            central: CBCentralManager,
            willRestoreState: Map<Any?, *>
        ) {
            // Re-attach delegate to any restored peripherals
            @Suppress("UNCHECKED_CAST")
            val peripherals = willRestoreState["kCBRestoredPeripherals"] as? List<CBPeripheral>
            peripherals?.forEach { it.delegate = peripheralDelegate }
        }
    }

    // ── CBPeripheral delegate ──────────────────────────────────────────────

    inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverServices: NSError?
        ) {
            peripheral.services?.filterIsInstance<CBService>()?.forEach { service ->
                when (service.UUID.UUIDString.uppercase()) {
                    "180F" -> peripheral.discoverCharacteristics(listOf(BATTERY_CHAR_UUID), service)
                    "180D" -> peripheral.discoverCharacteristics(listOf(HEART_RATE_CHAR_UUID), service)
                    else -> peripheral.discoverCharacteristics(null, service)
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
                    val uuid = characteristic.UUID.UUIDString.uppercase()
                    when (uuid) {
                        "2A19" -> peripheral.readValueForCharacteristic(characteristic)
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
            val bytes = data.bytes ?: return
            val length = data.length.toInt()
            val byteArray = ByteArray(length) { i ->
                (bytes.reinterpret<kotlinx.cinterop.ByteVar>()[i])
            }

            when (didUpdateValueForCharacteristic.UUID.UUIDString.uppercase()) {
                "2A19" -> {
                    val battery = byteArray.firstOrNull()?.toInt()?.and(0xFF)
                    _deviceInfo.value = _deviceInfo.value?.copy(batteryLevel = battery)
                }
                "2A37" -> {
                    // Heart rate measurement parsing
                    if (byteArray.isNotEmpty()) {
                        val flags = byteArray[0].toInt()
                        val hr = if (flags and 0x01 == 0) {
                            byteArray.getOrNull(1)?.toInt()?.and(0xFF)
                        } else {
                            if (byteArray.size >= 3)
                                ((byteArray[2].toInt() and 0xFF) shl 8) or (byteArray[1].toInt() and 0xFF)
                            else null
                        }
                        // Heart rate data available – could be exposed via a separate flow
                        println("Heart Rate: $hr bpm")
                    }
                }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            // Notification subscription confirmed
        }
    }
}

