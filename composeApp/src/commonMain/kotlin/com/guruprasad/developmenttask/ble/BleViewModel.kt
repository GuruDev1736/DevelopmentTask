package com.guruprasad.developmenttask.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared ViewModel that drives the BLE UI on both Android and iOS.
 *
 * Bridges the reactive [BleRepository] streams into [StateFlow]s that Compose screens
 * collect efficiently. All business logic (filtering, connection control) lives here —
 * platform screens contain only UI code.
 *
 * @param repository Platform-specific [BleRepository] injected at construction time.
 */
class BleViewModel(private val repository: BleRepository) : ViewModel() {

    /** Current BLE connection lifecycle state. Replays the latest value to new collectors. */
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Disconnected)

    val deviceInfo: StateFlow<BleDevice?> = repository.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isBluetoothEnabled: StateFlow<Boolean> = repository.isBluetoothEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Battery percentage from the connected device. Null until the first GATT read completes. */
    @Suppress("unused")
    val batteryLevel: StateFlow<Int?> = repository.deviceInfo
        .map { it?.batteryLevel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Heart rate in bpm. Null until the first Heart Rate notification arrives. */
    @Suppress("unused")
    val heartRate: StateFlow<Int?> = repository.deviceInfo
        .map { it?.heartRate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stream of individual typed GATT characteristic update events. */
    @Suppress("unused")
    val characteristicUpdates: StateFlow<GattCharacteristicUpdate?> = repository.characteristicUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _filterConfig = MutableStateFlow(BleFilterConfig())
    val filterConfig: StateFlow<BleFilterConfig> = _filterConfig.asStateFlow()

    val scannedDevices: StateFlow<List<BleDevice>> = combine(
        repository.scannedDevices,
        _filterConfig
    ) { devices, config ->
        BleDeviceFilter.apply(devices, config)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isScanning = MutableStateFlow(false)

    /** True while a BLE scan is actively running. */
    @Suppress("unused")
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan() {
        _isScanning.value = true
        repository.startScan()
    }

    fun stopScan() {
        _isScanning.value = false
        repository.stopScan()
    }

    fun connect(device: BleDevice) {
        stopScan()
        viewModelScope.launch { repository.connect(device) }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun updateFilter(config: BleFilterConfig) {
        _filterConfig.value = config
        repository.applyFilter(config)
    }

    /** Removes all active filters and re-emits the full unfiltered device list. */
    @Suppress("unused")
    fun clearFilter() {
        _filterConfig.value = BleFilterConfig()
        repository.applyFilter(BleFilterConfig())
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopScan()
        repository.disconnect()
    }
}
