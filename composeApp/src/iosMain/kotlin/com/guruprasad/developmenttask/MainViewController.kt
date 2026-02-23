package com.guruprasad.developmenttask

import androidx.compose.ui.window.ComposeUIViewController
import com.guruprasad.developmenttask.ble.createBleRepository
import com.guruprasad.developmenttask.ui.BleNavigation

// Single instance of the repository for the app lifecycle
private val bleRepository by lazy { createBleRepository(null) }

fun MainViewController() = ComposeUIViewController {
    BleNavigation(repository = bleRepository)
}

