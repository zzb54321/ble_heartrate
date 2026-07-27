# BLE Heart Rate Monitor

An Android app that subscribes to heart rate data from Bluetooth Low Energy (BLE) devices,
supports a configurable vibration threshold, and can run in the background as a foreground service.

## Features

- **BLE Scan** — Discovers nearby BLE devices and highlights devices that advertise the standard Heart Rate Service (UUID `0x180D`)
- **Heart Rate Display** — Shows the live heart rate value in BPM; turns orange when the threshold is exceeded
- **Vibration Threshold** — Set a BPM limit; the phone vibrates continuously whenever the heart rate exceeds the threshold and stops when it falls back below
- **Optional Alerts** — Independently switchable channels that trigger while the heart rate is above a threshold: vibration, an alarm tone that beeps at the configured alert frequency, a red border drawn around the screen (requires the "display over other apps" permission), and a blinking red app background
- **Selectable Alert Tone** — Several alarm tones can be picked from a drop-down shown next to the alert tone switch; the choice is remembered
- **Quick Reconnect** — The last connected device is shown above the device list; tapping it reconnects immediately without scanning
- **Rule Management** — Every threshold rule can be temporarily disabled or deleted from its list row, and a single button pauses or resumes all alerts without touching the rules
- **Heart Rate Chart** — A collapsible line chart plots the most recent samples together with a dashed threshold reference line
- **Scrollable, Collapsible UI** — The whole screen scrolls, and the chart, the live log and the alert options can be collapsed; the state is remembered between launches
- **Background Operation** — A persistent foreground service keeps the BLE connection alive and monitors heart rate even when the app is minimised
- **Threshold Persistence** — The threshold is saved to SharedPreferences and restored automatically on the next launch

## Requirements

| Requirement | Value |
|---|---|
| Minimum SDK | API 21 (Android 5.0 Lollipop) |
| Target SDK | API 35 (Android 15) |
| Language | Kotlin |
| Permissions | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+) / `ACCESS_FINE_LOCATION` (API < 31), `VIBRATE`, `FOREGROUND_SERVICE`, `SYSTEM_ALERT_WINDOW` (optional, for the screen border alert) |

## Pre-built APK

A ready-to-install debug APK is available at:

```
app/release/ble_heartrate_debug.apk
```

Install it on a device with:

```bash
adb install app/release/ble_heartrate_debug.apk
```

## Building from source

### Using Android Studio (recommended)
Open the project in Android Studio (Hedgehog or newer) and click **Build → Build APK**.

### Manual command-line build (no network required)

The project includes a shell script that builds the APK using only the Android SDK
command-line tools (`aapt2`, `kotlinc`, `d8`, `apksigner`):

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
bash scripts/build_apk.sh
```

The signed APK is placed at `app/release/ble_heartrate_debug.apk`.

## Architecture

| Component | Description |
|---|---|
| `MainActivity` | UI: scan, device list, heart-rate display, threshold input |
| `HeartRateChartView` | Custom view that draws the recent heart-rate samples as a line chart |
| `HeartRateService` | Foreground service: BLE GATT connection, notifications subscription, threshold evaluation, vibration control |

## Usage

1. Open the app and grant the requested Bluetooth (and location on Android < 12) permissions.
2. Tap **Scan** — the app searches for nearby BLE devices for 10 seconds and labels heart rate advertisers in the list.
3. Tap a device in the list to select it, then tap **Connect**.
4. Once connected and subscribed, the heart rate updates in real time.
5. Enter a BPM threshold in the text field and tap **Set** — the phone will vibrate while the heart rate exceeds this value.
6. The foreground service keeps running in the background; use **Disconnect** to stop monitoring.
