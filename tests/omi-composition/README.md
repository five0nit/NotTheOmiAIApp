# Omi service/transport composition host checks

Run from the repository root (Python 3 and JDK 17+):

```bash
python3 tests/omi-composition/run_host_checks.py
```

The runner compiles **unchanged working-tree production** `OmiCaptureService`,
`OmiBle`, `OmiPcmQueue`, `LiveTranscript`, `ButtonEvent`, and vendored Concentus.
The real service constructs the real BLE object and installs its actual listener.
No passive test sink, fake `OmiBle`, source rewriting, Android SDK, Gradle, ADB,
network, dependency installation, firmware commands, or physical device is used.
Temporary Java sources/classes are removed on completion/failure.

Small Android/GATT/Vosk/model/notification fakes are reused by **AST literal
extraction** from the existing host runners; the sibling runners are never
imported or executed. This directory overrides only the required composition
boundaries: merged Context, Handler/Looper/HandlerThread, virtual SystemClock,
and a per-ID storage spy.

## Coverage and observed results

| Scenario | Assertions | IDs | Retained PCM bytes |
|---|---:|---|---:|
| `startup_retry` | 27 | `segment-1` | 640 |
| `sequence_loss` | 32 | `segment-1`, `segment-2` | 1280 |
| `malformed_opus` | 34 | `segment-1`, `segment-2` | 1920 |
| `link_recovery` | 43 | `segment-1`, `segment-2` | 1280 |
| `stop_backoff` | 27 | `segment-1` | 640 |

- Startup failure/retry before any decoded PCM does not start the recording
  clock, invent a gap, or create another segment.
- Real wire-framed Opus is encoded/decoded by Concentus. Packet sequence loss and
  actual invalid Opus decoding both reach the service's recovery listener.
- A processed recovery marker alone cannot finalize the old segment or create an
  empty successor. Resumed PCM creates an ordered distinct ID; the old gap and new
  continuation markers stay in their proper IDs. Old finish precedes new create.
- A second independent Concentus decoder supplies sample-exact little-endian PCM
  expectations. Assertions compare all bytes per segment, including sequential
  decoder state before the malformed frame and fresh decoder state after gaps.
- GATT disconnect status 8 closes the old link, preserves the foreground service,
  observes the exact 999/1000 ms backoff boundary, ignores stale old-link audio,
  and records PCM from a newly connected fake GATT via the real transport.
- Explicit service Stop during real transport backoff cancels the retry handle,
  tears down the BLE thread, ignores manually replayed stale retry/GATT events,
  and cannot revive after advancing three virtual minutes.
- The storage spy rejects writes to finished/non-current IDs and storage work on
  the BLE callback thread. Vosk's spy rejects ASR before archive or out-of-order
  PCM. Actual BLE/capture/speech Java threads must all exit; native doubles,
  foreground service, and wake lock must close exactly once.

Final suite: **163 assertions across 5 scenarios**, exit 0. Stability command
passed three consecutive final-source runs:

```bash
for run in 1 2 3; do python3 tests/omi-composition/run_host_checks.py || exit; done
```

## Regression sensitivity

Run one scenario against a read-only historical service source; all other
production sources still come from the current working tree:

```bash
python3 tests/omi-composition/run_host_checks.py --scenario sequence_loss --service-ref 44b240f
```

Observed expected exit **1**:

```text
java.lang.AssertionError: recovered PCM must create a distinct ordered segment
```

Its archive log contained both pre-gap and recovered `audio:segment-1:640`
appends with only one created/finished ID. This negative control verifies that
this harness detects the pre-fix splicing behavior instead of merely exercising
callbacks successfully. A historical run is not a full historical app rebuild.

## Limits

This proves host composition and ordering, **not** Android Bluetooth scheduling,
radio/hardware operation, microphone/acoustics, native Vosk recognition, real
notification visibility, keystore encryption, filesystem durability, or wearable
acceptance. GATT is deterministic; only its callbacks drive the production BLE
state machine. Optional button/LED/battery services are absent in these fixtures.
Other host suites cover those optional features and broader failure matrices.

The custom Handler scheduler uses a real joinable BLE thread and an explicitly
pumped main looper. Virtual time jumps to the requested instant; tests advance
one relevant retry boundary at a time. It does not emulate every Android Looper
race or wall-clock watchdog timing. Service/storage/speech still run on real
Java threads with bounded waits. No claims of exhaustive concurrency coverage.

Why not reuse the BLE fixture Handler unchanged? It executes every callback on
the test driver; production service teardown joins the callback's thread. That
would make the service wait on the test driver rather than a real exiting BLE
worker. The dedicated host HandlerThread preserves this composition boundary.
