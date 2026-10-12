# Omi service/transport composition host checks

Run from the repository root (Python 3 and JDK 17+):

```bash
python3 tests/omi-composition/run_host_checks.py
python3 tests/omi-composition/run_host_checks.py --scenario controls_stop_standby
```

The runner compiles **unchanged working-tree production** `OmiCaptureService`,
`OmiBle`, `OmiPcmQueue`, `LiveTranscript`, `ButtonEvent`, and vendored Concentus.
The real service constructs the real BLE object and installs its actual listener.
Android/GATT, storage, preview speech, and `ButtonActions` are boundary doubles;
this is not a fake `OmiBle` or a passive-listener-only transport test.

No Android SDK, Gradle, ADB, network, dependency installation, firmware commands,
or physical device is used. Temporary Java sources/classes are removed on
completion/failure. Sibling fixture dictionaries are read with **AST literal
extraction**; the sibling runners are never imported or executed.

The runner checks each scenario's actual PASS receipt and sums its assertion
count. Every scenario runs in a separate JVM, so static lifecycle state cannot
leak between scenarios. The final line reports actual scenario/assertion totals;
there is no manually maintained assertion-count table.

## Archive/recovery scenarios

`OmiCompositionHostTest` retains the sample-exact archive checks for:

- `startup_retry`: startup failure/retry before decoded PCM does not start the
  recording clock, invent a gap, or create another segment.
- `sequence_loss`, `malformed_opus`, `link_recovery`: real wire-framed Opus and
  real Concentus decoding drive service recovery. A processed gap alone cannot
  finalize the old segment or create an empty successor. Resumed PCM creates a
  distinct ordered archive; old finish precedes new create.
- `recovery_before_deadline`, `recovery_long_absence`: established capture
  survives extended missing audio, bounded retries, and recovery near the old
  startup-deadline boundary without fabricating PCM or empty segments.
- `startup_at_deadline`: no-PCM startup terminates at its actual virtual deadline
  and fences manually replayed stale retries/GATT events.
- `diagnostics`: payload-free bounded diagnostics distinguish ingress, decode,
  archive, native loss, audio timeout, GATT timeout, and explicit closure.
- `disconnect_backoff`: explicit terminal Disconnect cancels the old retry,
  exits the real BLE thread, and cannot revive through stale callbacks/deadlines.
  The old CLI name `stop_backoff` remains an alias for this renamed scenario.

These archive-focused cases end with **Disconnect**, not the intentional
Stop-to-standby path. Their byte/ID/order/status expectations are unchanged.
A second independent Concentus decoder supplies sample-exact little-endian PCM
expectations, including fresh decoder state after gaps.

## Controls lifecycle scenarios

`ControlsCompositionHostTest` advertises audio **and** button services and drives
stock eight-byte button notifications through the real `OmiBle` parser:

- `controls_idle`: no model preparation, speech owner, storage access, archive,
  capture wake lock, codec read, audio CCC, decoder, or audio watchdog. Default
  bookmark/stop and reserved LONG cannot start recording. Explicit Disconnect
  revokes controls immediately, closes GATT, and exits the actual BLE worker.
- `controls_start_exit`, `controls_toggle_exit`: idle start/toggle first closes
  native GATT, then waits for **actual old worker exit** before model/storage
  preparation and capture connect. Retired service listeners and old GATT
  callbacks cannot bookmark or stop the replacement recording.
- `controls_stop_standby`: default double-press Stop closes BLE, drains a blocked
  archive commit plus already-accepted FIFO PCM, waits for real BLE exit and a
  separately blocked final ASR result, finalizes the exact archive, then opens a
  fresh controls-only transport. No second model or archive is created.
- `controls_disconnect_drain`: ACTION_STOP starts that same bounded teardown;
  `requestDisconnect()` during the drain cancels queued standby without losing
  accepted PCM or the final speech result.
- `controls_disconnect_transition`: Disconnect while the closed controls worker
  is still alive cancels queued recording start before any capture resources
  or replacement connection are created.
- `controls_stop_backoff`: intentional `requestStopCapture()` cancels the old
  recording retry while opening fresh standby; replayed old retry/button events
  cannot restart capture or replace standby.
- `controls_stale_gatt`: a start or shortcut queued to main is invalidated by
  native loss **at callback ingress**, before worker/main dispatch. Controls
  retry uses the real backoff; old-GATT buttons stay stale after reconnection.
- `controls_stale_listener`: Disconnect fences both main-queued and directly
  replayed retired-listener actions, including the legacy listener overload.
- `controls_live_buttons`: original bookmark default, live ignore/shortcut/start/
  toggle mapping changes, idempotent start during recording, and reserved
  LONG/PRESS/RELEASE behavior. Shortcut execution itself is a boundary spy, not
  proof of an Android activity launch or notification.
- `controls_prepcm_stop`, `controls_fatal_stop`: cancellation before PCM and
  fatal permission loss terminate rather than silently reopening standby;
  existing PCM is retained sample-exactly on fatal teardown.

`controlsActive` must remain false during recording, drain, and transitions.
Resource and worker cleanup checks run after explicit terminal Disconnect in
all scenarios. Refinement priority is checked against actual recording episodes.

## Scheduler and boundary fidelity

The composition Handler/Looper uses a **real joinable JVM BLE thread** and an
explicit main-thread pump. A per-worker exit gate can hold the thread alive
**after** its loop has quit and native GATT has closed; a `beforeConnect` hook
rejects replacement connection while that old thread is alive. This distinguishes
`stop(afterClosed)` completion from actual thread termination.

`Handler.drainWorkers()` fences currently runnable BLE tasks without executing
main callbacks. This allows queued button actions to be invalidated before UI
dispatch. Virtual time jumps to the requested instant; backoff tests step across
specific deadlines rather than assuming real-time Android scheduling.

The storage spy rejects writes to finished/non-current IDs, storage on the BLE
thread, and invalid PCM append sizes. Its archive gate blocks outside its monitor
so tests can inspect the saved prefix without deadlocking. The preview speech
spy rejects ASR before archive or reordered PCM and offers an independent final-
result gate. Tests release all injected gates in `finally` before teardown.

## Historical regression sensitivity

Read a historical service source without modifying the checkout:

```bash
python3 tests/omi-composition/run_host_checks.py --scenario sequence_loss --service-ref 44b240f
```

Historical refs run only archive scenarios. The archive terminal helper falls
back to legacy ACTION_STOP only when ACTION_DISCONNECT does not exist in the
historical service; the current controls harness is not compiled for those runs.
Previously observed expected failure against `44b240f`:

```text
java.lang.AssertionError: recovered PCM must create a distinct ordered segment
```

That failure identifies pre-fix splicing, not a missing new controls API. A
historical-source run is not a full historical app rebuild.

## Limits

This proves host composition and ordering, **not** Android Bluetooth scheduling,
radio/hardware operation, native Vosk recognition, real notification visibility,
keystore encryption, filesystem durability, phone shortcut dispatch, or wearable
acceptance. There are no claims of exhaustive concurrency coverage. Optional
LED/battery protocol behavior and broader transport faults remain in their
separate focused suites.
