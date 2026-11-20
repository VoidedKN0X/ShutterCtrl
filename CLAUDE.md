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
- `geo/GeoTag.kt` (`LocationTrack`, `GeoExif`: capture time from EXIF, nearest fix within 10 min, GPS tags via androidx.exifinterface; JVM-tested with a test-only `android.util.Pair`), `geo/LocationLogger.kt` (LocationManager, foreground only), `geo/LocationLogFile.kt` (the log on disk, sealed with `data/crypto`). Downloads are tagged in `Saver.finish` before IS_PENDING is cleared; JPEG only.
- `ui/camera` (Camera tab with `BracketGroup`, live view with `FocusStackGroup`, firmware), `ui/card` (Card tab).

## Hard rules

- **Local network only.** INTERNET is only for sockets to private and link-local addresses (`LocalAddress`); never add a host name lookup, a cloud call or an HTTP client. No Canon account, image.canon or update check.
- Debug builds also accept 127.0.0.1 (`LocalAddress.allowLoopback`), for testing against a simulated camera through `adb reverse tcp:15740 tcp:15740`. Release builds never do.
- **Location only for opt-in geotagging:** ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION are requested only when the user turns on Geotag downloads (off by default). The log (geo/LocationLogger) runs only while the option is on and the app is in the foreground, keeps 24 h in noBackupFilesDir (encrypted at rest, see below) and is deleted when the option goes off. No background location, no location in foreground services. No ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / CHANGE_NETWORK_STATE, no Bluetooth. USB uses only `android.hardware.usb.host` (required="false") and the per-device runtime dialog; never add `MANAGE_USB` and never auto-connect from the attach intent. CI fails if the APK's permissions differ from the allowed list in `build.yml`.
- **Firmware:** never guess opcodes. Keep every safety check (full battery, model match or explicit confirmation, confirmation dialog, blocking screen). The app never starts an install; the camera does.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- The intervalometer service runs only while a series is active.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Encryption at rest

- Only `noBackupFilesDir/location-log.csv` (the geotagging log) is sealed: AES-256-GCM (`data/crypto/SealedFile`, marker `SHUTTERCTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `shutterctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication, independent of the app lock). The key is made the first time a log is written, so there is none while geotagging has never been on.
- `geo/LocationLogFile` is the only reader and writer and is only called on `LocationLogger`'s own thread: never touch the file or the data key on the main thread. It reads both sealed and ordinary logs; an ordinary one becomes sealed only through `SealedFile.sealInPlace`. Without a usable data key the log is read and written as an ordinary file.
- The log is a 24-hour working file, so it gets the simple treatment: a sealed log that can't be opened is deleted and a new one starts (no UI). Never replace an existing `data.key` and never create a Keystore key on the decrypt path (`KeystoreAes.existing`).
- Not encrypted, on purpose: the settings DataStore (`last_host`, the PTP/IP `guid`, toggles), which the connection code reads; the app lock's own `app_lock_vault` preferences are already protected and must not be touched. Downloads are in the shared media library.
- `SealedFileTest` and `LocationLogFileTest` cover the format, the migration step, the key rules and the log file (`./gradlew :app:testDebugUnitTest`; CI only runs `assembleRelease`, so run them locally).

## Build toolchain

- AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37 (Compose 1.12 needs it), `minSdk` 35, `targetSdk` 36. All versions are in `gradle/libs.versions.toml`.
- AGP 9 has built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin any more. The Kotlin version is pinned by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle.kts`; the Compose and serialization compiler plugins use the same `kotlin` version. Don't re-add the Kotlin Android plugin.
- AGP 9's R8 is stricter (`-keep class X` without a member block no longer keeps the constructor; resources are shrunk together with code). Keep rules in `proguard-rules.pro` must name the members they need, and resources must be referenced through `R`, never by name.
- Any version change here needs new checksums in `gradle/verification-metadata.xml`: push the change, start the `verification-metadata.yml` workflow on `main`, and commit the file it prints, unchanged. Never write or edit that file by hand and never make verification lenient.

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.
- `settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made dependency verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "ShutterCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
