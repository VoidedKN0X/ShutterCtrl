# ShutterCtrl

**A private remote for your Canon camera over Wi-Fi or a USB cable: card browser, downloads, remote shutter, live view, intervalometer and firmware transfer, with no Canon account and no cloud.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/ShutterCtrl?label=download)](https://github.com/VoidedKN0X/ShutterCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Local network only](https://img.shields.io/badge/internet-local%20network%20only-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

ShutterCtrl replaces Canon Camera Connect for the things you do on Wi-Fi, and does the same over a USB cable: browse the camera's card, download JPEG and RAW files, fire the shutter, watch live view and change exposure, run an intervalometer, and send a firmware file you downloaded from Canon. It talks to the camera directly with PTP, the standard picture transfer protocol (PTP/IP on the network, PTP over USB on the cable), and to nothing else: no Canon ID, no image.canon, no analytics.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/ShutterCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

> **Tested on: none yet.** ShutterCtrl 0.1 was written without a Canon camera at hand. The protocol code follows what Canon Camera Connect itself sends (see [Protocol](#protocol)), has unit tests, and was run against a simulated camera (connect, card, thumbnails, downloads, shutter, exposure, live view, intervalometer), but it hasn't talked to a real camera yet. The firmware transfer has only been unit-tested. **USB (new in 0.2) is untested on a real camera too:** the USB framing and a whole session (set-up, a download over many packets, events, a pulled cable) were run against a simulated camera in unit tests, and the connect screen was checked on a phone with no camera plugged in. If you try it, please [report](https://github.com/VoidedKN0X/ShutterCtrl/issues/new/choose) your camera model and what worked.

## Features

- **Connect over Wi-Fi:** on the camera's own Wi-Fi (no internet) or on your home Wi-Fi. Search the network (SSDP/UPnP), use the camera's access point address, or type its IP address
- **Connect over USB:** plug the camera into the phone with a cable and tap it. Everything below works the same over the cable (see [USB](#usb))
- **Card browser** with thumbnails from the camera, newest first, with **filters** (JPEG, RAW, taken today)
- **Batch download:** long-press to select files (or Select all shown) and download them in one go, one after another, with progress
- **Auto-download while shooting:** new JPEGs, RAWs or all new photos download by themselves while ShutterCtrl is connected (off until you choose)
- **Resume interrupted downloads:** when the connection drops part-way, what arrived is kept and the next try continues from there (while ShutterCtrl stays open; needs a camera that can send parts of a file)
- **Delete from the card**, one file or a selection, after you confirm. Copies on the phone stay
- **Download JPEG and RAW** (CR2, CR3) to **Pictures/ShutterCtrl**, with progress. The file only appears in your library once it's complete. RAW types the phone's media library doesn't know (on many phones CR3) go to **Download/ShutterCtrl** instead
- **Open in [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl):** download and edit a JPEG or RAW in one tap, if LumenCtrl is installed
- **Remote shutter:** Focus (half press, held until you release it) and Shoot, with a 2 or 10 second self-timer
- **Live view** from the camera, with **Av, Tv, ISO and exposure compensation** chosen from the values the camera allows in its current mode
- **Live-view aids:** a rule-of-thirds **grid**, a brightness **histogram** and **magnify** (2× or 4× around the point you tap). All worked out on the phone from the live-view picture; nothing extra is sent to the camera
- **More remote settings:** **white balance, drive mode, AF mode and picture style**, from the values the camera allows, shown when the camera reports them
- **Movie start and stop** from live view, on cameras that report the movie record setting (set the camera to movie mode first)
- **Bulb timer:** with the camera on Bulb, keep the shutter open for 2 seconds to 30 minutes, with a countdown and Stop
- **Bluetooth remote:** pair the camera once as a Bluetooth remote (like Canon's BR-E1) and use Focus and Shoot without turning on the camera's Wi-Fi. **Needs confirming on your device** (see [Bluetooth remote](#bluetooth-remote))
- **Intervalometer:** N shots every T seconds, also with the screen off. It runs in a foreground service only while a series is active, with a notification and Stop
- **Exposure bracketing:** 3, 5, 7 or 9 shots at ⅓, ⅔, 1 or 2 stops apart, around the current exposure, with exposure compensation (or with the shutter speed in M mode). Shown only when the camera reports values it accepts; the starting value is put back afterwards
- **Focus stacking** in live view: a shot, the focus moved a few steps with Canon's DriveLens, the next shot, and so on (5 to 50 shots, small, medium or large steps, towards far or near), without autofocus. Near and Far buttons set the starting point. Shown only on cameras that list DriveLens
- **Geotag downloads** (off until you turn it on): downloaded JPEG photos get the phone's position at the time they were taken, from a short location log kept on the phone while ShutterCtrl is open
- **Firmware transfer:** send a `.FIR` file you downloaded from Canon's website yourself, with safety checks (see [Firmware updates](#firmware-updates))
- **Settings export and import:** save your shooting, live view and download settings to a file and use them again later or on another phone. No pairing identity, Bluetooth camera, geotagging, app lock or privacy settings are in it
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **English and Dutch:** ShutterCtrl follows your phone's language (Dutch when your phone is set to Dutch, English otherwise); there's no language setting in the app
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono

### Not in 0.2

- **GPS tagging of RAW files:** Android can't write positions into CR2/CR3 files, so only JPEG photos are geotagged, and only for time ShutterCtrl was open.
- **Waking the camera and switching its Wi-Fi on over Bluetooth:** not included. No public source or open-source project confirms the commands, and ShutterCtrl never sends guessed ones. Turn on the camera's Wi-Fi from the camera's menu, or use a USB cable, for live view and the card. The Bluetooth remote (Focus and Shoot) is included.
- **Movies in the card browser:** the card browser lists photos; videos may show up but aren't the focus.
- Canon's cloud services (Canon ID, image.canon, streaming, error-log upload) are left out on purpose.

## Bluetooth remote

ShutterCtrl can be the camera's Bluetooth remote, the way Canon's BR-E1 wireless remote is: **Focus** (held until you let go) and **Shoot**, without Wi-Fi.

**Needs confirming on your device.** It follows two open-source projects that use this protocol: [furble](https://github.com/gkoh/furble) (`CanonEOSRemote`, used with the EOS M6, RP and R6 Mark II) and [eos-remote-web](https://github.com/RReverser/eos-remote-web) (EOS 250D), which builds on Ian Douglas Scott's reverse engineering of the EOS 800D. It hasn't been tried with an EOS R50 yet. Please [report](https://github.com/VoidedKN0X/ShutterCtrl/issues/new/choose) whether it works.

1. On the camera, open the wireless menu and choose pairing with a wireless remote (the BR-E1 pairing).
2. In ShutterCtrl, **Camera > Bluetooth remote > Pair a camera**. Android asks for the **Nearby devices** permission the first time. Tap your camera in the list; Android may ask you to confirm the pairing.
3. Next time, tap **Connect to** your camera. **Forget this camera** makes ShutterCtrl forget it (remove it from Android's Bluetooth settings and the camera's menu too if you like).

What is sent, and nothing else: service `00050000-0000-1000-0000-d8492fffa821`; on connecting, `0x03` followed by the name "ShutterCtrl" to `00050002-…`; for the buttons one byte to `00050003-…`: `0x4C` focus down, `0x8C` shutter down, `0x0C` let go. Other values those projects name (a 2-second delay mode, a movie mode, zoom buttons) aren't used, because neither project sends them.

## USB

ShutterCtrl can also control the camera over a USB cable, with PTP over USB (the USB "still image" class that cameras use with computers). Card, downloads, shutter, live view and exposure, the intervalometer and the firmware transfer all work the same as on Wi-Fi.

**Not tested with a real camera yet.** The USB code has unit tests against a simulated camera, but no Canon camera was plugged in while it was written. Please [report](https://github.com/VoidedKN0X/ShutterCtrl/issues/new/choose) your camera model and what worked.

**What you need:**

- A phone that can act as a **USB host (OTG)**. Most Android phones with USB-C can.
- A **cable from the camera to the phone:** USB-C to USB-C for cameras with a USB-C port, or the camera's own cable (Micro-B or Mini-B) with a USB-C OTG adapter. A charge-only cable won't work; it must carry data.
- **On the camera:** turn the camera's **Wi-Fi off** (many Canon cameras don't use the USB port for remote control while Wi-Fi or a wireless connection is on), and it helps to turn **Auto power off** off for long sessions and intervalometer series. Some models have a USB connection setting in the menu; if there is one, choose the computer / PTP connection, not a USB webcam or charging-only mode.

**How it works:**

1. Plug in the camera and turn it on. It shows up under **Camera > Connect over USB**. Android may also offer to open ShutterCtrl right away.
2. Tap it. The first time, Android asks whether ShutterCtrl may use the camera; allow it.
3. If another app (for example the Files app or a photo import app) opened the camera first, ShutterCtrl takes over its connection. If it still can't open the camera, close that app, unplug and plug in again.

**Pulling the cable** (or turning the camera off) ends the session straight away, stops a running intervalometer series and says so on the Camera tab. Don't unplug during a firmware transfer: nothing is installed until you start the update on the camera, but the transfer has to start over.

ShutterCtrl only lists Canon cameras (USB vendor 0x04A9 with a still-image interface). The plug-in notice is only a hint to look at the list again; ShutterCtrl never connects to anything by itself.

## Firmware updates

ShutterCtrl never downloads firmware and never contacts Canon. You download the `.FIR` file for your camera from Canon's support website yourself, then pick it in **Camera > Firmware update**.

The firmware transfer works the same over Wi-Fi and USB.

**Canon's own firmware transfer.** Canon Camera Connect sends firmware to the camera with a Canon PTP operation, `0x911F` (`UpdateFirmware`). Its PTP library (`CPtpInterface::DS_UpdateFirmware` in `libEDSDKCore.so`) shows the whole sequence: the file goes in 2 MiB pieces, each one an `0x911F` operation with two parameters (the file's total size and the piece's offset) and a data phase made of a 32-byte field with the file name, then up to 2 MiB − 32 bytes of the file. After the last piece Canon's app only says "Follow the instructions on the camera screen to start the firmware update": the camera, not the phone, checks the file and installs it. libgphoto2 knows the same operation code (`PTP_OC_CANON_EOS_UpdateFirmware 0x911F`). Because the sequence is clear in Canon's code and matches libgphoto2, ShutterCtrl uses it, exactly as Canon's app does.

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

PTP over USB (USB Still Image class 6, subclass 1, protocol 1): every container is a length (u32), a type (u16: 1 command, 2 data, 3 response, 4 event), a code (u16) and a transaction id (u32), then the parameters or data, on the bulk-out and bulk-in endpoints; events arrive on the interrupt endpoint. ShutterCtrl handles data split over many transfers, zero-length packets after a container that fills its last packet (sent and received), and the class requests Cancel (`0x64`, when saving a download fails), Get Device Status (`0x67`) and Device Reset (`0x66`, only if the camera reports it is still busy from an earlier app when ShutterCtrl connects). The operations and Canon codes below are the same on both connections.

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
- The **button values** for RemoteReleaseOn/Off: 1 = half press, 2 = full press, second parameter 0 = with autofocus, 1 = without (bracketing after the first shot, and focus stacking). Canon's code passes values it gets from elsewhere, so these come from libgphoto2.
- ** x9155 DriveLens** (focus stacking): one parameter,  x0001– x0003 = near (small, medium, large step),  x8001– x8003 = far; it works only while live view runs. From libgphoto2 (`PTP_OC_CANON_EOS_DriveLens`, its `manualfocusdrive` setting); ShutterCtrl offers focus stacking only when the camera lists  x9155 among its operations.
- The **value encodings**: Canon's Av/Tv/ISO/exposure codes, the battery values (0 low, 1 half, 2 full, 4 three quarters, 5 a quarter) and the GetEvent record layout (`0xC189` property changed, `0xC18A` allowed values, `0xC181`/`0xC1A7` new picture) follow libgphoto2.
- **`0xD116` as the model id**, and the `.FIR` header's first 4 bytes as the model id.
- The GetViewFinderData parameters `0x00200000, 0, 0` (Canon's code uses 3 parameters; the values follow libgphoto2).
- The SSDP search target `urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1` (from other open-source tools). Canon's UPnP library also names `CameraConnectedMobileService`, which ShutterCtrl searches for too.

## Requirements

- A Canon camera with Wi-Fi that offers remote control from a computer (EOS Utility) or a smartphone over Wi-Fi. Many EOS and PowerShot cameras from the last decade do. Some newer models only start Wi-Fi for a smartphone after Bluetooth pairing; those need the camera's computer (EOS Utility) Wi-Fi mode with ShutterCtrl.
- Or, over USB: a Canon camera that EOS Utility can control by cable, a data cable, and a phone that supports USB host (OTG). See [USB](#usb).
- Android 15 or newer, on a 64-bit ARM phone
- Works on any Android ROM as a normal app. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/ShutterCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `ShutterCtrl-v0.2.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds ShutterCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/ShutterCtrl` in Obtainium by hand.

## Getting started

1. **On the camera,** turn on Wi-Fi and start a connection for remote control from a computer (EOS Utility) or a smartphone. Menus differ by model.
2. **On the phone,** join the camera's own Wi-Fi network in Android's Wi-Fi settings (choose to stay connected when Android says it has no internet), or put the camera on your home Wi-Fi.
3. **In ShutterCtrl,** tap **Search this Wi-Fi**, **Use this Wi-Fi's access point**, or type the camera's IP address and tap **Connect**. The first time, accept ShutterCtrl on the camera's screen.

**With a cable instead:** turn the camera's Wi-Fi off, plug it into the phone and tap it under **Connect over USB** (see [USB](#usb)).

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

**Permissions.** ShutterCtrl can't scan or change Wi-Fi networks and has no Bluetooth access. It asks for location only if you turn on **Geotag downloads**.

| Permission | What ShutterCtrl uses it for |
| --- | --- |
| Internet (local network only) | Talking to the camera (PTP/IP) and searching for it (SSDP). The app refuses any address that isn't on your local network. |
| View network connections | Finding the phone's Wi-Fi, so camera traffic goes over the camera's own Wi-Fi even though it has no internet. |
| Allow Wi-Fi multicast reception | Hearing cameras answer a search. Held only while you tap Search. |
| Foreground service, and foreground service (connected device) | The intervalometer keeps shooting with the screen off. Only while a series runs. |
| Notifications | The "Intervalometer running" notification with Stop. |
| Nearby devices: Bluetooth scan and connect (optional) | Only for the **Bluetooth remote**; Android asks when you first pair or connect. Finding cameras that are waiting to pair with a remote, and connecting to the one you paired. The scan is declared as never used to work out where you are. |
| Precise and approximate location (optional) | Only for **Settings > Downloads > Add the phone's location**, which is off until you turn it on; Android asks then. While it's on and ShutterCtrl is open, the phone's position is noted (kept on the phone for 24 hours) and written into downloaded JPEGs. Turning it off deletes the notes. Allowing only approximate location works too, less precisely. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it. |
| App-internal broadcasts (`com.vknxworks.shutterctrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only ShutterCtrl itself can send to its own internal receivers. |

ShutterCtrl doesn't ask for "Wi-Fi state" or "change network connectivity": it doesn't need to read Wi-Fi names or switch networks, only to send its own sockets over the Wi-Fi you joined.

**USB needs no permission.** The cable connection uses Android's USB host feature (optional, so the app still installs on phones without it). Android asks you once per camera, in its own dialog, whether ShutterCtrl may use it.

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
- **Over USB,** ShutterCtrl talks only to the Canon camera you tap, and only after you allow it in Android's dialog.
- **On the camera's own Wi-Fi,** ShutterCtrl binds its sockets to that Wi-Fi network, so the traffic goes to the camera and never out over mobile data.
- **Pairing identity:** the camera remembers this phone by a random 16-byte identity made by ShutterCtrl, and the name "ShutterCtrl". No phone name, account or serial number is sent. You can reset the identity in Settings.
- ShutterCtrl doesn't read or keep the camera's serial number.
- **Location only if you ask for it:** with **Add the phone's location** off (the default), ShutterCtrl doesn't read your location and doesn't tag photos. When you turn it on, it notes the phone's position while it's open, keeps those notes on the phone for 24 hours (in its private storage, left out of backups) and writes the matching position into downloaded JPEGs. Nothing is sent anywhere. Turning the option off deletes the notes.
- **The location notes are encrypted on the phone** (AES-256-GCM). Their key is made on the phone and locked with a key in the phone's secure hardware (Android Keystore), so the notes can't be read from files copied off the phone. This doesn't depend on the app lock and doesn't protect against someone who can use the unlocked phone. If the phone can't make or use that key, the notes are kept as an ordinary private file, as in earlier versions. Positions written into your downloaded photos are part of those photos in your library and aren't encrypted by ShutterCtrl.
- Downloads go to **Pictures/ShutterCtrl** in your library (RAW types the phone's media library doesn't know go to **Download/ShutterCtrl**). ShutterCtrl keeps no copies of its own; thumbnails are only held in memory while connected.
- Firmware files are read from where you pick them and sent to the camera; ShutterCtrl doesn't copy them.
- Settings (including the last camera address and the pairing identity) are in ShutterCtrl's private storage, not encrypted. Android backups don't include them, and ShutterCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
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

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| **ShutterCtrl** (this app) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

ShutterCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Canon, EOS and PowerShot are trademarks of Canon Inc. ShutterCtrl is not made by or affiliated with Canon.

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
