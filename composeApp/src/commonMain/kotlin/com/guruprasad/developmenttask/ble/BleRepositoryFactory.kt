package com.guruprasad.developmenttask.ble

    /**
 * `expect` factory function resolved by each platform target.
 *
 * - **Android** (`BleRepositoryFactory.android.kt`) — instantiates [AndroidBleRepository]
 *   with the application [android.content.Context].
 * - **iOS** (`BleRepositoryFactory.ios.kt`) — instantiates [IosBleRepository]; `context`
 *   is unused and should be passed as `null`.
 *
 * @param context Platform context. Pass [android.content.Context] on Android; `null` on iOS.
 * @return A platform-specific [BleRepository] instance.
 */
expect fun createBleRepository(context: Any?): BleRepository
