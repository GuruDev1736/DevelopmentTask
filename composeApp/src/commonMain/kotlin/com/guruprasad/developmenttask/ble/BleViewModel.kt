package com.guruprasad.developmenttask.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BleViewModel(private val repository: BleRepository) : ViewModel() {

    val scannedDevices: StateFlow<List<BleDevice>> = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Disconnected)

    val deviceInfo: StateFlow<BleDevice?> = repository.deviceInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isScanning = MutableStateFlow(false)
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
