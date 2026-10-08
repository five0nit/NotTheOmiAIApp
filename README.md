# NotTheOmiAIApp

Independent offline Omi companion for Android. **Public APK downloads only; application source remains private.** Not affiliated with Omi or Based Hardware.

## Download 0.3.1 (preview)

- **[ARM64 APK — most modern Android phones](https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.3.1/NotTheOmiAIApp-0.3.1-arm64-v8a.apk)**
- [Universal APK — if unsure](https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.3.1/NotTheOmiAIApp-0.3.1-universal.apk)
- [All builds, checksums and release notes](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.3.1)

Android 8.0+ (API 26). Package: `app.nottheomi.ai`. Version: **0.3.1 / code 4**.

GitHub's automatic “Source code” archives contain only this downloads repository: documentation, checksums, manifest and third-party license texts, not application source. Install an **APK**, not a ZIP/TAR archive.

## Install and connect

1. Download the appropriate APK on your Android phone. Allow your browser/file manager to install this app if Android requests it.
2. Existing NotTheOmiAIApp users: install over the current app. **Do not uninstall or clear its data** if you want to retain recordings. Uninstalling loses the local encryption key and library.
3. Disconnect other Omi apps, wake/charge your Omi and enable Bluetooth.
4. Open NotTheOmiAIApp and tap **Connect Omi**. Grant Nearby devices and notifications, then select your device. Android 8–11 additionally needs Location permission and system Location enabled for Bluetooth discovery; this app does not collect location.
5. Recording starts automatically once Omi audio arrives. Follow live transcription or open saved sessions. Use **Disconnect & save** or the notification Stop control to finish.

The phone microphone is an explicit fallback, not required for Omi audio. Original Omi Private is a separate app and is not replaced.

## What it does

- Omi Bluetooth LE audio → phone-local English transcription using bundled Vosk.
- Encrypted phone-local audio and transcript history; playback and explicit export.
- No Omi account, PC sync, cloud transcription, model download, or INTERNET permission.
- Capability-dependent battery, brightness and button controls. Brightness 0 requests minimum, not guaranteed darkness; long-press/power behavior stays firmware-owned.

Stock Omi/DevKit Opus codec IDs 20/21 are supported. Not every wearable/firmware has compatible optional controls.

## 0.3.1 and current limits

Batches contiguous audio into up to 200 ms encrypted saves instead of a durable transaction for every 10–20 ms packet. Queue limits, ordered controls and archival-before-transcription are retained.

This is a **preview**, not a physical-endurance certification. Retained verification includes 63 Android 13 emulator tests and 23 controlled capture scenarios. Physical Omi sustained capture and screen-off endurance after this update remain unverified. BLE loss, sequence gaps or bounded-queue overflow can still stop a session with the already committed audio retained; reconnect for a new session.

English only; recognition can be wrong. Process death can lose an uncommitted audio tail. 2 GiB audio quota and 128 MiB free-space reserve. Record with participants' permission. User-requested exports are plaintext; choose their destinations carefully.

## Integrity and third-party licenses

Compare downloads with [SHA256SUMS.txt](SHA256SUMS.txt). Signing-certificate SHA-256:

```text
b1ae66aff1dc32173dd2b99ec17710f949ff03d1b33ebcc1d49de87c7fba1e90
```

See [release-manifest.json](release-manifest.json), [third-party notices](THIRD_PARTY_NOTICES.md) and [license texts](licenses/). These notices cover their named components, not an open-source grant for the application.
