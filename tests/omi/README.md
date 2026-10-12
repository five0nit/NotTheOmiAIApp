# Omi transport host fixtures

Run from repository root with Python 3 and JDK 17+:

```sh
python3 tests/omi/run_ble_tests.py
# Or select one or more fixture classes:
python3 tests/omi/run_ble_tests.py TransportBleTest LedBleTest
```

No Gradle, Android SDK, network, ADB or physical peripheral is needed. The runner
compiles the actual `OmiBle.java`, `ButtonEvent.java`, all 124 vendored Concentus
Java sources, and these fixtures into an automatically removed temporary folder.
Android/Bluetooth/Handler are deterministic host fakes, not an Android suite.
The GATT fake asserts one outstanding operation; Handler uses an ordered virtual
clock and owner-scoped cancellation. Codec payloads use real Concentus encoding
and decoding rather than fabricated PCM.

## Coverage

- `ButtonBleTest`: strict stock 8-byte button layout; retained-read suppression;
  serial required audio/optional button CCCs; descriptor identity; both callback
  APIs; optional failure/timeouts; PCM readiness and watchdogs; stale callbacks.
- `ControlsBleTest`: required button-only subscription without an audio service;
  no codec read/audio CCC/decoder/audio watchdog or unsolicited PCM; exact discrete
  button events; required-button failures and controls-only retry; same-instance
  close-before-open capture handovers; generation invalidation at native loss,
  selection and Stop; native-close completion barriers and reentrant cancellation.
- `LedBleTest`: current-connection successful-read prerequisite; explicit 0–100
  write; acknowledged write followed by readback; busy rejection; stale targets,
  timeouts and ATT quarantine. Device readback is not reboot persistence proof.
- `BatteryBleTest`: standard BAS strict one-byte percentage; idle periodic reads;
  optional failures/quarantine; no overlap with LED/button work; stale callbacks.
- `ReconnectBleTest`: discovery recheck, operation deadlines, bounded fast retry
  burst followed by persistent backoff, startup vs established-audio watchdog,
  disconnect/Stop/new-selection invalidation.
- `TransportBleTest`: codecs 20 and 21; exact codec read
  identity/layout; MTU uncertainty never overlaps discovery; actual fragmented
  Opus sample equality, sequence wrap/loss and decoder reset; malformed/orphan/
  oversized frames; scan permission failures/epochs; reentrant setup cancellation
  and null platform callbacks. Named OmiGlass regressions reject its colliding
  two-byte OTA status layout (both read callback APIs), revoke any prior read
  authorization, and prove no start/cancel/status command is written as brightness.

## Controls-only integration contract

`connectControls(String address)` uses the existing selection FIFO and teardown,
but goes directly from link/discovery to the button CCC. It does not negotiate an
audio MTU or perform optional BAS/LED operations. Missing or failed button support
is visible and retryable; controls mode never falls back to capture.
`connect(String address)` keeps its codec/audio-first path. Either mode closes the prior native
GATT before opening its replacement on the same instance.

`stop(Runnable afterClosed)` is an additive native-close barrier. The callback runs
once after `stopInternal()` and native disconnect/close calls have returned (or
thrown, under the existing best-effort teardown). It runs outside `workerLock` on
the BLE worker, after `quitSafely()` is requested if no newer selection retains the
queue; if no worker exists it runs synchronously on the caller. It may post work
to main or start a new selection. A later Stop/selection cannot silently cancel a
requested completion. It does **not** prove actual worker-thread termination:
retain any service-owned off-worker join needed for a full capture drain, and
never join the BLE worker from its completion callback. No `stopAndAwait()` was
added, and the unchanged no-argument `stop()` remains available.

Override `Listener.onButton(int event, long generation)` to carry the original
notification token onto a downstream/main queue. Before acting, compare it to
the same client's `connectionGeneration()` (and verify the client is still the
service's selected instance). The token is invalidated synchronously by selection
or Stop, at current-GATT native loss ingress, and at close. It is not readiness.
Do not re-sample the token later and attach it to an old event. The default overload
delegates to existing `onButton(int event)` listeners for source compatibility.

## Fixture provenance

Ported from the local `omi-private-phone` host fixtures with package/path changes.
`TransportBleTest` and the aggregate runner add scoped hardening coverage; the
shared fakes now model scan failures, MTU rejection and delivered PCM samples.
Individual `run_*` entry points are retained for the four original fixtures.

The Concentus source pin is `3885c4e46513ef0fc81fca100189e54f1714c6ca`;
protocol provenance is Omi `d1fdcb4cc4fbd021eac799630e476f144e6bc18a`.
See `third_party/` licenses and `verification/omi-transport-*` receipts.
Host success does **not** establish Android runtime, wearable compatibility,
Bluetooth permissions UI, radio timing, physical battery accuracy, LED reboot
persistence, capture-service lifecycle, or speech-engine acceptance.
