# ShutterCtrl

Canon camera remote in the VKNXWorks Ctrl series (replaces Canon Camera Connect over Wi-Fi, and works over a USB cable too). Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. PTP/IP over plain sockets and PTP over Android's USB host API, no networking or USB library.

## Layout

- `ptp/PtpTransport.kt`: the transport interface (`transactTo`, `transactStreamOut`, `transact`, events, `onClosed`) and `PtpResult`. Everything above it (`CameraSession`) only sees this, so every feature runs over either transport unchanged.
- `ptp/PtpIp.kt`: PTP/IP framing (length + type + payload, all packet types). `ptp/Datasets.kt`: DeviceInfo, ObjectInfo, Canon GetEvent records, viewfinder JPEG, SetDevicePropValueEx data, the firmware chunk. `ptp/Ptp.kt`: op, response, property and event codes (README "Protocol" says which were seen in Canon's app), `PtpException`, `UsbDetachedException`, `UsbOpenException`. Pure Kotlin with JVM tests in `app/src/test`.
- `ptp/PtpIpClient.kt`: the PTP/IP transport: command and event sockets, one synchronized transaction at a time, data in/out, probe answers.
- `ptp/PtpUsb.kt`: USB container framing (12-byte header), class request data, the zero-length-packet rule, `UsbEventAssembler`, and the `UsbPipe` interface (endpoints + class requests). `ptp/PtpUsbClient.kt`: the USB transport: bulk-in read as a stream of transfers (split containers, glued containers, ZLPs), whole-packet out transfers + ZLP, interrupt-endpoint event thread, Cancel on a local failure, Get Device Status / Device Reset at start, transaction id 0 for GetDeviceInfo/OpenSession. Tested with `FakeUsbCamera` (packet-level simulated camera) in `app/src/test`.
- `usb/AndroidUsbPipe.kt`: `UsbPipe` on `UsbDeviceConnection` (claims the class 6/1/1 interface with force; close waits for in-flight transfers before releasing). `usb/UsbCameras.kt`: the attached Canon cameras (vendor 0x04A9) from `UsbManager`, attach/detach receiver (not exported), permission request (immutable PendingIntent with explicit package; the answer is re-read from `UsbManager`, never from extras). `res/xml/usb_device_filter.xml` + the `USB_DEVICE_ATTACHED` filter on `MainActivity`, which only refreshes the list.
- `net/LocalAddress.kt`: the private/link-local allowlist; every address goes through it. `net/CameraNetwork.kt`: follows the Wi-Fi `Network` (ACCESS_NETWORK_STATE only) and makes camera sockets on it. `net/Ssdp.kt`: M-SEARCH and the UPnP description read (raw socket, same address only).
- `camera/CameraSession.kt`: the one connection over either transport (`connect` for Wi-Fi, `connectUsb` for the cable; on detach it ends the session, stops the intervalometer and shows `USB_DETACHED`), session set-up, remote mode, GetEvent poll, card, downloads, shutter, live view, firmware). `camera/Saver.kt`: MediaStore Pictures/ShutterCtrl with IS_PENDING; LumenCtrl intents. `camera/Firmware.kt`: the firmware safety rules. `camera/IntervalService.kt`: the intervalometer foreground service.
- `ptp/Sequences.kt`: pure bracketing plan (Canon codes to stops, nearest allowed value, 0/−/+ order, no repeats) and focus-stack plan (DriveLens 0x9155 parameters, libgphoto2). `CameraSession.startBracket`/`startFocusStack` run them; bracketing restores the starting value; stacking shoots without AF (RemoteReleaseOn second parameter 1).
- `geo/GeoTag.kt` (`LocationTrack`, `GeoExif`: capture time from EXIF, nearest fix within 10 min, GPS tags via androidx.exifinterface; JVM-tested with a test-only `android.util.Pair`), `geo/LocationLogger.kt` (LocationManager, foreground only). Downloads are tagged in `Saver.finish` before IS_PENDING is cleared; JPEG only.
- `ui/camera` (Camera tab with `BracketGroup`, live view with `FocusStackGroup`, firmware), `ui/card` (Card tab).

## Hard rules

- **Local network only.** INTERNET is only for sockets to private and link-local addresses (`LocalAddress`); never add a host name lookup, a cloud call or an HTTP client. No Canon account, image.canon or update check.
- Debug builds also accept 127.0.0.1 (`LocalAddress.allowLoopback`), for testing against a simulated camera through `adb reverse tcp:15740 tcp:15740`. Release builds never do.
- **Location only for opt-in geotagging:** ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION are requested only when the user turns on Geotag downloads (off by default). The log (geo/LocationLogger) runs only while the option is on and the app is in the foreground, keeps 24 h in noBackupFilesDir and is deleted when the option goes off. No background location, no location in foreground services. No ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / CHANGE_NETWORK_STATE, no Bluetooth. USB uses only `android.hardware.usb.host` (required="false") and the per-device runtime dialog; never add `MANAGE_USB` and never auto-connect from the attach intent. CI fails if the APK's permissions differ from the allowed list in `build.yml`.
- **Firmware:** never guess opcodes. Keep every safety check (full battery, model match or explicit confirmation, confirmation dialog, blocking screen). The app never starts an install; the camera does.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- The intervalometer service runs only while a series is active.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "ShutterCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
