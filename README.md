# NotTheOmiAIApp

Independent **offline Omi companion for Android 8+**. Public Android application source, tests and build tooling. Not affiliated with Omi or Based Hardware.

**[Explore the app and full feature guide →](https://five0nit.github.io/NotTheOmiAIApp/)**

**Android only for now — no iOS version.** Latest signed download: **0.4.11 / build 18**, for Android 8+ ARM64 phones.

**[Download v0.4.11 APK ↓](https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.4.11/NotTheOmiAIApp-0.4.11-arm64-v8a.apk)** · [Release notes, checksums and licences](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11)

The feature guide covers the downloadable **0.4.11** APK. Public application source remains **0.4.7**; the source and binary are separate checkpoints.

**Source version: 0.4.7 / versionCode 14.** Application license: [MIT](LICENSE); third-party components retain their [own notices](THIRD_PARTY_NOTICES.md).

## Source and APK downloads

- This branch contains the **0.4.7 source**, including recording recovery, the calendar Library and configurable button actions.
- [Latest v0.4.11 preview APK](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11) is available for `arm64-v8a`, with matching checksums, manifest and licences attached to that release. It adds the 15 GB retained PCM quota, RF64 export, expanded media/triple-press controls, optional notification vibrations and queue-capacity recovery.
- [Older v0.4.7 preview](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.7) remains available as a historical release.
- The [v0.3.1 download](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.3.1), root `SHA256SUMS.txt`, `release-manifest.json` and `RELEASE-NOTES-0.3.1.md` describe **older 0.3.1 binaries**, not the 0.4.11 release.
- GitHub-generated source ZIPs contain the public 0.4.7 application checkpoint, not the 0.4.11 changes. They are not installable APKs or matching 0.4.11 build inputs.
- Package: `app.nottheomi.ai`. Install updates over the existing app only with the same signer; **do not uninstall or clear data** to preserve the local encryption key and recordings. Your own signing key cannot update an existing differently signed installation.

## Public source checkpoint (0.4.7)

The implementation notes and build instructions below describe **0.4.7 source**, not the newer downloadable binary. Use the [feature guide](https://five0nit.github.io/NotTheOmiAIApp/) and [v0.4.11 release notes](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11) for current APK behaviour.

## What it does

- Omi Bluetooth LE Opus audio → encrypted phone-local PCM and transcript history.
- Bundled **Vosk** for streaming English drafts; CPU-only **Whisper small.en Q5_1** for post-save refinement.
- Searchable calendar/day Library, bounded previews, playback and explicit WAV/text export.
- Independently saved single-press and double-press functions, plus button-only standby without recording audio.
- No Omi account, PC relay, cloud transcription, runtime model download or `INTERNET` permission.
- Capability-gated battery, brightness and button controls. Long-press/power behavior stays firmware-owned.
- Explicit phone-microphone fallback; no automatic substitution when the wearable is absent.

## Connect and record

1. Disconnect other Omi apps, wake/charge the wearable and enable Bluetooth.
2. Tap **Connect Omi**, grant Nearby devices and notifications, then select your device. Android 8–11 also needs Location permission and system Location for BLE discovery; the app does not collect location.
3. Recording starts when decoded Omi audio arrives. Preparing/Connecting is not Recording. Initial model verification/copying is local.
4. **Stop & save** ends recording and retains a button-only connection. Saved sessions remain in Library; Whisper refinement runs after capture stops. **Disconnect buttons** closes the remaining connection.

App launch/reboot never auto-records. Cancellation before the first audio and fatal failures terminate rather than reopening standby. Brightness zero requests minimum, not guaranteed darkness. Optional controls stay unavailable when characteristic/readback validation fails.

## Button functions

In Omi settings, choose each gesture independently:

- Bookmark in transcript (original single-press default).
- Start recording.
- Stop & save (original double-press default).
- Start / stop recording.
- Play / pause music.
- Pause music.
- Phone shortcut.
- No action.

Use **Connect for buttons — no recording** to start button-only standby directly. The app does not subscribe to the audio stream or create a recording in this mode. A configured start/toggle press can transition into recording; Stop & save drains accepted audio, finishes the recording, then returns to standby. Disconnect ends both capture and button control.

Music functions dispatch Android media keys to the active media session. Shortcut selection supports eligible exported phone activities and supported shortcut providers; background/locked-screen launches use a tap-to-run notification when required by Android. These are app-local mappings while connected, not firmware remapping or unrestricted phone automation.

**Physical presses, media-app compatibility, locked-screen delivery and sustained wearable reliability remain hardware acceptance gates.** Synthetic tests do not certify those behaviors.

## Recovery and storage

Initial startup without decoded audio is bounded at **120 seconds**. After an explicit session has received PCM, prolonged audio absence remains Waiting for Omi with capped-backoff recovery until explicit Stop/Disconnect or a terminal failure. Connection callbacks, battery traffic and malformed packets do not prove recovery.

Reported BLE gaps create separate encrypted continuation recordings when actual audio resumes. Playback/export do not splice audio across a known gap; retries without decoded PCM do not create empty entries. Accepted pre-Stop audio still drains to storage. Missing audio is not reconstructed, and pauses without a transport gap may remain undetected.

Storage and live-preview queues are independently bounded. Audio is archived before preview recognition. Whisper refines saved encrypted PCM in bounded batches with durable encrypted checkpoints; interrupted work resumes from its last committed offset. A permanently blocked native recognizer can delay final save/teardown. Real screen-off and workday endurance remain unverified.

## Privacy

AES-GCM audio/text with an Android Keystore key. Backups disabled; screen captures protected. Uninstall/data clear loses the key. User-requested exports are plaintext. Device selection and controls stay on the phone; transcript text is not placed in notifications/logs.

The **2 GiB aggregate retained PCM quota** includes existing recordings; 128 MiB free-space reserve. English only. Recognition may be wrong. Process death may lose an uncommitted tail. Record with participants' permission.

## Build from source

Prerequisites: Java 17, Python 3, Android SDK/platform 34, Android build-tools 34.0.0, Android NDK **27.2.12479018**, and CMake. Native build scripts target Linux/WSL. Set `ANDROID_SDK_ROOT` to your SDK location and configure `sdk.dir` in your local, untracked `local.properties` if needed.

```sh
python3 scripts/prepare_model.py
python3 scripts/build_whisper.py --ndk "$ANDROID_SDK_ROOT/ndk/27.2.12479018"
./gradlew --no-daemon assembleDebug assembleRelease assembleDebugAndroidTest lintDebug --console=plain
```

Preparation downloads and verifies pinned public models/source. Native compilation itself does not download. Provenance is pinned in [DEPENDENCIES.json](DEPENDENCIES.json). Supported ABIs: `arm64-v8a`, `x86_64`; no 32-bit ARM. Model weights, generated native libraries, APKs, signing keys and machine configuration are excluded from Git.

Use `python3 scripts/sign_release.py --help` for signing with **your own external key directory**. Original release signing keys are not published. `tests/hybrid/verify_apk.py` checks models/native payloads, both speech engines, signature and lack of Internet permission.

## Verification

[0.4.7 verification scope](verification/PUBLIC-SOURCE-0.4.7.md) separates retained build/Android/installation evidence from fresh publication host checks and remaining physical acceptance. Earlier verification documents are historical, not current-release proof.

No phone is needed for these controlled host checks. First prepare pinned model assets and configure `sdk.dir`: capture installer checks use the real models and Android SDK; other host entrypoints use controlled doubles.

```sh
python3 tests/omi/run_ble_tests.py
python3 tests/omi-capture/run_host_checks.py
python3 tests/omi-composition/run_host_checks.py
python3 tests/capture/run_host_checks.py
python3 tests/hybrid/run_host_checks.py
python3 tests/hybrid/run_job_checks.py
python3 tests/whisper-java/run_host_checks.py
python3 tests/library-dates/run_host_checks.py
python3 tests/buttons/test_android_runner.py
```

Android instrumentation needs prepared artifacts and an explicitly selected synthetic target. `tests/buttons/run_android_checks.py` is constrained to its isolated emulator and rejects an active app service; it is not a production-phone installer. KVM-backed x86_64 execution is recommended: an older software-emulation path produced an instruction overread absent under KVM with unchanged APK bytes.

For `tests/whisper-device/run_probe.py` with Windows `adb.exe`, pass `--windows-temp` with an existing Windows-accessible WSL directory. Public `jfk.wav` and `test.wav` are upstream fixtures, not private recordings. Production-phone diagnostics, private transcripts, signing material and private Git history are excluded.
