package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Production BLE state machine; deterministic host transport, no service harness. */
public final class ControlsBleTest {
    static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    static Object field(OmiBle ble, String name) {
        try {
            java.lang.reflect.Field field = OmiBle.class.getDeclaredField(name);
            field.setAccessible(true); return field.get(ble);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void connectControls(Fixture f) { f.ble.connectControls(ADDRESS); Handler.drain(); }
    static void discoverControls(Fixture f) {
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("discover".equals(f.remote.pending), "controls discover directly without audio MTU negotiation");
        f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
    }
    static void readyControls(Fixture f) {
        connectControls(f); discoverControls(f);
        check(f.remote.pending == f.buttonCcc, "button CCC is the required controls operation");
        f.ack(f.buttonCcc, 0);
        check(f.sink.status().contains("controls only") && f.sink.status().contains("button controls ready"), "truthful controls readiness");
    }
    static void noAudio(Fixture f, BluetoothGatt remote) {
        check(remote.reads.stream().noneMatch(c -> CODEC.equals(c.getUuid())), "controls NEVER read codec");
        check(remote.writes.stream().allMatch(d -> BUTTON.equals(d.getCharacteristic().getUuid())), "controls NEVER write audio CCC");
        check(remote.calls.stream().noneMatch(s -> s.equals("local-notify:" + AUDIO + ":true")), "controls NEVER enable local audio notifications");
        check(field(f.ble, "decoder") == null, "controls NEVER construct a decoder");
        check(field(f.ble, "audioDeadline") == null, "controls NEVER arm audio watchdog");
        check(field(f.ble, "codecCharacteristic") == null && field(f.ble, "audioCharacteristic") == null, "controls never select audio targets");
        check(f.sink.pcm == 0, "controls deliver no PCM");
        check(f.sink.statuses.stream().noneMatch(s -> s.contains("waiting for first audio") || s.contains("local transcription active") || s.contains("checking Omi audio format")), "controls never claim audio setup or capture");
    }
    static void buttonOnly() {
        for (boolean audioService : new boolean[]{false, true}) {
            scenario("button-only setup, no retained read, no audio service needed; audio present=" + audioService);
            Fixture f = new Fixture();
            if (!audioService) f.remote.services.remove(OmiBle.SERVICE);
            connectControls(f); discoverControls(f);
            noAudio(f, f.remote);
            check(f.remote.reads.isEmpty() && f.remote.writes.equals(List.of(f.buttonCcc)), "only button CCC submitted");
            check(Arrays.equals(f.buttonCcc.getValue(), new byte[]{1, 0}), "button notification enable value unchanged");
            f.readButton(); f.notify(f.button, event(1, 8)); f.ack(f.audioCcc, 0);
            check(f.sink.buttons.isEmpty() && f.remote.pending == f.buttonCcc, "retained value/pre-ack notification/unrelated ack cannot activate controls");
            f.ack(f.buttonCcc, 0);
            long generation = f.ble.connectionGeneration();
            byte[] mutable = event(1, 8);
            f.remote.callback.onCharacteristicChanged(f.remote, f.button, mutable); mutable[0] = 2; Handler.drain();
            mutable = event(2, 8); f.button.setValue(mutable);
            f.remote.callback.onCharacteristicChanged(f.remote, f.button); mutable[0] = 1; Handler.drain();
            f.notify(f.button, event(1, 8)); f.notify(f.button, event(1, 8));
            for (long code : new long[]{0, 3, 4, 5, 6, 257, 0xffffffffL}) f.notify(f.button, event(code, 8));
            for (int size : new int[]{0, 1, 4, 7, 9, 12}) f.notify(f.button, event(1, size));
            byte[] reserved = event(1, 8); reserved[4] = 1; f.notify(f.button, reserved);
            f.notify(characteristic(BUTTON, true), event(1, 8)); f.readButton();
            check(f.sink.buttons.equals(List.of(1, 2, 1, 1)), "exact discrete events only; no synthesis, debounce or retained replay");
            check(f.sink.buttonGenerations.equals(List.of(generation, generation, generation, generation)), "each delivery carries original live generation");
            // Even unsolicited codec/CCC/audio callbacks cannot turn this into capture.
            f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0);
            f.codec.setValue(new byte[]{21}); f.remote.callback.onCharacteristicRead(f.remote, f.codec, 0);
            f.ack(f.audioCcc, 0); f.audio(); f.audio();
            int gaps = f.sink.gaps;
            f.ble.readLedBrightness(); f.ble.setLedBrightness(20); Handler.drain();
            Handler.advance(180000);
            check(!f.remote.closed && f.context.adapter.connections == 1 && f.sink.gaps == gaps, "silent idle controls remain connected past audio deadlines");
            noAudio(f, f.remote);
            check(f.remote.reads.isEmpty() && f.remote.commands.isEmpty(), "minimal controls mode never starts optional reads/writes");
            f.ble.stop(); Handler.drain();
        }
    }
    static void requiredButtonFailures() {
        for (String mode : List.of("no-service", "no-characteristic", "no-notify", "no-ccc", "notify-false", "notify-throws", "write-false", "write-throws", "ack-fails", "timeout")) {
            scenario("required controls button failure has no audio fallback: " + mode);
            Fixture f = new Fixture();
            switch (mode) {
                case "no-service": f.remote.services.remove(BUTTON_SERVICE); break;
                case "no-characteristic": f.remote.services.get(BUTTON_SERVICE).chars.clear(); break;
                case "no-notify": f.button.properties = 0; break;
                case "no-ccc": f.button.descriptors.clear(); break;
                case "notify-false": f.remote.rejectNotify.add(BUTTON); break;
                case "notify-throws": f.remote.throwNotify.add(BUTTON); break;
                case "write-false": f.remote.rejectWrite.add(BUTTON); break;
                case "write-throws": f.remote.throwWrite.add(BUTTON); break;
            }
            connectControls(f); discoverControls(f);
            if (mode.equals("ack-fails")) f.ack(f.buttonCcc, 133);
            if (mode.equals("timeout")) {
                Handler.advance(14999); check(!f.remote.closed, "button timeout not early");
                Handler.advance(1);
            }
            BluetoothGatt old = f.remote;
            check(old.closed && old.disconnected, "required controls failure closes GATT before retry");
            check(f.sink.status().contains("button controls unavailable") && f.sink.status().contains("retry"), "missing controls visibly retry without pretending capture");
            f.ack(f.buttonCcc, 0); f.notify(f.button, event(1, 8));
            check(f.sink.buttons.isEmpty(), "late ack/event never revives failed controls");
            noAudio(f, old);
            f.nextRemote(); Handler.advance(1000); discoverControls(f); f.ack(f.buttonCcc, 0);
            f.notify(f.button, event(2, 8));
            check(f.sink.buttons.equals(List.of(2)) && f.context.adapter.connections == 2, "natural retry preserves controls mode");
            noAudio(f, f.remote); f.ble.stop(); Handler.drain();
        }
    }
    static void handovers() {
        for (String direction : List.of("controls-to-capture", "capture-to-controls", "controls-to-controls")) {
            scenario("same-instance close-before-open handover: " + direction);
            Fixture f = new Fixture();
            if (direction.equals("capture-to-controls")) { f.ready(); f.checkAudio("original capture"); }
            else readyControls(f);
            BluetoothGatt old = f.remote; BluetoothGattCharacteristic oldButton = f.button;
            long generation = f.ble.connectionGeneration(); int pcm = f.sink.pcm;
            old.callback.onCharacteristicChanged(old, oldButton, event(1, 8));
            f.nextRemote();
            f.context.adapter.beforeConnect = () -> check(old.closed && old.disconnected, "old GATT closed before replacement native connect");
            if (direction.equals("controls-to-capture")) f.ble.connect(ADDRESS);
            else f.ble.connectControls(ADDRESS);
            check(generation != f.ble.connectionGeneration(), "selection synchronously revokes button generation");
            Handler.drain();
            check(f.sink.buttons.isEmpty(), "pending old button cannot cross mode handover");
            old.callback.onCharacteristicChanged(old, oldButton, event(2, 8));
            old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
            if (direction.equals("controls-to-capture")) {
                f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0); f.checkAudio("capture handover");
                check(f.remote.writes.equals(List.of(f.audioCcc, f.buttonCcc)), "capture retains audio-first subscription");
            } else {
                discoverControls(f); f.ack(f.buttonCcc, 0); Handler.advance(60000);
                check(f.sink.pcm == pcm && field(f.ble, "decoder") == null && field(f.ble, "audioDeadline") == null, "entering controls clears prior decoder/watchdog");
                check(f.remote.reads.isEmpty() && f.remote.writes.equals(List.of(f.buttonCcc)), "replacement controls never touches codec/audio");
            }
            f.notify(f.button, event(2, 8)); check(f.sink.buttons.equals(List.of(2)), "only new connection delivers button");
            f.ble.stop(); Handler.drain();
        }
    }
    static void generationFences() {
        for (String invalidator : List.of("stop", "selection", "native-loss", "timeout")) {
            scenario("downstream queued button token invalidated by " + invalidator);
            Fixture f = new Fixture();
            if (invalidator.equals("timeout")) f.ready(); else readyControls(f);
            f.notify(f.button, event(1, 8));
            long delivered = f.sink.buttonGenerations.get(0);
            check(delivered == f.ble.connectionGeneration(), "queued fresh action initially current");
            if (invalidator.equals("stop")) f.ble.stop();
            else if (invalidator.equals("selection")) f.ble.connectControls("11:22:33:44:55:66");
            else if (invalidator.equals("native-loss")) {
                // Native callback ingress invalidates before BLE worker can drain.
                f.remote.callback.onCharacteristicChanged(f.remote, f.button, event(2, 8));
                f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED);
            } else {
                Handler.advance(30000); // Same-generation capture watchdog closes the native link.
            }
            check(delivered != f.ble.connectionGeneration(), "main-queued old event can be rejected before dispatch");
            if (invalidator.equals("native-loss")) {
                Handler.drain(); check(f.sink.buttons.equals(List.of(1)), "pre-loss worker-queued event invalidated at loss ingress");
                BluetoothGatt old = f.remote; f.nextRemote(); Handler.advance(1000); discoverControls(f); f.ack(f.buttonCcc, 0);
                long live = f.ble.connectionGeneration();
                old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
                check(f.ble.connectionGeneration() == live, "old native-loss callback cannot revoke current generation");
                f.notify(f.button, event(2, 8));
                check(f.sink.buttonGenerations.get(1) == live && live != delivered, "new native GATT gets a fresh usable generation");
            }
            f.ble.stop(); Handler.drain();
        }
        scenario("stop racing listener delivery cannot relabel old event with new generation");
        Fixture f = new Fixture(); readyControls(f);
        long live = f.ble.connectionGeneration(); f.sink.buttonHook = f.ble::stop;
        f.notify(f.button, event(1, 8));
        check(f.sink.buttonGenerations.equals(List.of(live)) && live != f.ble.connectionGeneration(), "immutable callback token survives reentrant Stop");
    }
    static void stopCompletion() {
        for (String phase : List.of("ready", "pending-button", "retry", "new", "queued-connect", "native-errors")) {
            scenario("stop completion follows native close and worker quit request: " + phase);
            Fixture f = new Fixture();
            if (phase.equals("queued-connect")) f.ble.connectControls(ADDRESS);
            else if (!phase.equals("new")) {
                connectControls(f); discoverControls(f);
                if (!phase.equals("pending-button")) f.ack(f.buttonCcc, 0);
                if (phase.equals("retry")) {
                    f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
                }
            }
            if (phase.equals("native-errors")) {
                f.remote.disconnectFailure = new IllegalStateException("fixture disconnect");
                f.remote.closeFailure = new IllegalStateException("fixture close");
            }
            int quits = HandlerThread.quits; boolean hadWorker = field(f.ble, "worker") != null;
            int[] completions = {0};
            f.ble.stop(() -> {
                completions[0]++;
                if (!phase.equals("new") && !phase.equals("queued-connect"))
                    check(f.remote.disconnectAttempts == 1 && f.remote.closeAttempts == 1, "both native teardown calls precede completion (even exceptions)");
                check(field(f.ble, "gatt") == null && field(f.ble, "handler") == null && field(f.ble, "worker") == null, "stopped native/queue ownership released before completion");
                check(HandlerThread.quits == quits + (hadWorker ? 1 : 0), "quit requested before completion; no thread-exit claim");
            });
            Handler.drain(); Handler.advance(180000);
            check(completions[0] == 1, "completion delivered exactly once, no timeout/retry resurrection");
            check(f.context.adapter.connections == (phase.equals("new") || phase.equals("queued-connect") ? 0 : 1), "stop cancels queued connect/retry");
        }
        scenario("multiple Stop completions cannot be swallowed by newer Stop");
        Fixture f = new Fixture(); readyControls(f); List<Integer> completions = new ArrayList<>();
        f.ble.stop(() -> { check(f.remote.closed, "first completion sees native close"); completions.add(1); });
        f.ble.stop(() -> completions.add(2)); Handler.drain();
        check(completions.equals(List.of(1, 2)), "both completion requests run FIFO exactly once");
        check(f.remote.closeAttempts == 1, "repeated Stop does not duplicate native close");

        scenario("completion handover may immediately reconnect without joining BLE worker");
        Fixture handover = new Fixture(); readyControls(handover); BluetoothGatt old = handover.remote;
        handover.nextRemote(); int[] calls = {0};
        handover.context.adapter.beforeConnect = () -> check(old.closed, "new GATT only after completion closes old");
        handover.ble.stop(() -> { calls[0]++; handover.ble.connect(ADDRESS); }); Handler.drain();
        check(calls[0] == 1 && handover.context.adapter.connections == 2, "callback can create capture queue without lock/join deadlock");
        handover.toAudioCcc(false); handover.ack(handover.audioCcc, 0); handover.ack(handover.buttonCcc, 0); handover.checkAudio("callback handover");
        handover.ble.stop(); Handler.drain();

        scenario("later selection cannot cancel a requested native-close completion");
        Fixture race = new Fixture(); readyControls(race); BluetoothGatt prior = race.remote;
        race.nextRemote(); calls[0] = 0;
        race.ble.stop(() -> { calls[0]++; check(prior.closed && race.context.adapter.connections == 1, "barrier precedes queued newer connect"); });
        race.ble.connect(ADDRESS); Handler.drain();
        check(calls[0] == 1 && race.context.adapter.connections == 2, "same FIFO can continue after close barrier");
        race.ble.stop(); Handler.drain();
    }
    static void cancellations() {
        for (String boundary : List.of("connecting", "discovering", "enabling", "ready")) {
            scenario("reentrant controls Stop at " + boundary);
            Fixture f = new Fixture();
            f.sink.statusHook = () -> {
                String status = f.sink.status();
                if ((boundary.equals("connecting") && status.startsWith("Connecting to selected Omi")) ||
                    (boundary.equals("discovering") && status.contains("discovering")) ||
                    (boundary.equals("enabling") && status.contains("enabling button")) ||
                    (boundary.equals("ready") && status.contains("button controls ready"))) f.ble.stop();
            };
            connectControls(f);
            if (!boundary.equals("connecting")) {
                f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
                if (!boundary.equals("discovering")) {
                    f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
                    if (boundary.equals("ready")) f.ack(f.buttonCcc, 0);
                }
            }
            Handler.advance(120000);
            check(f.context.adapter.connections == (boundary.equals("connecting") ? 0 : 1), "reentrant Stop prevents subsequent work/retry");
            check(field(f.ble, "worker") == null, "reentrant controls Stop releases worker");
            noAudio(f, f.remote);
        }
        scenario("invalid controls address closes previous link but never opens a new one");
        Fixture f = new Fixture(); readyControls(f); f.ble.connectControls("bad address"); Handler.drain();
        check(f.remote.closed && f.context.adapter.connections == 1 && f.sink.status().contains("Invalid selected Bluetooth address"), "invalid controls selection preserves validation");
        f.ble.stop(); Handler.drain();
    }
    static void controlsRecovery() {
        for (String stage : List.of("connection", "discovery")) {
            scenario("controls operation deadline closes native link: " + stage);
            Fixture f = new Fixture(); connectControls(f);
            if (stage.equals("discovery")) {
                f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
            }
            Handler.advance(14999); check(!f.remote.closed, "controls deadline not early");
            Handler.advance(1);
            check(f.remote.closed && f.sink.status().contains("timed out; retry in 1 seconds"), "bounded controls operation recovers visibly");
            noAudio(f, f.remote); f.ble.stop(); Handler.drain();
        }
        scenario("controls recovery backoff resets only on fresh valid button, not CCC ack");
        Fixture f = new Fixture(); readyControls(f);
        long[] waits = {1000, 2000, 4000};
        for (long wait : waits) {
            f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
            check(f.sink.status().contains("retry in " + wait / 1000 + " seconds"), "controls preserves recovery debt without fresh events");
            f.nextRemote(); Handler.advance(wait); discoverControls(f); f.ack(f.buttonCcc, 0);
            f.notify(f.button, event(3, 8)); f.readButton();
        }
        f.notify(f.button, event(1, 8));
        f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
        check(f.sink.status().contains("retry in 1 seconds"), "fresh valid controls event proves recovered input");
        noAudio(f, f.remote); f.ble.stop(); Handler.drain();

        scenario("controls survive disabled Bluetooth without ever opening capture");
        f = new Fixture(); f.context.adapter.enabled = false; connectControls(f);
        check(f.context.adapter.connections == 0 && f.sink.status().contains("Bluetooth disabled"), "disabled adapter visible with no native open");
        Handler.advance(1000); f.context.adapter.enabled = true; Handler.advance(2000);
        discoverControls(f); f.ack(f.buttonCcc, 0);
        check(f.context.adapter.connections == 1, "enabled radio retries directly into controls");
        noAudio(f, f.remote); f.ble.stop(); Handler.drain();

        scenario("legacy Listener.onButton(int) still receives exact events through default overload");
        Fixture legacy = new Fixture(); List<Integer> delivered = new ArrayList<>();
        OmiBle ble = new OmiBle(legacy.context, new OmiBle.Listener() {
            public void onDevice(String address, String name) {}
            public void onPcm(short[] samples) { throw new AssertionError("legacy controls PCM"); }
            public void onStatus(String status) {}
            public void onGap() {}
            public void onButton(int event) { delivered.add(event); }
        });
        ble.connectControls(ADDRESS); Handler.drain();
        legacy.remote.callback.onConnectionStateChange(legacy.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        legacy.remote.pending = null; legacy.remote.callback.onServicesDiscovered(legacy.remote, 0); Handler.drain();
        legacy.ack(legacy.buttonCcc, 0); legacy.notify(legacy.button, event(2, 8));
        check(delivered.equals(List.of(2)), "legacy listener callback remains source and behavior compatible");
        ble.stop(); Handler.drain();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int length = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(length > 0, "real encoded payload for unsolicited-audio and capture-handover checks");
        ButtonBleTest.opus = Arrays.copyOf(encoded, length);
        buttonOnly(); requiredButtonFailures(); handovers(); generationFences(); stopCompletion(); cancellations(); controlsRecovery();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " controls-only BLE host scenarios");
        System.out.println("HOST ONLY: production OmiBle/ButtonEvent/Concentus; native-close ordering, not Android thread termination or wearable acceptance");
    }
}
