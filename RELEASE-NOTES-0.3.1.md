# NotTheOmiAIApp 0.3.1 — preview

Public APK distribution; application source remains private.

## Change

- Bounded 200 ms encrypted audio batches reduce per-packet storage pressure behind the reported early capture stop.
- Preserve byte ordering, queue limits, archive-before-transcription, bookmark/Stop/gap boundaries and partial-tail flushing.
- Includes Connect-first Home, automatic Omi recording, live transcription and local saved history.

## Installation

Android 8.0+ / API26; package `app.nottheomi.ai`, version code 4. Use `arm64-v8a` for most modern Android phones, `armeabi-v7a` for older 32-bit ARM devices, `x86_64` for compatible Intel devices/emulators, or `universal` if unsure. Install over the existing app; do not uninstall to upgrade.

## Verification and remaining gate

Existing release evidence: 63 Android 13 emulator tests; 23 controlled capture scenarios; release build and lint passed (0 errors, 25 warnings). These are retained test results, not a newly executed full suite. Publication preflight reverified all four APK hashes, signing certificates, package/version, non-debuggable status and absence of INTERNET permission.

Physical Omi sustained capture and screen-off endurance after this fix are still unverified. Packet gaps/BLE loss can still end a session; earlier committed audio remains saved. Synthetic timing tests are not wearable acceptance.

Checksums, public signing identity and exact private-source commit are recorded in the release manifest. The public tag identifies this downloads-only repository, not the private source tree. No GitHub Actions build is configured here; APKs are the existing locally built and tested artifacts.
