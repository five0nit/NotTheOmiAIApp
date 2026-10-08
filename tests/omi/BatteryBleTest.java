package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;
import static app.nottheomi.ai.LedBleTest.field;
import static app.nottheomi.ai.LedBleTest.audioUnaffected;

/** Actual client + codec under shared deterministic Android/GATT stand-ins. */
public final class BatteryBleTest {
    static final UUID BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static BluetoothGattCharacteristic addBattery(Fixture f) {
        BluetoothGattService service = new BluetoothGattService(BAS);
        BluetoothGattCharacteristic c = characteristic(LEVEL, false);
        c.properties = BluetoothGattCharacteristic.PROPERTY_READ;
        service.add(c); f.remote.services.put(BAS, service); return c;
    }
    static BluetoothGattCharacteristic battery(Fixture f) { return f.remote.getService(BAS).getCharacteristic(LEVEL); }
    static Fixture ready() { Fixture f = new Fixture(); addBattery(f); LedBleTest.addLed(f); f.ready(); return f; }
    static void read(Fixture f, byte[] value, int status, boolean legacy) {
        BluetoothGattCharacteristic c = battery(f); f.remote.pending = null;
        if (legacy) { c.setValue(value); f.remote.callback.onCharacteristicRead(f.remote, c, status); }
        else f.remote.callback.onCharacteristicRead(f.remote, c, value, status);
        if (value != null && value.length > 0) value[0] = 77;
        Handler.drain();
    }
    static void stop(Fixture f) { f.ble.stop(); Handler.drain(); }
    static void liveAdvance(Fixture f, long ms) {
        // Real decoded PCM is the only thing keeping the audio watchdog alive.
        f.audio(); f.audio();
        while (ms > 0) { long step = Math.min(4000L, ms); Handler.advance(step); f.audio(); ms -= step; }
    }
    static void basicsAndSetup() throws Exception {
        scenario("unknown constructor/idle stop: telemetry never starts a worker, scan or connection");
        Fixture f = new Fixture(); addBattery(f);
        check(f.sink.batteries.equals(List.of(-1)), "constructor publishes unknown");
        Handler.advance(120000); stop(f);
        check(f.sink.batteries.equals(List.of(-1, -1)), "idle Stop republishes unknown");
        check(f.context.adapter.connections == 0 && field(f, "handler") == null && f.remote.calls.isEmpty(), "battery cannot start capture");

        scenario("battery starts only after required audio and optional button setup, with 1s idle grace");
        f = new Fixture(); addBattery(f); f.connect();
        Handler.advance(1000); check(f.remote.reads.isEmpty(), "no connecting read");
        f.toAudioCcc(false); Handler.advance(1000);
        check(f.remote.pending == f.audioCcc && f.remote.reads.equals(List.of(f.codec)), "no battery during audio CCC");
        f.ack(f.audioCcc, 0); Handler.advance(1000);
        check(f.remote.pending == f.buttonCcc && field(f, "batteryPoll") == null, "no poll until optional CCC resolved");
        f.ack(f.buttonCcc, 0); Handler.advance(999);
        check(f.remote.reads.equals(List.of(f.codec)), "idle grace not early");
        int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
        Handler.advance(1);
        check(f.remote.pending == battery(f) && field(f, "operation").equals(11), "one standard BAS read with dedicated op");
        check(f.remote.reads.equals(List.of(f.codec, battery(f))), "exact battery target, never retained button");
        audioUnaffected(f, gaps, watch, decoder, 0, "initial battery read");
        read(f, new byte[]{63}, 0, false);
        check(f.sink.battery() == 63 && field(f, "deadline") == null, "reported percentage completes own deadline");
        check(f.remote.commands.isEmpty() && f.remote.writes.equals(List.of(f.audioCcc, f.buttonCcc)), "no battery writes/CCCs");
        liveAdvance(f, 59999); check(f.remote.reads.size() == 2, "refresh not early");
        Handler.advance(1); check(f.remote.reads.size() == 3 && f.remote.pending == battery(f), "bounded 60s refresh on same GATT");
        check(f.context.adapter.connections == 1, "poll never reconnects"); stop(f);
        int reads = f.remote.reads.size(); Handler.advance(180000);
        check(f.remote.reads.size() == reads && field(f, "batteryPoll") == null && f.sink.battery() == -1, "stop cancels all polls and clears value");

        for (String mode : List.of("absent", "write-reject", "status-failure")) {
            scenario("battery follows completed optional button fallback: " + mode);
            f = new Fixture(); addBattery(f);
            if (mode.equals("absent")) f.remote.services.remove(BUTTON_SERVICE);
            if (mode.equals("write-reject")) f.remote.rejectWrite.add(BUTTON);
            f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
            if (mode.equals("status-failure")) f.ack(f.buttonCcc, 133);
            Handler.advance(1000); check(f.remote.pending == battery(f), "known completed fallback permits battery");
            read(f, new byte[]{25}, 0, false); check(f.sink.battery() == 25 && !f.remote.closed, "fallback remains connected"); stop(f);
        }
    }
    static void unsupported() throws Exception {
        for (String mode : List.of("no-service", "no-characteristic", "notify-only", "write-only", "no-properties", "wrong-characteristic", "wrong-service")) {
            scenario("BAS unavailable without audio effects: " + mode);
            Fixture f = ready();
            switch (mode) {
                case "no-service": f.remote.services.remove(BAS); break;
                case "no-characteristic": f.remote.services.get(BAS).chars.clear(); break;
                case "notify-only": battery(f).properties = 16; break;
                case "write-only": battery(f).properties = 8; break;
                case "no-properties": battery(f).properties = 0; break;
                case "wrong-characteristic":
                    BluetoothGattCharacteristic wrong = characteristic(AUDIO, false); wrong.properties = 2;
                    f.remote.services.get(BAS).chars.put(LEVEL, wrong); break;
                case "wrong-service":
                    BluetoothGattService service = new BluetoothGattService(OmiBle.SERVICE); service.add(battery(f));
                    f.remote.services.put(BAS, service); break;
            }
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            Handler.advance(1000);
            check(f.remote.reads.equals(List.of(f.codec)) && f.sink.battery() == -1, "only verified readable BAS may be used");
            audioUnaffected(f, gaps, watch, decoder, 0, mode);
            LedBleTest.known(f, 41); check(f.sink.led().brightness == 41, "unsupported BAS does not quarantine LED");
            f.checkAudio(mode); stop(f);
        }
    }
    static void valuesAndIdentity() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            for (int value : new int[]{0, 1, 50, 99, 100, 101, 127, 128, 255}) {
                scenario("strict unsigned one-byte percentage " + value + ", legacy=" + legacy);
                Fixture f = ready(); Handler.advance(1000);
                int states = f.sink.batteries.size();
                f.remote.callback.onCharacteristicRead(f.remote, characteristic(LEVEL, false), new byte[]{20}, 0);
                f.notify(battery(f), new byte[]{20}); Handler.drain();
                check(f.sink.batteries.size() == states && f.remote.pending == battery(f), "same UUID foreign object and unsolicited notification ignored");
                int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
                read(f, new byte[]{(byte)value}, 0, legacy);
                check(f.sink.battery() == (value <= 100 ? value : -1), "exact range and callback snapshot");
                audioUnaffected(f, gaps, watch, decoder, 0, "BAS result");
                states = f.sink.batteries.size();
                f.remote.callback.onCharacteristicRead(f.remote, battery(f), new byte[]{25}, 0); Handler.drain();
                check(f.sink.batteries.size() == states, "duplicate/unsolicited read not republished");
                stop(f);
            }
        }
        for (String mode : List.of("null", "empty", "long", "status", "read-reject", "changed-target", "lost-read-property")) {
            scenario("known read failure clears battery only, recovers on next interval: " + mode);
            Fixture f = ready(); Handler.advance(1000); read(f, new byte[]{90}, 0, false);
            if (mode.equals("read-reject")) f.remote.rejectRead = true;
            liveAdvance(f, 60000);
            Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder"); int gaps = f.sink.gaps;
            switch (mode) {
                case "null": read(f, null, 0, false); break;
                case "empty": read(f, new byte[0], 0, true); break;
                case "long": read(f, new byte[]{80, 0}, 0, false); break;
                case "status": read(f, new byte[]{80}, 133, false); break;
                case "lost-read-property": battery(f).properties = 16; read(f, new byte[]{80}, 0, false); break;
                case "changed-target":
                    BluetoothGattCharacteristic old = battery(f); addBattery(f); f.remote.pending = null;
                    f.remote.callback.onCharacteristicRead(f.remote, old, new byte[]{80}, 0); Handler.drain(); break;
            }
            check(f.sink.battery() == -1 && field(f, "operation").equals(6), "bad result invalidates prior known value");
            audioUnaffected(f, gaps, watch, decoder, 0, mode);
            f.remote.rejectRead = false; battery(f).properties = 2;
            LedBleTest.known(f, 55); check(f.sink.led().brightness == 55, "known failure releases optional lane");
            liveAdvance(f, 60000); check(f.remote.pending == battery(f), "retry only at next bounded poll");
            read(f, new byte[]{75}, 0, false); check(f.sink.battery() == 75, "future ordinary refresh recovers"); stop(f);
        }
    }
    static void coexistence() throws Exception {
        for (int stage : new int[]{8, 9, 10}) {
            scenario("busy LED stage " + stage + " skips battery without altering its deadline/completion");
            Fixture f = ready();
            if (stage == 8) LedBleTest.requestRead(f);
            else { LedBleTest.known(f, 20); LedBleTest.set(f, 60); if (stage == 10) LedBleTest.ack(f, 0); }
            int reads = f.remote.reads.size(); Object timer = field(f, "deadline"); OmiBle.LedState ledState = f.sink.led();
            Handler.advance(1000);
            check(f.remote.reads.size() == reads && field(f, "deadline") == timer && f.sink.led() == ledState, "poll cannot touch busy LED");
            if (stage == 9) LedBleTest.ack(f, 0);
            LedBleTest.read(f, new byte[]{60}, 0, false);
            check(f.sink.led().brightness == 60 && !f.sink.led().busy, "LED readback completes without waiting for poll");
            reads = f.remote.reads.size(); liveAdvance(f, 59999);
            check(f.remote.reads.size() == reads, "skipped poll not retried in tight loop");
            Handler.advance(1); check(f.remote.pending == battery(f), "next interval can use idle lane");
            timer = field(f, "deadline"); reads = f.remote.reads.size(); int writes = f.remote.commands.size();
            LedBleTest.requestRead(f); LedBleTest.set(f, 30);
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes && field(f, "deadline") == timer, "LED while battery busy cannot overlap or replay");
            f.checkAudio("BAS pending"); f.notify(f.button, event(1, 8));
            check(f.sink.buttons.equals(List.of(1)), "button notifications still flow during BAS");
            read(f, new byte[]{47}, 0, false);
            check(f.remote.commands.size() == writes && f.sink.battery() == 47, "BAS completion does not replay rejected LED write"); stop(f);
        }
    }
    static void quarantine() throws Exception {
        for (String mode : List.of("battery-timeout", "battery-runtime", "battery-after-submit", "battery-security", "led-read-timeout", "led-write-timeout", "led-back-timeout", "led-runtime", "button-timeout", "button-runtime")) {
            scenario("uncertain shared ATT lane stays quarantined until natural reconnect: " + mode);
            Fixture f = new Fixture(); addBattery(f); LedBleTest.addLed(f);
            if (mode.equals("battery-after-submit")) {
                BluetoothGatt uncertain = new BluetoothGatt() {
                    @Override public boolean readCharacteristic(BluetoothGattCharacteristic c) {
                        boolean accepted = super.readCharacteristic(c);
                        if (LEVEL.equals(c.getUuid())) throw new IllegalStateException("failure after native submission");
                        return accepted;
                    }
                };
                uncertain.services.putAll(f.remote.services); f.remote = uncertain; f.context.adapter.next = uncertain;
            }
            f.connect(); f.toAudioCcc(false);
            if (mode.equals("button-runtime")) f.remote.throwWrite.add(BUTTON);
            f.ack(f.audioCcc, 0);
            if (!mode.startsWith("button-")) f.ack(f.buttonCcc, 0);
            if (mode.startsWith("led-")) {
                if (mode.equals("led-runtime")) f.remote.readFailure = new IllegalStateException("uncertain native submit");
                if (mode.equals("led-write-timeout") || mode.equals("led-back-timeout")) {
                    LedBleTest.known(f, 20); LedBleTest.set(f, 60);
                    if (mode.equals("led-back-timeout")) LedBleTest.ack(f, 0);
                } else LedBleTest.requestRead(f);
            }
            if (mode.equals("battery-runtime")) f.remote.readFailure = new IllegalStateException("uncertain native submit");
            if (mode.equals("battery-security")) f.remote.readFailure = new SecurityException("permission lost");
            int gaps = f.sink.gaps;
            if (mode.startsWith("battery-")) Handler.advance(1000);
            if (mode.endsWith("timeout")) {
                liveAdvance(f, 14999);
                check(!((Boolean)field(f, "ledQuarantined")), "optional deadline not early/reset by PCM");
                Handler.advance(1);
            }
            check((Boolean)field(f, "ledQuarantined") && field(f, "batteryPoll") == null && f.sink.battery() == -1, "quarantine cancels telemetry and invalidates value");
            check(!f.remote.closed && f.sink.gaps == gaps && f.context.adapter.connections == 1, "uncertain optional result never disconnects/gaps/retries");
            int reads = f.remote.reads.size(), writes = f.remote.commands.size();
            if (mode.equals("battery-after-submit")) check(f.remote.pending == battery(f), "uncertain native read is still outstanding");
            LedBleTest.requestRead(f); LedBleTest.set(f, 10);
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes, "quarantine blocks ATT while native request may still be pending");
            f.remote.readFailure = null; f.remote.pending = null;
            f.remote.callback.onCharacteristicRead(f.remote, battery(f), new byte[]{80}, 0);
            f.remote.callback.onCharacteristicRead(f.remote, LedBleTest.led(f), new byte[]{60}, 0);
            f.remote.callback.onCharacteristicWrite(f.remote, LedBleTest.led(f), 0);
            f.remote.callback.onDescriptorWrite(f.remote, f.buttonCcc, 0); Handler.drain();
            LedBleTest.requestRead(f); LedBleTest.set(f, 10); liveAdvance(f, 120000);
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes && f.sink.battery() == -1, "late callbacks cannot revive either optional lane");
            f.remote.callback.onConnectionStateChange(f.remote, 133, 0); Handler.drain();
            f.nextRemote(); addBattery(f); LedBleTest.addLed(f); Handler.advance(1000);
            f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
            check(!((Boolean)field(f, "ledQuarantined")) && f.sink.battery() == -1, "new GATT resets optional capability, not old value");
            Handler.advance(1000); read(f, new byte[]{38}, 0, false); LedBleTest.known(f, 28);
            check(f.sink.battery() == 38 && f.sink.led().brightness == 28, "natural reconnect restores independent fresh reads"); stop(f);
        }
    }
    static void lifecycleAndReentrancy() throws Exception {
        for (String mode : List.of("stop", "selection", "reconnect")) {
            scenario("stale callbacks and scheduled poll cannot cross " + mode);
            Fixture f = ready(); Handler.advance(1000); read(f, new byte[]{84}, 0, false);
            BluetoothGatt old = f.remote; BluetoothGattCharacteristic oldBattery = battery(f);
            Runnable oldPoll = (Runnable)field(f, "batteryPoll");
            // Queue an unsolicited result immediately before invalidation.
            old.callback.onCharacteristicRead(old, oldBattery, new byte[]{85}, 0);
            if (mode.equals("stop")) f.ble.stop();
            else if (mode.equals("selection")) { f.nextRemote(); addBattery(f); f.ble.connect("11:22:33:44:55:66"); }
            else old.callback.onConnectionStateChange(old, 133, 0);
            Handler.drain();
            check(f.sink.battery() == -1 && old.closed, "lifecycle clears observed percentage");
            if (!mode.equals("stop")) {
                if (mode.equals("reconnect")) { f.nextRemote(); addBattery(f); Handler.advance(1000); }
                f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
            }
            int states = f.sink.batteries.size(), reads = old.reads.size(); oldPoll.run();
            old.callback.onCharacteristicRead(old, oldBattery, new byte[]{90}, 0); Handler.drain();
            check(f.sink.batteries.size() == states && old.reads.size() == reads, "old read/poll ignored by exact GATT and timer identity");
            if (mode.equals("stop")) check(field(f, "handler") == null && f.context.adapter.connections == 1, "late callbacks cannot recreate worker");
            else { Handler.advance(1000); read(f, new byte[]{35}, 0, false); check(f.sink.battery() == 35, "only fresh GATT result displayed"); }
            stop(f);
        }
        scenario("Stop invalidates already-queued in-flight battery result and pending poll before dispatch");
        Fixture f = ready(); Handler.advance(1000); f.remote.pending = null;
        f.remote.callback.onCharacteristicRead(f.remote, battery(f), new byte[]{88}, 0); stop(f);
        check(!f.sink.batteries.contains(88) && f.sink.battery() == -1 && field(f, "batteryPoll") == null, "queued read suppressed by Stop epoch");
        f = ready(); f.ble.stop(); Handler.advance(1000);
        check(f.remote.reads.equals(List.of(f.codec)), "due poll cannot submit after synchronous Stop invalidation");

        for (boolean scan : new boolean[]{false, true}) {
            scenario("unknown-state listener Stop invalidates new selection before native submission, scan=" + scan);
            Fixture x = new Fixture(); addBattery(x);
            x.sink.batteryHook = () -> { x.sink.batteryHook = null; x.ble.stop(); };
            if (scan) x.ble.scan(); else x.ble.connect("AA:BB:CC:DD:EE:FF");
            Handler.drain(); Handler.advance(60000);
            check(x.context.adapter.connections == 0 && x.remote.calls.isEmpty() && field(x, "handler") == null, "unknown callback cannot start capture after reentrant Stop");
            check(x.sink.statuses.isEmpty(), "cancelled selection never starts a scan/connection stage");
        }

        for (String stage : List.of("button-enabling", "button-ready", "button-unavailable", "battery-result", "unknown")) {
            scenario("reentrant listener Stop is checked before follow-up native work: " + stage);
            Fixture x = new Fixture(); addBattery(x); x.connect(); x.toAudioCcc(false);
            if (stage.equals("button-unavailable")) x.remote.services.remove(BUTTON_SERVICE);
            x.sink.statusHook = () -> {
                String s = x.sink.status();
                if ((stage.equals("button-enabling") && s.contains("enabling button controls")) ||
                    (stage.equals("button-ready") && s.contains("button controls ready")) ||
                    (stage.equals("button-unavailable") && s.contains("button controls unavailable"))) {
                    x.sink.statusHook = null; x.ble.stop();
                }
            };
            x.ack(x.audioCcc, 0);
            if (!x.remote.closed) x.ack(x.buttonCcc, 0);
            if (stage.equals("battery-result") || stage.equals("unknown")) {
                x.sink.batteryHook = () -> { x.sink.batteryHook = null; x.ble.stop(); };
                if (stage.equals("unknown")) x.remote.services.remove(BAS);
                Handler.advance(1000);
                if (stage.equals("battery-result")) read(x, new byte[]{44}, 0, false);
            }
            Handler.advance(120000);
            check(x.remote.closed && field(x, "handler") == null && x.context.adapter.connections == 1, "reentrant Stop retires worker without connection restart");
            check(x.remote.reads.size() == (stage.equals("battery-result") ? 2 : 1), "no read after reentrant stop");
            if (stage.equals("button-enabling")) check(x.remote.writes.equals(List.of(x.audioCcc)), "button native submit rechecked after listener");
        }
    }
    static void watchdogAndRetry() throws Exception {
        scenario("battery success/failure do not reset startup watchdog or retry debt");
        Fixture f = ready(); f.remote.callback.onConnectionStateChange(f.remote, 133, 0); Handler.drain();
        f.nextRemote(); addBattery(f); Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
        Object watch = field(f, "audioDeadline"); Handler.advance(1000); read(f, new byte[]{100}, 0, false);
        check(field(f, "retry").equals(1) && field(f, "audioDeadline") == watch, "battery is not PCM proof");
        Handler.advance(28999); check(!f.remote.closed, "startup watchdog not early");
        Handler.advance(1); check(f.remote.closed && f.sink.status().contains("retry in 2 seconds") && f.sink.battery() == -1, "unchanged startup timeout clears battery and preserves backoff"); stop(f);

        scenario("pending battery cannot postpone established 15s audio watchdog");
        f = ready(); f.checkAudio("established stream"); Handler.advance(1000);
        check(f.remote.pending == battery(f), "battery outstanding during silent PCM period");
        Handler.advance(13999); check(!f.remote.closed, "established deadline not early");
        Handler.advance(1); check(f.remote.closed && f.sink.status().contains("15 seconds") && field(f, "batteryPoll") == null, "audio watchdog wins and cancels optional work"); stop(f);
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int size = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(size > 0, "real Opus encoded fixture"); ButtonBleTest.opus = Arrays.copyOf(encoded, size);
        basicsAndSetup(); unsupported(); valuesAndIdentity(); coexistence(); quarantine(); lifecycleAndReentrancy(); watchdogAndRetry();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " battery/BLE host scenarios");
        System.out.println("HOST ONLY: actual OmiBle + Concentus; shared single-flight Android/GATT/Handler fakes; no wearable battery accuracy/runtime acceptance");
    }
}
