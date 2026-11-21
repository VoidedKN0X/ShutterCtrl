# Changelog

What changed in each ShutterCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ShutterCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.6.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Added

- **ShutterCtrl in French and German, following the phone's language.** Every screen, setting and message now also has a French translation (polite "vous", as Android itself uses) and a German one (informal "du", as modern Android apps use). Android picks French or German when your phone is set to that language; otherwise ShutterCtrl stays in English, or Dutch as before. ShutterCtrl now also tells Android which languages it has (English, Dutch, French, German), so you can choose one for ShutterCtrl alone in Android's per-app language setting. Canon's own names (Av, Tv, One Shot, AI Focus, Servo, EOS Utility, Camera Connect), units, folder names and the app name stay as they are.

## v0.5.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Added

- **Bluetooth remote (needs confirming on your device).** **Camera > Bluetooth remote** pairs the camera as a Bluetooth remote, the way Canon's BR-E1 wireless remote is paired (start pairing with a wireless remote in the camera's wireless menu, then tap **Pair a camera**), and then offers **Focus** (held until you let go) and **Shoot** without the camera's Wi-Fi. The camera is remembered (**Connect to …**, **Forget this camera**). Only what two open-source projects both send is used: furble's `CanonEOSRemote` (EOS M6, RP, R6 Mark II) and eos-remote-web (EOS 250D): the pairing write `0x03` + name, and the button bytes `0x4C`, `0x8C` and `0x0C`. It hasn't been tried with an EOS R50, so it's marked "Needs confirming on your device". **Not included:** waking the camera and switching its Wi-Fi on over Bluetooth: no public source or open-source project confirms those commands, so ShutterCtrl doesn't send them.
- **Live-view aids.** Live view has three new chips: a rule-of-thirds **grid**, a brightness **histogram** (with an accent edge when part of the picture is pure black or white) and **magnify** (2× or 4×; tap the picture to choose the point to magnify around). All on the phone, from the live-view picture; nothing extra is sent to the camera. Grid and histogram are remembered.
- **Movie start and stop.** In live view, **Movie > Start recording / Stop recording** records a movie to the camera's card, on cameras that report the movie record setting (libgphoto2's `movierecordtarget`, property 0xD1B8: 4 = card, 0 = stop). Set the camera to movie mode first. Shown only when the camera reports the setting.
- **More remote settings.** A second row of tiles under Av, Tv, ISO and EV: **white balance (0xD109), drive mode (0xD106), AF mode (0xD108) and picture style (0xD110)**, chosen from the values the camera allows right now, with libgphoto2's names for the values. A tile appears only when the camera reports that setting.
- **Bulb timer.** When the camera is on Bulb, **Camera > Bulb timer** keeps the shutter open for 2 seconds to 30 minutes with a countdown and **Stop**. It uses Canon's BulbStart/BulbEnd (0x9125/0x9126, libgphoto2) when the camera lists them, otherwise it holds the remote release fully down and lets go (libgphoto2's "Press Full" and "Release Full"). The shutter is always closed again, also when you stop early or the connection ends. The other shutter buttons wait while a bulb exposure runs.
- **Card filters, batch download and auto-download.** The Card tab has filters (All, JPEG, RAW, Today). Long-press a thumbnail to select it (tap more to add them, or **Select all shown**), then download the selection or delete it. Downloads now go through one queue, one file at a time, with a progress line ("Downloading · 3 of 12"). **Auto: …** chooses whether new photos download by themselves while ShutterCtrl is connected: off (the default), JPEG, RAW or all photos.
- **Delete from the card.** One file (**Delete from the card** in its sheet) or a selection, after a confirmation that says copies on the phone stay. Uses the standard PTP DeleteObject (0x100B), or Canon's own (0x9105, libgphoto2) when the camera lists only that. Shown only when the camera lists one of them.
- **Resume interrupted downloads.** When a download stops part-way (the Wi-Fi or cable dropped), what arrived is kept, hidden from your library, and **Continue download (45 %)** picks up where it stopped, also after reconnecting. It works with cameras that can send parts of a file, and while ShutterCtrl stays open; an unfinished file Android never sees finished is removed by Android after about a week.

- **Settings export and import.** **Settings > Settings file** saves your shooting, intervalometer, bracketing, focus stacking, bulb, live view and auto-download settings and the camera's last address to a JSON file you choose, and imports one again after listing exactly which settings it changes. Never in the file: this phone's PTP/IP pairing identity, the Bluetooth camera, geotagging (it would turn on location), the app lock and the privacy settings. An imported address is used only if it's on a local network.

- **Dutch.** ShutterCtrl now has a complete Dutch (Belgian Dutch, informal "je") translation of every screen, setting and message, including everything above. Android picks it when your phone's language is Dutch; otherwise ShutterCtrl stays in English. There's no language setting in the app. Camera values and technical names (Av, Tv, ISO, JPEG, RAW, USB, AUTO, BULB…) stay as they are, and so do the camera's own error codes shown after a failure.

### Permissions

- **Nearby devices (Bluetooth scan and connect), new and optional.** Only for the Bluetooth remote; asked for when you first pair or connect. The scan is declared as never used to work out where you are, so no location is involved. The build check's list of allowed permissions now includes these two.

## v0.4.1 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there. It has also never been tried with a real Canon camera.

### Security

- **The signing key is no longer available to the build.** Nothing in the app changed. Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and its contents are identical to what the build job made. Releases are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. The release job itself has not run with the new workflow yet; the next version will be the first.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml` and SECURITY.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the weekly code scan start from a clean Gradle home, and the plugin portal is no longer a plugin source at all (`settings.gradle.kts`): everything the build needs is on Maven Central and Google's Maven. Dependency verification itself is unchanged and as strict as before. Builds take a little longer.

### Changed

- **Newer build tools; nothing changes in how ShutterCtrl looks or works.** ShutterCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.13.2 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin stays 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2026.06.01 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries were already on the versions the newest Ctrl apps use and are unchanged. No library was added, and the app is still Google-free (CI checks it).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions ShutterCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4; the release APK is about 4% smaller (3.51 MB → 3.38 MB). ShutterCtrl's own keep rules are unchanged. The new R8 shrinks differently from the old one (stricter keep rules, resources removed together with unused code, classes repackaged), so the release build needs a run on a phone before the next version is published.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

## v0.4.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published. It has also never been tried with a real Canon camera.

### Privacy

- **The geotagging location notes are now encrypted on the phone.** With **Add the phone's location** on, ShutterCtrl keeps the phone's positions of the last 24 hours in a private file (`location-log.csv`). That file is now sealed with AES-256-GCM. Its key (32 random bytes, in `data.key` in ShutterCtrl's private storage) is itself locked with a key in the phone's secure hardware (Android Keystore, StrongBox when the phone has it) that can't be copied off the phone. The key is only made the first time notes are saved, so nothing changes if you never turned geotagging on. This protects the notes in files copied off the phone; it doesn't depend on the app lock and doesn't protect against someone who can use the unlocked phone or a compromised running app.
- **Existing notes are converted the first time they're read** (when ShutterCtrl comes to the screen with geotagging on): the old file is read as before, an encrypted copy is written next to it, read back and compared, and only then put in its place in one step. If any of that fails, the old file stays in use and it's tried again at the next start. ShutterCtrl reads both kinds of file from now on.
- **If the key can't be made or used** (the secure hardware fails), the notes are kept as an ordinary private file, exactly as before. **If encrypted notes can't be opened** (the key is gone, or the file is damaged), they're deleted and new notes start: at most the last 24 hours of positions are lost, so photos taken before that moment and downloaded later aren't geotagged. There's no message for this, because the notes are a short-lived working file. An existing key file is never replaced.
- **An older ShutterCtrl can't read the encrypted notes.** After going back to an older version, it would see no usable notes and start over (again at most 24 hours of positions). There's nothing to export: positions already written into downloaded photos are in those photos.
- **Deliberately not encrypted:** the settings, including the last camera address (a local-network address) and this phone's pairing identity, because the connection code reads them and a change there could not be tried on a real camera; the app lock's own data, which is already protected; downloaded photos, which are in your photo library and not in ShutterCtrl's storage. Turning geotagging off still deletes the notes.

### Changed

- **The saved location notes are read in the background** instead of on the main thread when ShutterCtrl comes to the screen. They're available a moment later than before, so a photo downloaded in the first instant after opening the app could miss a position from an earlier session.

## v0.3.1 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It has also never been tried with a real Canon camera.

### Fixed

- **Card thumbnails that stayed empty after a fast scroll.** When a photo scrolled off the screen before its thumbnail had been fetched, ShutterCtrl wrongly noted it as "has no thumbnail" and never asked the camera for it again until you reconnected. It's now simply fetched the next time the photo is on screen. A file the camera really has no thumbnail for is still asked for only once.

### Smoother

- **Live view no longer makes a new picture in memory for every frame.** Frames (about 15 a second) are now decoded into three bitmaps used in turn, which avoids the short stutters Android's memory clean-up could cause. One frame is decoded at a time; a frame that arrives while the previous one is still being decoded is skipped, and a frame that can't be decoded leaves the last picture on screen, as before.

## v0.3.0 (2026-11-13)

### New

- **Exposure bracketing** on the Camera tab: take 3, 5, 7 or 9 shots ⅓, ⅔, 1 or 2 stops apart around the current exposure, for HDR or tricky light. ShutterCtrl uses exposure compensation, or the shutter speed when the camera is in M mode, and only values the camera says it accepts right now; it shows the steps it will use before you start. Autofocus runs on the first shot only, and the camera's starting value is put back afterwards. The section only appears when the camera reports those values.
- **Focus stacking** in live view: ShutterCtrl takes a shot, moves the focus a few steps with Canon's lens-drive command, takes the next shot, and so on, without autofocus, for 5 to 50 shots. Choose the step size, how many steps between shots and the direction; **Near** and **Far** move the focus to your starting point first. Merge the photos in a focus-stacking program afterwards. The section only appears on cameras that offer the lens-drive command. Not tested on a real camera yet: please report your model.
- **Geotag downloads** (Settings > Downloads, off until you turn it on): downloaded JPEG photos get the phone's position at the time they were taken. While the option is on and ShutterCtrl is open, the phone notes its position (kept on the phone for 24 hours, never sent anywhere); each photo gets the position nearest to its capture time, within 10 minutes. Photos that already have a position from the camera keep it, and RAW files are left as they are. Turning the option off deletes the notes.

### Privacy

- **Location permission, only if you turn on geotagging.** ShutterCtrl now lists precise and approximate location; Android asks only when you turn on Geotag downloads, and with the option off nothing reads your location. Allowing only approximate location works too, less precisely.

## v0.2.1 (2026-11-13)

A security and maintenance release. Nothing changes in how you use ShutterCtrl.

### Security

- **Stricter checks on what a camera sends.** A camera (or a device on the network pretending to be one) that sends broken or oversized answers now gets a clean "connection lost" instead of possibly confusing ShutterCtrl or using up the phone's memory. Answers that are kept in memory (camera details, the card list, thumbnails, live view frames) are limited to 32 MB; photo and video downloads still stream straight to your library at any size.
- **File names from the camera are cleaned before saving.** A name with folder parts or odd characters can't make a download land anywhere but Pictures/ShutterCtrl (or Movies or Download/ShutterCtrl).
- The build tool download is now checked against its published checksum.

### Performance

- Updated Jetpack Compose, AndroidX and Kotlin libraries to their current versions.
- Release builds drop debug logging.

## v0.2.0 (2026-11-12)

ShutterCtrl now also works over a USB cable. Everything you can do over Wi-Fi works the same way on the cable. USB hasn't been tested with a real camera yet; please report your camera model and what works.

### New

- **Connect over USB:** plug the camera into the phone with a USB cable (USB-C to USB-C, or the camera's cable with an OTG adapter) and tap it under **Connect over USB** on the Camera tab. The first time, Android asks whether ShutterCtrl may use the camera. When you plug in a Canon camera, Android can also offer to open ShutterCtrl.
- **Everything works over the cable:** card browser and downloads to Pictures/ShutterCtrl, Focus and Shoot with the self-timer, live view with Av, Tv, ISO and exposure compensation, the intervalometer, and the firmware transfer with all its safety checks (full battery, model check, confirmation, blocking progress screen).
- **Pulling the cable** ends the session right away and stops a running intervalometer series. The Camera tab says the cable was disconnected; plug it in again and tap the camera to reconnect.

### Changed

- The Camera tab's connect screen now has two sections: **Connect over USB** and **Connect over Wi-Fi**.
- Messages that mentioned only Wi-Fi (live view not supported, firmware routes, the firmware warning) now cover both connections.
- No new permissions. Using a camera over USB needs only Android's per-camera "allow" dialog.

## v0.1.0 (2026-11-12)

The first release of ShutterCtrl: a private Wi-Fi remote for Canon cameras that replaces Canon Camera Connect, with no Canon account and no cloud. Not yet tested with a real camera; please report your camera model and what works.

### New

- **Connect over Wi-Fi** on the camera's own access point or your home Wi-Fi: search the network, use the access point's address, or type the camera's IP address. Traffic is bound to the Wi-Fi, so it reaches the camera even when Android prefers mobile data.
- **Card browser** with thumbnails from the camera.
- **Download JPEG and RAW** (CR2, CR3) to Pictures/ShutterCtrl, with progress.
- **Open in LumenCtrl** straight after downloading, when LumenCtrl is installed.
- **Remote shutter:** Focus (half press) and Shoot, with a 2 or 10 second self-timer.
- **Live view** with Av, Tv, ISO and exposure compensation, chosen from the values the camera allows.
- **Intervalometer:** N shots every T seconds, also with the screen off, with a notification and Stop.
- **Firmware transfer** of a .FIR file you downloaded from Canon, using Canon's own transfer (or a copy to the card on cameras without it), behind a full-battery check, a model check, a confirmation and a blocking progress screen. The camera installs it only when you start the update on the camera.
- **Privacy:** local network only (any other address is refused), no location permission, app lock, Hide in screenshots and Hide in recent apps.
