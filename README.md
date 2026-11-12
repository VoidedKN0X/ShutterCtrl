# ShutterCtrl

**A private remote for your Canon camera over Wi-Fi: card browser, downloads, remote shutter, live view, intervalometer and firmware transfer, with no Canon account and no cloud.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/ShutterCtrl?label=download)](https://github.com/VoidedKN0X/ShutterCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Local network only](https://img.shields.io/badge/internet-local%20network%20only-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

ShutterCtrl replaces Canon Camera Connect for the things you do on Wi-Fi: browse the camera's card, download JPEG and RAW files, fire the shutter, watch live view and change exposure, run an intervalometer, and send a firmware file you downloaded from Canon. It talks to the camera directly with PTP/IP, the standard picture transfer protocol over the network, and to nothing else: no Canon ID, no image.canon, no analytics.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ShutterCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

> **Tested on: none yet.** ShutterCtrl 0.1 was written without a Canon camera at hand. The protocol code follows what Canon Camera Connect itself sends (see [Protocol](#protocol)), has unit tests, and was run against a simulated camera (connect, card, thumbnails, downloads, shutter, exposure, live view, intervalometer), but it hasn't talked to a real camera yet. The firmware transfer has only been unit-tested. If you try it, please [report](https://github.com/VoidedKN0X/ShutterCtrl/issues/new/choose) your camera model and what worked.

## Features

- **Connect over Wi-Fi:** on the camera's own Wi-Fi (no internet) or on your home Wi-Fi. Search the network (SSDP/UPnP), use the camera's access point address, or type its IP address
- **Card browser** with thumbnails from the camera, newest first
- **Download JPEG and RAW** (CR2, CR3) to **Pictures/ShutterCtrl**, with progress. The file only appears in your library once it's complete. RAW types the phone's media library doesn't know (on many phones CR3) go to **Download/ShutterCtrl** instead
- **Open in [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl):** download and edit a JPEG or RAW in one tap, if LumenCtrl is installed
- **Remote shutter:** Focus (half press, held until you release it) and Shoot, with a 2 or 10 second self-timer
- **Live view** from the camera, with **Av, Tv, ISO and exposure compensation** chosen from the values the camera allows in its current mode
- **Intervalometer:** N shots every T seconds, also with the screen off. It runs in a foreground service only while a series is active, with a notification and Stop
- **Firmware transfer:** send a `.FIR` file you downloaded from Canon's website yourself, with safety checks (see [Firmware updates](#firmware-updates))
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono

### Not in 0.1

- **GPS tagging from the phone:** not included. ShutterCtrl has no location permission.
- **Bluetooth pairing, wake-up and the Bluetooth remote:** not included. Turn on the camera's Wi-Fi from the camera's menu instead.
- **Movies:** the card browser lists photos; videos may show up but aren't the focus of this version.
- Canon's cloud services (Canon ID, image.canon, streaming, error-log upload) are left out on purpose.

## Firmware updates

ShutterCtrl never downloads firmware and never contacts Canon. You download the `.FIR` file for your camera from Canon's support website yourself, then pick it in **Camera > Firmware update**.

**What ships in 0.1: Canon's own firmware transfer.** Canon Camera Connect sends firmware to the camera with a Canon PTP operation, `0x911F` (`UpdateFirmware`). Its PTP library (`CPtpInterface::DS_UpdateFirmware` in `libEDSDKCore.so`) shows the whole sequence: the file goes in 2 MiB pieces, each one an `0x911F` operation with two parameters (the file's total size and the piece's offset) and a data phase made of a 32-byte field with the file name, then up to 2 MiB − 32 bytes of the file. After the last piece Canon's app only says "Follow the instructions on the camera screen to start the firmware update": the camera, not the phone, checks the file and installs it. libgphoto2 knows the same operation code (`PTP_OC_CANON_EOS_UpdateFirmware 0x911F`). Because the sequence is clear in Canon's code and matches libgphoto2, ShutterCtrl uses it, exactly as Canon's app does.

**Fallback: copy to the card.** If a camera doesn't list `0x911F` in its supported operations but does list the standard `SendObjectInfo` (`0x100C`) and `SendObject` (`0x100D`), ShutterCtrl copies the `.FIR` file to the root of the card instead, and you run **Firmware update** from the camera's own menu. If a camera offers neither, ShutterCtrl says so and you copy the file with a card reader.

**Safety rules, checked before the Send button works:**

- The file must be a `.FIR` file of a plausible size, with Canon's original short file name.
- **Full battery:** the camera's battery level (property `0xD111`) must read full. If the camera doesn't report it, the transfer is refused.
- **Model match:** the model id in the `.FIR` header (its first 4 bytes) must equal the model id the camera reports (property `0xD116`). If they differ, the transfer is refused. If the camera doesn't report a model id, you must confirm yourself that the file is for your camera.
- **You confirm** in a dialog that says what happens.
- **Blocking progress screen:** while the file is sent, the screen stays on, Back does nothing and nothing else talks to the camera.
- **Nothing is installed by the app.** The camera checks the file (model and signature) and installs it only when you start the update on the camera.

## Protocol

PTP/IP on TCP port 15740: every packet is a length (u32, little-endian) and a type (u32), then the payload. ShutterCtrl implements Init Command Request/Ack/Fail (1, 2, 5), Init Event Request/Ack (3, 4), Operation Request/Response (6, 7), Event (8), Start Data, Data and End Data (9, 10, 12), Cancel (11) and Probe Request/Response (13, 14), with unit tests for every packet.

Each Canon code was checked in Canon Camera Connect's own PTP library (`libEDSDKCore.so`, the `CPtpInterface::DS_*` functions, with the opcode as a constant in each) and against libgphoto2's `ptp.h`. They agree on every code below.

| Code | What | Seen in Canon's app |
| --- | --- | --- |
| `0x9110` | SetDevicePropValueEx | yes (`DS_SetDevicePropValueEx`) |
| `0x9114` | SetRemoteMode | yes (`DS_SetRemoteMode`) |
| `0x9115` | SetEventMode | yes (`DS_SetEventMode`) |
| `0x9116` | GetEvent (polled) | yes (`DS_GetEvent`) |
| `0x9128` | RemoteReleaseOn (2 parameters) | yes (`DS_RemoteReleaseOn`) |
| `0x9129` | RemoteReleaseOff (1 parameter) | yes (`DS_RemoteReleaseOff`) |
| `0x9153` | GetViewFinderData (3 parameters; response `0xA102` = not ready) | yes (`DS_GetViewFinderData`) |
| `0x911F` | UpdateFirmware | yes (`DS_UpdateFirmware`) |
| `0x9107` | Canon GetPartialObject (download fallback) | yes (`DS_GetPartialObject`) |
| `0xD101`, `0xD102`, `0xD103`, `0xD104` | Av, Tv, ISO, exposure compensation | yes (`CPtpCamera::SetAv`, `SetTv`, `SetISOSpeed`, `SetExposureCompensation`) |
| `0xD1B0` | EVF output device (live view on) | yes (`CPtpCamera::SetEvf_OutputDevice`) |
| `0xD111` | Battery level | yes (`CPtpCamera::TranslateBatteryLevel`) |

**Not confirmed in Canon's code, from libgphoto2 and the PTP standard only:**

- The standard operations `GetDeviceInfo 0x1001`, `OpenSession 0x1002`, `CloseSession 0x1003`, `GetStorageIDs 0x1004`, `GetObjectHandles 0x1007`, `GetObjectInfo 0x1008`, `GetThumb 0x100A`, `SendObjectInfo 0x100C`, `SendObject 0x100D` and `GetPartialObject 0x101B` (standard PTP; Canon's app uses its own variants `0x9101`, `0x9103`, `0x9104`, `0x910A` instead, and `0x1009 GetObject` as a fallback, which is in its code).
- The **button values** for RemoteReleaseOn/Off: 1 = half press, 2 = full press, second parameter 0 = with autofocus. Canon's code passes values it gets from elsewhere, so these come from libgphoto2.
- The **value encodings**: Canon's Av/Tv/ISO/exposure codes, the battery values (0 low, 1 half, 2 full, 4 three quarters, 5 a quarter) and the GetEvent record layout (`0xC189` property changed, `0xC18A` allowed values, `0xC181`/`0xC1A7` new picture) follow libgphoto2.
- **`0xD116` as the model id**, and the `.FIR` header's first 4 bytes as the model id.
- The GetViewFinderData parameters `0x00200000, 0, 0` (Canon's code uses 3 parameters; the values follow libgphoto2).
- The SSDP search target `urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1` (from other open-source tools). Canon's UPnP library also names `CameraConnectedMobileService`, which ShutterCtrl searches for too.

## Requirements

- A Canon camera with Wi-Fi that offers remote control from a computer (EOS Utility) or a smartphone over Wi-Fi. Many EOS and PowerShot cameras from the last decade do. Some newer models only start Wi-Fi for a smartphone after Bluetooth pairing; those need the camera's computer (EOS Utility) Wi-Fi mode with ShutterCtrl 0.1.
- Android 15 or newer, on a 64-bit ARM phone
- Works on any Android ROM as a normal app. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/ShutterCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `ShutterCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds ShutterCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/ShutterCtrl` in Obtainium by hand.

## Getting started

1. **On the camera,** turn on Wi-Fi and start a connection for remote control from a computer (EOS Utility) or a smartphone. Menus differ by model.
2. **On the phone,** join the camera's own Wi-Fi network in Android's Wi-Fi settings (choose to stay connected when Android says it has no internet), or put the camera on your home Wi-Fi.
3. **In ShutterCtrl,** tap **Search this Wi-Fi**, **Use this Wi-Fi's access point**, or type the camera's IP address and tap **Connect**. The first time, accept ShutterCtrl on the camera's screen.

- **Camera tab:** battery, Av/Tv/ISO/exposure, Focus and Shoot, self-timer, live view, the intervalometer, firmware and Disconnect.
- **Card tab:** the camera's pictures. Tap one to download it or open it in LumenCtrl.
- **Settings** (profile picture): self-timer, keep the screen on during live view, reset the pairing identity, and privacy.

## Is it safe to install?

- **Open source.** All of ShutterCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks that the app has exactly the permissions listed below and no Google libraries.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over ShutterCtrl. The SHA-256 fingerprint of ShutterCtrl's signing certificate is:

  ```
  15:BF:24:A2:CF:A9:B8:2D:BE:F2:A5:57:16:C2:4C:16:6D:71:47:23:61:61:58:28:D4:18:6A:2D:B7:D6:69:90
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum ShutterCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify ShutterCtrl-vX.Y.Z.apk -R VoidedKN0X/ShutterCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.** ShutterCtrl has **no location permission**, can't scan or change Wi-Fi networks and has no Bluetooth access.

| Permission | What ShutterCtrl uses it for |
| --- | --- |
| Internet (local network only) | Talking to the camera (PTP/IP) and searching for it (SSDP). The app refuses any address that isn't on your local network. |
| View network connections | Finding the phone's Wi-Fi, so camera traffic goes over the camera's own Wi-Fi even though it has no internet. |
| Allow Wi-Fi multicast reception | Hearing cameras answer a search. Held only while you tap Search. |
| Foreground service, and foreground service (connected device) | The intervalometer keeps shooting with the screen off. Only while a series runs. |
| Notifications | The "Intervalometer running" notification with Stop. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it. |
| App-internal broadcasts (`com.vknxworks.shutterctrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only ShutterCtrl itself can send to its own internal receivers. |

ShutterCtrl doesn't ask for "Wi-Fi state" or "change network connectivity": it doesn't need to read Wi-Fi names or switch networks, only to send its own sockets over the Wi-Fi you joined.

See [Privacy](#privacy) for what happens to your data.

## App lock

Turn the app lock on under **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset ShutterCtrl's settings and turn the lock off. Your camera will ask to pair this phone again. Photos you downloaded aren't touched.
- A running intervalometer series or firmware transfer keeps going when the app locks.

## Privacy

**Telemetry-free.** ShutterCtrl has no analytics, crash reporting, usage stats or tracking of any kind.

- **Local network only.** ShutterCtrl opens sockets only to private and link-local addresses (10.x, 172.16–31.x, 192.168.x, 169.254.x, and IPv6 fc00::/7 and fe80::/10). Anything else, including names that would need a DNS lookup, is refused before a connection is made. It never contacts Canon, Google or anyone else on the internet.
- **Cleartext:** the camera protocols (PTP/IP, and reading a camera's UPnP description during a search) are plain sockets to those local addresses. Android's network security config turns cleartext HTTP off for everything else.
- **On the camera's own Wi-Fi,** ShutterCtrl binds its sockets to that Wi-Fi network, so the traffic goes to the camera and never out over mobile data.
- **Pairing identity:** the camera remembers this phone by a random 16-byte identity made by ShutterCtrl, and the name "ShutterCtrl". No phone name, account or serial number is sent. You can reset the identity in Settings.
- ShutterCtrl doesn't read or keep the camera's serial number.
- **No location:** ShutterCtrl has no location permission and doesn't tag photos.
- Downloads go to **Pictures/ShutterCtrl** in your library (RAW types the phone's media library doesn't know go to **Download/ShutterCtrl**). ShutterCtrl keeps no copies of its own; thumbnails are only held in memory while connected.
- Firmware files are read from where you pick them and sent to the camera; ShutterCtrl doesn't copy them.
- Settings are in ShutterCtrl's private storage. Android backups don't include them, and ShutterCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How ShutterCtrl is made

ShutterCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

To work with Canon cameras, the protocol details were learned by examining Canon Camera Connect for interoperability with my own camera. No Canon code or assets are included; ShutterCtrl is its own implementation.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/ShutterCtrl.git
   cd ShutterCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`. `./gradlew testDebugUnitTest` runs the protocol tests.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

ShutterCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/ShutterCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

CTRL_SERIES_TABLE

## License

ShutterCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Canon, EOS and PowerShot are trademarks of Canon Inc. ShutterCtrl is not made by or affiliated with Canon.

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
