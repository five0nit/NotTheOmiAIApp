# Public source verification — 0.4.4 / versionCode 11

## Publication scope

This is a curated source snapshot from local checkpoint `3564d575078e715c9cd7bb248c711c27893dcc92`, layered on the existing public downloads repository without importing private Git history. Production-device logs, identifiers, runtime dumps, private audio/transcripts, machine configuration, signing material and raw historical receipts are excluded.

Application/runtime sources, native integration and build inputs copied from the checkpoint are byte-preserved. Public documentation is rewritten to describe this source release and retained older APK downloads accurately. One test-helper portability change replaces a developer-specific Windows temporary directory with an explicit validated `--windows-temp` argument. No application/runtime or installed APK change is made by this publication.

## Retained release evidence — not newly executed Android tests

The private source/APK binding receipt recorded 237 build/source inputs and the exact three signed 0.4.4 release artifacts. Unchanged published bound inputs are hash-matched to that receipt; the modified test helper and excluded generated inputs are identified separately in the derived [public provenance manifest](public-source-provenance.json). Original private receipts are retained unchanged.

Prior verification of that release includes:

- Exact signed x86_64 synthetic Android suite: **124 tests passed**.
- Two additional five-test synthetic throughput runs; across six sustained cases, **36,000/36,000 frames** accepted with zero rejection.
- Corrected seven-entrypoint canonical host matrix passed. An earlier broad runner failed because it invoked a nonexistent test entrypoint; that failed run is not relabelled successful.
- Signed ARM64 release installed in place on a production phone with exact installed-byte/signing-identity readback. UID, first-install and data-directory identities and permissions preserved. This is not a recording-by-recording decryption/content audit.

## Fresh publication verification

[fresh-host-checks.json](fresh-host-checks.json) records actual host checks run against this publication checkout, with individual exits and relative log paths. These are controlled host tests, not physical BLE acceptance. The public provenance manifest maps copied files to checkpoint hashes and identifies the one helper change.

No new Android build, emulator suite, phone installation, recording, radio endurance run or APK release upload is implied by the source push.

## Remaining acceptance gates

Physical wearable reconnect, Home/screen-off and workday endurance remain unverified. The intentional 120-second initial/subsequent no-PCM terminal policy and 2 GiB aggregate retained PCM quota remain unchanged. A permanently stuck native call can delay final saving/teardown.

## Public fixtures and licenses

- [JFK fixture provenance](whisper-fixture.json).
- [Vosk fixture provenance](speech-fixture.json).
- [Protocol compatibility notes](OMI-PROTOCOL.md).
- [Application license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md).
