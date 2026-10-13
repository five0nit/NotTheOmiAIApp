# Public source verification — 0.4.12 / versionCode 19

## Publication boundary

This checkpoint advances public source from 0.4.7 to the current 0.4.12 controls candidate, preserving the existing public history and guide. It includes the intervening storage, playback, notification and recording fixes rather than layering new controls onto the old public source.

**230 published source/test inputs** match the retained verified candidate byte-for-byte. [Source provenance](public-source-provenance-0.4.12.json) records each path and SHA-256. Existing portable SDK instructions and Windows-temp CLI handling are preserved as public-only variants; they are not application changes. Documentation and verification summaries are updated separately.

Private Git history, phone identifiers, recordings/transcripts, runtime diagnostics, signing material, machine configuration, generated models and native binaries are not copied into Git. Use the pinned preparation scripts and dependency manifest to build from source. Historical verification reports and root binary manifests/checksums remain historical; their presence does not describe the current source version.

## Controls included

- Independent single/double/triple/four/five actions. Four/five begin unassigned. Existing triple-only 650 ms grouping remains when higher counts are unassigned; assigning four/five extends the fixed window to 950/1250 ms and shorter sequences must wait.
- Manually selected Everyday, Meeting and Music profiles with independent actions and shortcut targets.
- Smart action: bookmark while recording; media play/pause in controls-only standby.
- Bounded pause-music → start-recording action; does not toggle off an active recording. No arbitrary macro editor or automatic profile switching.
- Flashlight toggle with explicit optional Camera permission. No camera preview or new networking permission.
- Notification All apps / Selected apps only filtering. An empty selected-app list stays silent. Short, Double, Triple and Long default/per-app wearable vibration patterns; existing opt-in behavior retained.

These are Android-side controls, not a wearable firmware update.

## Fresh checks on the public checkpoint

[Fresh host checks](fresh-host-checks-0.4.12.json) record nine successful command entry points. They cover BLE and gesture/haptic regressions, capture/controls lifecycle, service/BLE composition, real model installers, refinement, job lifecycle, Whisper Java wrappers, calendar dates and instrumentation-output parsing.

Selected bounded results: 54 gesture scenarios; 641 haptic/BLE scenarios; 72 Omi capture scenarios; 43 composition scenarios. These are separate host-test scopes, not a combined physical-acceptance total. The model-installer check used the existing pinned regular model files via explicit command arguments; no model files were added to Git.

## Retained candidate evidence — not freshly rerun Android tests

- Offline Android debug/release/test builds and lint passed; lint warnings were retained.
- 125 Android instrumentation methods passed on the exact signed x86_64 release installed on an isolated API 34 emulator, with installed APK hash readback.
- An authorized physical ARM64 USB update from 0.4.11 / build 18 to 0.4.12 / build 19 passed installed-byte, signer-continuity, app-data identity and permission checks. App launch succeeded; recording was left stopped. This is installation proof, not a claim that every stored recording was decrypted and compared.
- Exact artifact hashes are in the source provenance. Original immutable build, test and phone receipts remain private.

No fresh Android build, phone install, recording, BLE connection or firmware write is performed for this Git update.

## Remaining physical gates

Real wearable four/five-press cadence, each motor pattern, profile/context behavior during real capture, and flashlight permission/busy/background/locked-screen behavior remain pending. Host doubles and emulator tests do not prove those device outcomes.

## Source versus downloadable APK

Public source is **0.4.12 / build 19**. The newest downloadable GitHub APK remains **v0.4.11 / build 18**. This source/documentation update does not create a new tag or binary release. Download links deliberately continue to point to the existing v0.4.11 preview.
