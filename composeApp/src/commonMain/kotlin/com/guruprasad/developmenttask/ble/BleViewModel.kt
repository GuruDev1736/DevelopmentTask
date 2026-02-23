package com.guruprasad.developmenttask.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared ViewModel that drives the BLE UI on both Android and iOS.
 *
 * It bridges the reactive [BleRepository] streams into [StateFlow]s that
 * Compose screens can collect efficiently.
 */
class BleViewModel(private val repository: BleRepository) : ViewModel() {

    /** Latest list of devices found during scanning. */
    val scannedDevices: StateFlow<List<BleDevice>> = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current BLE connection lifecycle state. */
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Disconnected)

    /** Details of the currently connected device (including battery level). */
    val deviceInfo: StateFlow<BleDevice?> = repository.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Internal flag: true while a scan is active. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ── Scan controls ────────────────────────────────────────────────────────

    fun startScan() {
        _isScanning.value = true
        repository.startScan()
    }

    fun stopScan() {
        _isScanning.value = false
        repository.stopScan()
    }

    // ── Connection controls ───────────────────────────────────────────────────

    fun connect(device: BleDevice) {
        stopScan()
        viewModelScope.launch {
            repository.connect(device)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopScan()
        repository.disconnect()
    }
}

