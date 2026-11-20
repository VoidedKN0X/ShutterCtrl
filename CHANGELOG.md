# Changelog

What changed in each ShutterCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ShutterCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
