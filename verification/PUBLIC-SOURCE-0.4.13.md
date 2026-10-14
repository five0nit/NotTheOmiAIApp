# Public source and APK verification — 0.4.13 / build 20

## Publication boundary

This checkpoint advances the public application source and signed Android preview download to **0.4.13 / versionCode 20**. It preserves the existing public history and includes the controls previously published as source-only in 0.4.12.

[Source provenance](public-source-provenance-0.4.13.json) binds **254 source/test inputs** byte-for-byte to the retained verified candidate. The two existing portable public variants (`tests/ui/README.md` and `tests/whisper-device/run_probe.py`) remain unchanged. Public documentation and its regression tests are maintained separately. Pinned generated models/native libraries are excluded from Git, with hashes recorded in the provenance; preparation scripts and dependency declarations remain available.

Private Git history, phone identifiers, recordings/transcripts, runtime diagnostics, machine configuration and signing keys are not published. The signed release APK is preserved exactly, not rebuilt or re-signed for publication. Its SHA-256 is:

```text
0a391f6490c650ce9f20e2b112015e8737a910199656bc5b7b150b466693fd76
```

Automatic source archives for v0.4.13 contain the corresponding public application checkpoint and build instructions, not an installable APK or signing keys. Exact source hashes establish provenance, not independent reproducible-build proof. Older release tags and root binary manifests/checksums remain historical.

## Changes included

- Screenshots allowed throughout the app. Visible content may appear in captures; encrypted local storage and disabled backups remain unchanged.
- **All notifications** is the default event filter when no preference exists; **Alerting only** retains the restrictive alternative. All mode includes silent, grouped, ongoing/foreground and DND-delivered posts, and does not exclude an event solely because of `ONLY_ALERT_ONCE`.
- Event filtering is separate from **All apps / Selected apps only**. Notification vibrations remain opt-in and off by default for new installs. Existing opt-in, selected apps, per-app/default patterns, own-app exclusions, duplicate/stale/reconnect fencing and the burst limit remain.
- No notification-message text reads, additional BLE owner, disconnected backlog or phone-motor substitution. Retained safeguards mean not every posted event produces a pulse.
- Preference-generation publication is synchronized with policy setters, with a latch-controlled regression.
- Includes 0.4.12 control profiles, smart actions, explicit-permission flashlight, four/five-press mappings and notification app/pattern choices.

## Fresh publication checks

[Fresh host checks](fresh-host-checks-0.4.13.json) record successful BLE/gesture/haptic, capture-service and service/BLE composition entry points on this exact public checkout. These use controlled doubles, not physical hardware. APK signature/metadata, model and native dependency hashes, retained install-byte correspondence and extracted-content secret scans were checked again before release. Large model payloads were verified by pinned upstream hashes.

The existing third-party distribution bundle was carried forward only after byte-matching all three native libraries, both models and six packaged licence texts to the reviewed 0.4.11 release. All 17 accompanying licence/attribution texts were preserved, with new artifact provenance/checksums. This is a bounded distribution check, not a new exhaustive legal review.

## Retained candidate and installation evidence

- Offline debug/release/test builds and lint passed before publication.
- **112 targeted signed-emulator tests** passed on the final candidate. A separate **11-test ad-hoc rerun** passed; these counts overlap and are **not additive**, nor a claim that the complete instrumentation suite was run.
- Actual Android system screenshots were captured and visually inspected on the emulator.
- An authorized ARM64 USB update from 0.4.12 / build 19 to 0.4.13 / build 20 passed exact installed-APK hash, signer continuity, UID/data-directory identity, permission and notification-access readbacks. Launch succeeded; recording was not started and the prior idle BLE connection was left stopped.
- Data-directory identity checks do not establish that every stored recording was decrypted and compared.

No new Android build, Android test execution, phone install, BLE connection, recording or firmware change is performed merely to publish the release.

## Remaining physical acceptance

Real WhatsApp → Omi vibration, four/five-press cadence, each motor pattern, profile/context behavior during capture, flashlight background/locked-screen behavior and long-duration wearable reliability remain unverified. Emulator and host results do not establish those outcomes.

## Download

[Public v0.4.13 Android preview](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.13): Android 8+ (API 26), ARM64 (`arm64-v8a`), no iOS version. Release assets include the APK, manifest, checksums and complete third-party notices/licences. Update a same-signer installation in place; do not uninstall or clear data if retaining existing recordings.
