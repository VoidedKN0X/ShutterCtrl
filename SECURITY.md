# Security policy

Thank you for helping keep ShutterCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/ShutterCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of ShutterCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

ShutterCtrl has no server and talks only to your camera on the local network. So these count:

- Any way for ShutterCtrl to connect to an address outside the local network, or to send photos, settings or other data anywhere but your camera
- A camera, or another device on the network, being able to make ShutterCtrl write files outside Pictures/ShutterCtrl or Download/ShutterCtrl, crash in a harmful way, or run code
- The firmware transfer starting without your confirmation, or with a file the safety checks should have refused
- The intervalometer service running when no series is active
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
15:BF:24:A2:CF:A9:B8:2D:BE:F2:A5:57:16:C2:4C:16:6D:71:47:23:61:61:58:28:D4:18:6A:2D:B7:D6:69:90
```

The key never meets the build: the job that runs Gradle (and with it every plugin and library) has no secrets and produces an unsigned APK. A separate job that checks out nothing and runs no build code signs that APK, then refuses to pass it on unless it is signed by exactly this certificate and its contents are unchanged.
