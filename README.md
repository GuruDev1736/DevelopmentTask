# Kotlin Multiplatform BLE Connection

A **Kotlin Multiplatform (KMP)** application that connects to a Bluetooth Low Energy (BLE) device, maintains a continuous background connection, and displays real-time device information on both **Android** and **iOS**.

---

## Project Structure

```
composeApp/
├── src/
│   ├── commonMain/          # Shared logic — models, interfaces, ViewModel, UI
│   │   └── kotlin/.../
│   │       ├── ble/
│   │       │   ├── BleDevice.kt              # Shared data model
│   │       │   ├── ConnectionState.kt        # Connection state machine
│   │       │   ├── BleRepository.kt          # Platform-agnostic BLE interface
│   │       │   ├── BleRepositoryFactory.kt   # expect factory
│   │       │   ├── BleViewModel.kt           # Shared ViewModel (Flow → StateFlow)
│   │       │   ├── BleDeviceFilter.kt        # Shared device filtering logic
│   │       │   ├── GattParser.kt             # Shared GATT characteristic parsing
│   │       │   └── GattCharacteristicUpdate.kt # Typed characteristic update events
│   │       └── ui/
│   │           ├── DeviceListScreen.kt       # Scan list UI
│   │           ├── DeviceDetailScreen.kt     # Connected device details UI
│   │           └── Navigation.kt            # NavHost routing
│   │
│   ├── androidMain/         # Android-specific BLE implementation
│   │   └── kotlin/.../
│   │       ├── MainActivity.kt
│   │       └── ble/
│   │           ├── AndroidBleRepository.kt   # BluetoothLeScanner + BluetoothGatt
│   │           ├── BleForegroundService.kt   # Foreground Service for background BLE
│   │           └── BleRepositoryFactory.android.kt
│   │
│   └── iosMain/             # iOS-specific BLE implementation
│       └── kotlin/.../
│           ├── MainViewController.kt
│           └── ble/
│               ├── IosBleRepository.kt       # CBCentralManager + CBPeripheral
│               └── BleRepositoryFactory.ios.kt
│
iosApp/
└── iosApp/
    └── Info.plist            # UIBackgroundModes → bluetooth-central
```

---

## Shared Module (`commonMain`)

### Data Models

| Class | Description |
|---|---|
| `BleDevice` | Discovered/connected peripheral — name, address (MAC/UUID), RSSI, battery %, heart rate |
| `ConnectionState` | Sealed class — `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Reconnecting`, `BluetoothDisabled`, `Error` |
| `BleFilterConfig` | Filter parameters — name query, min RSSI, named-only toggle |
| `GattCharacteristicUpdate` | Typed GATT events — `BatteryLevel`, `HeartRate`, `CustomData` |

### Shared Interface

```kotlin
interface BleRepository {
    val scannedDevices: Flow<List<BleDevice>>
    val connectionState: Flow<ConnectionState>
    val deviceInfo: Flow<BleDevice?>
    val isBluetoothEnabled: Flow<Boolean>
    val characteristicUpdates: Flow<GattCharacteristicUpdate>

    fun startScan()
    fun stopScan()
    suspend fun connect(device: BleDevice)
    fun disconnect()
    fun applyFilter(config: BleFilterConfig)
}
```

### Shared Business Logic

- **`BleDeviceFilter`** — filters scanned devices by name/address query, minimum RSSI, named-only
- **`GattParser`** — parses Battery Level (0x2A19), Heart Rate (0x2A37), Device Name (0x2A00)
- **`BleViewModel`** — bridges `Flow` streams into `StateFlow`s; exposes `batteryLevel`, `heartRate`, `scannedDevices` (filtered), `characteristicUpdates`

---

## Android Module (`androidMain`)

### `AndroidBleRepository`
- Scans using `BluetoothLeScanner` with `SCAN_MODE_LOW_LATENCY`; auto-stops after 30 s
- Connects via `BluetoothGatt` with `TRANSPORT_LE`
- **Battery reading strategy:**
  1. Standard Battery Service `0x180F` / `0x2A19`
  2. Custom vendor service `0x0AF0` (ByBoat watch) — reads all characteristics
  3. Heuristic auto-detection: single-byte value in range 1–100
  4. Subscribes to CCCD notifications for real-time updates
- Heart Rate Service `0x180D` / `0x2A37` — CCCD notifications enabled
- Reconnects up to **5 times** with linear back-off (2 s × attempt)
- `BroadcastReceiver` on `ACTION_STATE_CHANGED` — clears state instantly when BT turns off

### `BleForegroundService`
- Started when GATT connects; stopped on disconnect/BT-off
- Posts a persistent "BLE Connected" notification (`IMPORTANCE_LOW`)
- Bound from `MainActivity` for lifecycle management

### Required Permissions (`AndroidManifest.xml`)
```xml
BLUETOOTH_SCAN, BLUETOOTH_CONNECT          <!-- API 31+ -->
BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION  <!-- API ≤ 30 -->
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE
```

---

## iOS Module (`iosMain`)

### `IosBleRepository`
- Scans using `CBCentralManager.scanForPeripheralsWithServices(nil)`
- Connects via `CBCentralManager.connectPeripheral` with notify-on-connection options
- State restoration via `CBCentralManagerOptionRestoreIdentifierKey` for background wake
- Reads Battery Level (0x2A19) immediately on characteristic discovery + subscribes to notify
- Heart Rate (0x2A37) — notifications enabled
- Reconnects up to **5 times** with linear back-off
- `centralManagerDidUpdateState` drives `isBluetoothEnabled` and `BluetoothDisabled` state

### Background Mode (`Info.plist`)
```xml
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
</array>
```

---

## Sample UI

| Screen | Features |
|---|---|
| **Device List** | Live scan list sorted by RSSI; signal strength badge (Strong/Medium/Weak); search by name/address; named-only filter toggle; Bluetooth disabled banner |
| **Device Detail** | Device name, MAC/UUID, signal strength, battery % with color indicator (🔋/🪫), heart rate; real-time updates via StateFlow; reconnecting spinner |

UI updates reactively — every `StateFlow` is collected with `collectAsState()` so recomposition happens automatically on each GATT notification.

---

## Optional: Additional GATT Characteristics

Heart Rate Service is fully implemented:
- Service UUID: `0x180D`
- Characteristic UUID: `0x2A37`
- Parsed via `GattParser.parseHeartRate()` — handles both 8-bit and 16-bit HR value formats
- Displayed in `DeviceDetailScreen` when available

Any unknown characteristic is emitted as `GattCharacteristicUpdate.CustomData` with its UUID and raw bytes.
