# Public source verification — 0.4.7 / versionCode 14

## Publication boundary

This curated checkpoint advances the existing public `main` branch from 0.4.4 without importing private Git history. It contains the 0.4.5 recovery changes, 0.4.6 calendar Library and 0.4.7 independently configured button functions and button-only standby.

Application/runtime sources are unchanged from the retained verified build. **188 published build inputs** match the immutable local build receipt byte-for-byte. Excluded generated inputs and all publication source hashes are listed in [public provenance](public-source-provenance-0.4.7.json). Public documentation is updated separately. Existing portable SDK/Windows-temp instructions remain intact; they are not application changes.

Private recordings/transcripts, device identifiers, production-phone dumps, signing material, models, generated native binaries, machine configuration and raw private receipts are excluded. The public branch retains its prior source/download history; the development repository's private history is not pushed.

## Retained acceptance evidence — not freshly executed Android tests

- Build, lint and signing: `PASS_BUILD_SIGN`; bound input hashes checked again during publication.
- Clean KVM debug emulator: **78 passing test methods** across settings, media, Home, Library UI/storage, playback, Omi integration and archive concurrency. Original instrumentation logs replayed through the published parser and current test-method inventory.
- Exact signed x86_64 release: **28 passing HybridRecordingsTest/HybridSpeechIntegrationTest methods**, with installed-byte readback.
- ARM64 in-place phone update: `PASS_INSTALL`. Retained installed-byte/signing-identity, UID, first-install, data-directory identity and permission comparisons passed. This proves the installation procedure, not byte-for-byte validation/decryption of every recording.

An earlier software-emulated x86_64 run faulted on a legacy `CVTPS2PD` operand at a buffer boundary. An isolated guard-page probe reproduced the software-only overread; KVM ran the unchanged APK successfully. Earlier failed emulator/fixture runs remain retained locally, not relabelled as passes. No native application workaround was made.

## Fresh publication checks

[Fresh host checks](fresh-host-checks-0.4.7.json) record actual command exits and bounded summaries against this exact publication checkout. Coverage includes BLE, capture/controls lifecycle, real service/BLE composition with controlled Android boundaries, installer preparation, refinement, job lifecycle, Whisper Java, calendar dates and instrumentation-output parsing.

Publication exposed a host-test timing race: the transition joiner can exit after posting, but before the fake main queue executes cleanup. `ControlsCompositionHostTest` now drains that queue after worker exit before asserting owner/foreground release. Assertions remain unchanged; application code is untouched. A disposable negative control removed owner release from `finishDisconnected`; the corrected test still failed on the owner/foreground-release assertion as expected. The initial failures, negative-control receipt and corrected rerun are retained separately.

These host checks are not Android runtime or physical BLE acceptance. No fresh Android build, phone install, recording, radio interaction or new APK release is performed for this Git update. The linked provenance records historical Android results separately.

## Remaining physical gates

Real single/double-press timing, standby without saved audio, music-app compatibility, supported shortcuts, background/locked-screen delivery, reconnect and sustained recording/thermal/battery endurance remain unverified on the wearable. Synthetic tests do not certify them.

## Source versus download version

This branch is 0.4.7 source. Existing `v0.3.1` APK downloads and root binary checksum/manifest files remain historical and unchanged. No new binary asset is uploaded by this source publication.
