package com.guruprasad.developmenttask.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.guruprasad.developmenttask.ble.BleRepository
import com.guruprasad.developmenttask.ble.BleViewModel
import kotlin.reflect.KClass

object Routes {
    const val DEVICE_LIST = "device_list"
    const val DEVICE_DETAIL = "device_detail"
}

/**
 * Root navigation graph for the BLE application.
 *
 * @param repository Platform-specific [BleRepository] provided by the host.
 */
@Composable
fun BleNavigation(repository: BleRepository) {
    val navController = rememberNavController()

    @Suppress("UNCHECKED_CAST")
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            return BleViewModel(repository) as T
        }
    }
    val viewModel: BleViewModel = viewModel(factory = factory)

    NavHost(navController = navController, startDestination = Routes.DEVICE_LIST) {
        composable(Routes.DEVICE_LIST) {
            DeviceListScreen(
                viewModel = viewModel,
                onDeviceSelected = { device ->
                    viewModel.connect(device)
                    navController.navigate(Routes.DEVICE_DETAIL)
                }
            )
        }
        composable(Routes.DEVICE_DETAIL) {
            DeviceDetailScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.disconnect()
                    navController.popBackStack()
                }
            )
        }
    }
}
