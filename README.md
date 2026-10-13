# NotTheOmiAIApp

Independent **offline Omi companion for Android 8+**. Public Android application source, tests and build tooling. Not affiliated with Omi or Based Hardware.

**[Explore the app and full feature guide →](https://five0nit.github.io/NotTheOmiAIApp/)**

**Android only for now — no iOS version.** Latest signed download: **0.4.11 / build 18**, for Android 8+ ARM64 phones.

**[Download v0.4.11 APK ↓](https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.4.11/NotTheOmiAIApp-0.4.11-arm64-v8a.apk)** · [Release notes, checksums and licences](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11)

The feature guide covers the downloadable **0.4.11** APK and labels the newer **0.4.12 source** controls separately. **No v0.4.12 APK release is published.** Updating this branch does not update the download.

**Source version: 0.4.12 / versionCode 19.** Application license: [MIT](LICENSE); third-party components retain their [own notices](THIRD_PARTY_NOTICES.md).

## Source and APK downloads

- This branch contains the **0.4.12 source**, including the 0.4.11 capture/storage features and new control profiles, four/five-press actions, flashlight and notification app/pattern choices.
- [Latest v0.4.11 preview APK](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11) is available for `arm64-v8a`, with matching checksums, manifest and licences attached to that release. It adds the 15 GB retained PCM quota, RF64 export, expanded media/triple-press controls, optional notification vibrations and queue-capacity recovery.
- [Older v0.4.7 preview](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.7) remains available as a historical release.
- The [v0.3.1 download](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.3.1), root `SHA256SUMS.txt`, `release-manifest.json` and `RELEASE-NOTES-0.3.1.md` describe **older 0.3.1 binaries**, not the 0.4.11 release.
- A source ZIP from the current branch contains the 0.4.12 application checkpoint, not an installable APK or matching 0.4.11 build inputs. Automatic source archives attached to older release tags remain historical snapshots; publishing this branch does not change those tags.
- Package: `app.nottheomi.ai`. Install updates over the existing app only with the same signer; **do not uninstall or clear data** to preserve the local encryption key and recordings. Your own signing key cannot update an existing differently signed installation.

## Public source checkpoint (0.4.12)

The implementation notes and build instructions below describe **0.4.12 source**, ahead of the downloadable binary. The [feature guide](https://five0nit.github.io/NotTheOmiAIApp/) distinguishes source-only additions from the [v0.4.11 APK](https://github.com/five0nit/NotTheOmiAIApp/releases/tag/v0.4.11).

## What it does

- Omi Bluetooth LE Opus audio → encrypted phone-local PCM and transcript history.
- Bundled **Vosk** for streaming English drafts; CPU-only **Whisper small.en Q5_1** for post-save refinement.
- Searchable calendar/day Library, bounded previews, playback and explicit WAV/RF64/text export, with a 15 GB retained PCM quota.
- Independent single/double/triple/four/five-press actions and manually selected profiles, plus button-only standby without recording audio.
- Optional notification vibrations with app selection and default/per-app patterns in source 0.4.12.
- No Omi account, PC relay, cloud transcription, runtime model download or `INTERNET` permission.
- Capability-gated battery, brightness and button controls. Long-press/power behavior stays firmware-owned.
- Explicit phone-microphone fallback; no automatic substitution when the wearable is absent.

## Connect and record

1. Disconnect other Omi apps, wake/charge the wearable and enable Bluetooth.
2. Tap **Connect Omi**, grant Nearby devices and notifications, then select your device. Android 8–11 also needs Location permission and system Location for BLE discovery; the app does not collect location.
3. Recording starts when decoded Omi audio arrives. Preparing/Connecting is not Recording. Initial model verification/copying is local.
4. **Stop & save** ends recording and retains a button-only connection. Saved sessions remain in Library; Whisper refinement runs after capture stops. **Disconnect buttons** closes the remaining connection.

App launch/reboot never auto-records. Cancellation before the first audio and fatal failures terminate rather than reopening standby. Brightness zero requests minimum, not guaranteed darkness. Optional controls stay unavailable when characteristic/readback validation fails.

## Button functions — source 0.4.12

In **Omi device & controls**, manually choose **Everyday**, **Meeting** or **Music**. Each profile retains independent actions and shortcut targets. Everyday preserves existing mappings; Music starts with play/pause and next track. Switching profiles cancels pending gestures; notification preferences remain shared. Profiles do not switch automatically.

Each profile has independent **Single press**, **Double press**, **Triple press**, **Four presses** and **Five presses** dropdowns. Available actions:

- Bookmark in transcript (original single-press default).
- Start recording.
- Stop & save (original double-press default).
- Start / stop recording.
- Play / pause music.
- Pause music.
- Play music, Next track, Previous track, Stop music, Fast-forward music and Rewind music.
- Media volume up, Media volume down and Mute / unmute media (media stream only).
- Phone shortcut.
- Flashlight toggle.
- Smart: bookmark recording / play-pause idle.
- Pause music → start recording.
- No action.

**Four/five start with No action.** Triple-only retains its **650 ms** fixed grouping window while four/five remain unassigned. Assigning four extends the window to **950 ms**; assigning five extends it to **1250 ms**. The highest assigned count sets the window, measured from the first received firmware tap event rather than a sliding deadline. Shorter gestures wait for that window, so enabling higher counts adds latency. Nearby separate gestures can combine. With triple/four/five all unassigned, original single/double timing is retained. This is experimental app-side grouping of stock single/double notifications, not native firmware gestures.

**Smart: bookmark recording / play-pause idle** inserts a bookmark during recording and requests music play/pause in controls-only standby. **Pause music → start recording** is a bounded built-in action: it requests Pause, then starts only when idle; it never stops an existing recording. Android does not acknowledge whether the media player actually paused. No arbitrary macros or automatic profiles are provided.

**Flashlight toggle** uses Android **CameraManager**, not a camera preview or photo capture. Use **Allow flashlight permission** to explicitly grant the optional Camera permission; selecting an action does not grant permission or turn on the torch. Status follows observed torch callbacks and reports unknown, unsupported or camera-busy states rather than claiming success. Background and locked-screen behaviour depends on Android and the phone.

Use **Connect for buttons — no recording** to start button-only standby directly. The app does not subscribe to the audio stream or create a recording in this mode. A configured start/toggle press can transition into recording; Stop & save drains accepted audio, finishes the recording, then returns to standby. Disconnect ends both capture and button control.

Music functions dispatch Android media keys to the active media session. Shortcut selection supports eligible exported phone activities and supported shortcut providers; background/locked-screen launches use a tap-to-run notification when required by Android. These are app-local mappings while connected, not firmware remapping or unrestricted phone automation.

**Real four/five-press cadence, motor patterns and locked-screen torch physical acceptance remain pending.** Media-app compatibility, shortcut delivery and sustained wearable reliability also need hardware acceptance; synthetic tests do not certify these behaviours.

## Notification vibrations — source 0.4.12

Notification vibrations remain **off by default** and opt-in, requiring Android notification access and an existing recording or button-only Omi connection. Upgrading preserves the previous opt-in state, All apps behaviour and default double pulse.

- Choose **All apps** or **Selected apps only**. An empty selected-app list produces no notification pulses.
- Choose a default **Short pulse**, **Double pulse**, **Triple pulse** or **Long pulse**, with per-app overrides. Overrides apply in either app-selection mode.
- Notification settings are independent of the manually selected control profile. Changing notification choices cancels queued vibrations; it does not grant access, connect or record.
- Automatic notifications retain metadata-only filtering, Do Not Disturb suppression and the **1.5 seconds** burst limit. Silent/low-importance, ongoing, own-app, group-summary, duplicate and stale events are skipped. Message text is not read, saved or sent to Omi.
- The manual test uses the selected default pattern on the existing connection and does not itself check DND. BLE acknowledgments are not measurements of motor output.
- No second BLE connection, replay backlog or phone-motor substitution. The downloadable 0.4.11 APK has the earlier opt-in all-app double-pulse feature, not these app/pattern selectors.

## Recovery and storage

Initial startup without decoded audio is bounded at **120 seconds**. After an explicit session has received PCM, prolonged audio absence remains Waiting for Omi with capped-backoff recovery until explicit Stop/Disconnect or a terminal failure. Connection callbacks, battery traffic and malformed packets do not prove recovery.

Reported BLE gaps create separate encrypted continuation recordings when actual audio resumes. Playback/export do not splice audio across a known gap; retries without decoded PCM do not create empty entries. Accepted pre-Stop audio still drains to storage. Missing audio is not reconstructed, and pauses without a transport gap may remain undetected.

Storage and live-preview queues are independently bounded. Audio is archived before preview recognition. Whisper refines saved encrypted PCM in bounded batches with durable encrypted checkpoints; interrupted work resumes from its last committed offset. A permanently blocked native recognizer can delay final save/teardown. Real screen-off and workday endurance remain unverified.

## Privacy

AES-GCM audio/text with an Android Keystore key. Backups disabled; screen captures protected. Uninstall/data clear loses the key. User-requested exports are plaintext. Device selection and controls stay on the phone; transcript text is not placed in notifications/logs.

The **15 GB aggregate retained PCM quota** (15,000,000,000 bytes) includes existing recordings; 128 MiB free-space reserve. Large WAV exports use RF64; models, transcripts, database overhead and exports use additional space. English only. Recognition may be wrong. Process death may lose an uncommitted tail. Record with participants' permission.

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

See the [0.4.12 public-source verification scope](verification/PUBLIC-SOURCE-0.4.12.md) for fresh publication checks, retained candidate evidence and remaining physical gates.

Retained 0.4.12 evidence includes **125 Android instrumentation tests** on the isolated emulator, **54 gesture scenarios**, and **641 haptic/BLE scenarios**. These checks overlap; do not add the counts together. They are retained software evidence, not a claim of a new public APK release or completed wearable physical acceptance. Real four/five cadence, motor patterns and locked-screen torch checks remain pending. The [0.4.7 verification scope](verification/PUBLIC-SOURCE-0.4.7.md) and earlier verification documents are historical, not current-source proof.

The documentation regression is independent of Android builds and model preparation:

```sh
python3 tests/pages/check_site.py
```

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
