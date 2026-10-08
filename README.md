# NotTheOmiAIApp 0.4.4

Independent **offline Omi companion for Android 8+**. Public Android application source, tests and build tooling. Not affiliated with Omi or Based Hardware.

**Source version: 0.4.4 / versionCode 11.** This source publication supersedes the repository's earlier APK-only policy. Application license: [MIT](LICENSE); third-party components retain their [own notices](THIRD_PARTY_NOTICES.md).

## Source and APK downloads

- This branch contains the **0.4.4 source**.
- [Published APK releases](https://github.com/five0nit/NotTheOmiAIApp/releases) remain separate. The existing [v0.3.1 download](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.3.1), root `SHA256SUMS.txt`, `release-manifest.json` and `RELEASE-NOTES-0.3.1.md` describe the **older 0.3.1 binaries**, not the 0.4.4 source.
- This source push does not publish a new 0.4.4 binary release. Do not confuse a GitHub source ZIP with an installable APK.
- Package: `app.nottheomi.ai`. Install an update over the existing app only with the same signer; **do not uninstall or clear data** to preserve the local encryption key and recordings. Your own signing key cannot update an existing differently signed installation.

## What it does

- Omi Bluetooth LE Opus audio → encrypted phone-local PCM and transcript history.
- Bundled **Vosk** for streaming English drafts; CPU-only **Whisper small.en Q5_1** for post-save refinement.
- Playback, searchable library and explicit WAV/text export.
- No Omi account, PC relay, cloud transcription, runtime model download or `INTERNET` permission.
- Capability-gated battery, brightness and button controls. Long-press/power behavior stays firmware-owned.
- Explicit phone-microphone fallback; no automatic substitution when the wearable is absent.

## Connect and record

1. Disconnect other Omi apps, wake/charge the wearable and enable Bluetooth.
2. Tap **Connect Omi**, grant Nearby devices and notifications, then select your device. Android 8–11 also needs Location permission and system Location for BLE discovery; the app does not collect location.
3. Recording starts automatically when decoded Omi audio arrives. Preparing/Connecting is not Recording. Initial model verification/copying is local.
4. Use **Disconnect & save**, notification Stop or the configured double-press action to finish. Saved sessions remain in the library; Whisper refinement runs after capture stops.

Stop cancels retries; app launch/reboot never auto-records. Brightness zero requests minimum, not guaranteed darkness. Optional controls remain unavailable when characteristic/readback validation fails.

## 0.4.4 stability changes

- Bounded authenticated archive quota cache removes repeated whole-history scanning from the audio append hot path while preserving integrity, corruption checks and whole-request quota admission.
- Wake-lock renewal anchored to actual acquisition, including slow preparation.
- Deduplicated identical foreground notifications.
- Startup/recovery deadline regressions, archive hot-path and sustained synthetic throughput tests.

BLE gaps trigger bounded retries and separate encrypted continuation entries: playback/export do not splice audio across a known gap. Decoded PCM—not connection callbacks, battery traffic or malformed packets—proves recovery. Startup and uninterrupted no-PCM periods retain the intentional **120-second terminal policy**. Explicit Stop, storage failures and bounded-ingress safeguards remain effective.

**Physical wearable reconnection, screen-off and workday endurance are not certified.** See [public verification scope](verification/PUBLIC-SOURCE-0.4.4.md). Missing audio is not reconstructed. A permanently blocked native recognizer can delay final save/teardown.

## Privacy and storage

AES-GCM audio/text with an Android Keystore key. Backups disabled; screen captures protected. Uninstall/data clear loses the key. User-requested exports are plaintext. Device selection and controls remain on the phone; transcript text is not placed in notifications/logs.

The **2 GiB aggregate retained PCM quota** includes existing recordings; 128 MiB free-space reserve. English only. Recognition may be wrong. Process death may lose an uncommitted tail. Record with participants' permission.

## Build from source

Prerequisites: Java 17, Python 3, Android SDK/platform 34, Android build-tools 34.0.0, Android NDK **27.2.12479018**, and CMake. Native build scripts target Linux/WSL. Set `ANDROID_SDK_ROOT` to your SDK location and configure `sdk.dir` in your local, untracked `local.properties` if needed.

```sh
python3 scripts/prepare_model.py
python3 scripts/build_whisper.py --ndk "$ANDROID_SDK_ROOT/ndk/27.2.12479018"
./gradlew --no-daemon assembleDebug assembleRelease assembleDebugAndroidTest lintDebug --console=plain
```

The preparation script downloads and verifies pinned public models/source. Native compilation itself does not download. Build provenance is pinned in [DEPENDENCIES.json](DEPENDENCIES.json). Supported ABIs: `arm64-v8a`, `x86_64`; no 32-bit ARM. Model weights, generated native libraries, APKs, signing keys and machine configuration are excluded from Git.

Use `python3 scripts/sign_release.py --help` for signing with **your own external key directory**. Original release signing keys are not published. `tests/hybrid/verify_apk.py` checks models/native payloads, both speech engines, signature and lack of Internet permission.

## Host tests

No phone is needed for these controlled host checks. First prepare the pinned model assets and configure `sdk.dir` in `local.properties` as above: the capture installer checks use the real model files and Android SDK. The other host entrypoints use controlled doubles.

```sh
python3 tests/omi/run_ble_tests.py
python3 tests/omi-capture/run_host_checks.py
python3 tests/omi-composition/run_host_checks.py
python3 tests/capture/run_host_checks.py
python3 tests/hybrid/run_host_checks.py
python3 tests/hybrid/run_job_checks.py
python3 tests/whisper-java/run_host_checks.py
```

Device/instrumentation tests require their explicitly selected Android target and prepared artifacts. For the optional `tests/whisper-device/run_probe.py` helper with Windows `adb.exe`, pass `--windows-temp` with an existing Windows-accessible WSL directory; no developer-specific user path is embedded.

Public `jfk.wav` and `test.wav` are upstream test fixtures, not private recordings. The source publication contains no production-phone diagnostics, private transcripts, signing material or private Git history. Retained verification claims and freshly executed publication checks are distinguished in [verification evidence](verification/PUBLIC-SOURCE-0.4.4.md).
