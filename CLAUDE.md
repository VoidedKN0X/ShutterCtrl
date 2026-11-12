# ShutterCtrl

Canon camera remote in the VKNXWorks Ctrl series (replaces Canon Camera Connect over Wi-Fi). Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. PTP/IP over plain sockets, no networking library.

## Layout

- `ptp/PtpIp.kt`: PTP/IP framing (length + type + payload, all packet types). `ptp/Datasets.kt`: DeviceInfo, ObjectInfo, Canon GetEvent records, viewfinder JPEG, SetDevicePropValueEx data, the firmware chunk. `ptp/Ptp.kt`: op, response, property and event codes (README "Protocol" says which were seen in Canon's app). Pure Kotlin with JVM tests in `app/src/test`.
- `ptp/PtpIpClient.kt`: command and event sockets, one synchronized transaction at a time, data in/out, probe answers.
- `net/LocalAddress.kt`: the private/link-local allowlist; every address goes through it. `net/CameraNetwork.kt`: follows the Wi-Fi `Network` (ACCESS_NETWORK_STATE only) and makes camera sockets on it. `net/Ssdp.kt`: M-SEARCH and the UPnP description read (raw socket, same address only).
- `camera/CameraSession.kt`: the one connection (session set-up, remote mode, GetEvent poll, card, downloads, shutter, live view, firmware). `camera/Saver.kt`: MediaStore Pictures/ShutterCtrl with IS_PENDING; LumenCtrl intents. `camera/Firmware.kt`: the firmware safety rules. `camera/IntervalService.kt`: the intervalometer foreground service.
- `ui/camera` (Camera tab, live view, firmware), `ui/card` (Card tab).

## Hard rules

- **Local network only.** INTERNET is only for sockets to private and link-local addresses (`LocalAddress`); never add a host name lookup, a cloud call or an HTTP client. No Canon account, image.canon or update check.
- Debug builds also accept 127.0.0.1 (`LocalAddress.allowLoopback`), for testing against a simulated camera through `adb reverse tcp:15740 tcp:15740`. Release builds never do.
- **No location permission**, no ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / CHANGE_NETWORK_STATE, no Bluetooth. CI fails if the APK's permissions differ from the allowed list in `build.yml`.
- **Firmware:** never guess opcodes. Keep every safety check (full battery, model match or explicit confirmation, confirmation dialog, blocking screen). The app never starts an install; the camera does.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- The intervalometer service runs only while a series is active.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "ShutterCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
