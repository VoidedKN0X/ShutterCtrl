# Changelog

What changed in each ShutterCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/ShutterCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
