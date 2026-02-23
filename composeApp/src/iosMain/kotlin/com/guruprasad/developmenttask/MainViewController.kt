package com.guruprasad.developmenttask

import androidx.compose.ui.window.ComposeUIViewController
import com.guruprasad.developmenttask.ble.createBleRepository
import com.guruprasad.developmenttask.ui.BleNavigation

/** App-scoped [IosBleRepository] instance — created once and reused across recompositions. */
private val bleRepository by lazy { createBleRepository(null) }

/**
 * iOS entry point — called from [ContentView.swift] via the KMP framework.
 *
 * Creates a [ComposeUIViewController] hosting [BleNavigation] with the shared
 * [bleRepository] instance. The repository's [CBCentralManager] is initialised
 * here with state-restoration support so CoreBluetooth can wake the app in the
 * background when the BLE connection drops.
 */
fun MainViewController() = ComposeUIViewController {
    BleNavigation(repository = bleRepository)
}

