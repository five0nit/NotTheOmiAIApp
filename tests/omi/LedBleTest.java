package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Actual production client + real codec; not Android/wearable acceptance. */
public final class LedBleTest {
    static final UUID SETTINGS = UUID.fromString("19b10010-e8f2-537e-4f6c-d104768a1214");
    static final UUID LED = UUID.fromString("19b10011-e8f2-537e-4f6c-d104768a1214");
    static BluetoothGattCharacteristic addLed(Fixture f) {
        BluetoothGattService service = new BluetoothGattService(SETTINGS);
        BluetoothGattCharacteristic c = characteristic(LED, false);
        c.properties = BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE;
        service.add(c); f.remote.services.put(SETTINGS, service); return c;
    }
    static BluetoothGattCharacteristic led(Fixture f) { return f.remote.getService(SETTINGS).getCharacteristic(LED); }
    static Fixture ready() { Fixture f = new Fixture(); addLed(f); f.ready(); return f; }
    static void requestRead(Fixture f) { f.ble.readLedBrightness(); Handler.drain(); }
    static void set(Fixture f, int value) { f.ble.setLedBrightness(value); Handler.drain(); }
    static void read(Fixture f, byte[] value, int status, boolean legacy) {
        BluetoothGattCharacteristic c = led(f); f.remote.pending = null;
        if (legacy) { c.setValue(value); f.remote.callback.onCharacteristicRead(f.remote, c, status); }
        else f.remote.callback.onCharacteristicRead(f.remote, c, value, status);
        if (value != null && value.length > 0) value[0] = 99; // Both callbacks must own a snapshot.
        Handler.drain();
    }
    static void known(Fixture f, int value) { requestRead(f); read(f, new byte[]{(byte)value}, 0, false); }
    static void ack(Fixture f, int status) {
        f.remote.pending = null; f.remote.callback.onCharacteristicWrite(f.remote, led(f), status); Handler.drain();
    }
    static Object field(Fixture f, String name) throws Exception {
        java.lang.reflect.Field value = OmiBle.class.getDeclaredField(name); value.setAccessible(true); return value.get(f.ble);
    }
    static void audioUnaffected(Fixture f, int gaps, Object watch, Object decoder, int retry, String label) throws Exception {
        check(!f.remote.closed && !f.remote.disconnected, label + ": no disconnect");
        check(f.sink.gaps == gaps, label + ": no audio gap/recognition flush");
        check(field(f, "audioDeadline") == watch && field(f, "decoder") == decoder, label + ": watchdog and decoder unchanged");
        check(field(f, "retry").equals(retry) && field(f, "retryTask") == null, label + ": retry state unchanged");
    }
    static void basics() throws Exception {
        scenario("initial/disconnected/connection-setup rejection never opens GATT or creates a worker");
        Fixture f = new Fixture();
        check(!f.sink.led().supported && !f.sink.led().busy && f.sink.led().brightness == -1, "initial unknown state");
        requestRead(f); set(f, 50);
        check(f.context.adapter.connections == 0 && field(f, "handler") == null, "LED never starts worker/capture/connection");
        addLed(f); f.connect(); requestRead(f); set(f, 50);
        check(f.remote.reads.isEmpty() && f.remote.commands.isEmpty(), "connecting requests rejected, not queued");
        f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        requestRead(f); set(f, 50);
        check(f.remote.pending == f.buttonCcc && f.remote.reads.equals(List.of(f.codec)), "optional CCC is serialized before LED");
        f.ack(f.buttonCcc, 0);
        check(f.remote.commands.isEmpty() && f.remote.reads.equals(List.of(f.codec)), "no unsolicited LED read/write after discovery");
        set(f, 50); check(f.remote.commands.isEmpty(), "set requires successful explicit read");
        known(f, 40); check(f.sink.led().brightness == 40 && f.sink.led().supported && !f.sink.led().busy, "read enables set with copied result");
        for (int value : new int[]{-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            set(f, value); check(f.remote.commands.isEmpty() && f.sink.led().brightness == 40, "out of range never written " + value);
        }
        f.ble.stop(); Handler.drain();
        check(!f.sink.led().supported && !f.sink.led().busy && f.sink.led().brightness == -1, "stop resets capability and value");
        requestRead(f); set(f, 1); check(field(f, "handler") == null && f.context.adapter.connections == 1, "post-stop LED cannot restart capture");
    }
    static void unsupported() throws Exception {
        for (String mode : List.of("service", "characteristic", "read-only", "write-only", "write-no-response", "wrong-identity")) {
            scenario("unsupported firmware: " + mode);
            Fixture f = ready(); BluetoothGattCharacteristic c = led(f);
            switch (mode) {
                case "service": f.remote.services.remove(SETTINGS); break;
                case "characteristic": f.remote.services.get(SETTINGS).chars.clear(); break;
                case "read-only": c.properties = 2; break;
                case "write-only": c.properties = 8; break;
                case "write-no-response": c.properties = 2 | 4; break;
                case "wrong-identity":
                    BluetoothGattCharacteristic wrong = characteristic(AUDIO, false); wrong.properties = 2 | 8;
                    f.remote.services.get(SETTINGS).chars.put(LED, wrong); break;
            }
            int gaps = f.sink.gaps; Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            requestRead(f); set(f, 30);
            check(!f.sink.led().supported && f.sink.led().brightness == -1, "unsupported reported");
            check(f.remote.commands.isEmpty() && f.remote.reads.equals(List.of(f.codec)), "no guessed read/write");
            audioUnaffected(f, gaps, watch, decoder, 0, mode); f.checkAudio(mode); f.ble.stop(); Handler.drain();
        }
    }
    static void happyAndBusy() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            scenario("single-byte write/readback, busy preservation, audio/button delivery, callback legacy=" + legacy);
            Fixture f = ready(); requestRead(f);
            OmiBle.LedState pending = f.sink.led(); Object timer = field(f, "deadline");
            set(f, 10); requestRead(f); set(f, -1);
            check(f.sink.led() == pending && field(f, "deadline") == timer, "busy requests do not replace in-flight state/deadline");
            f.remote.callback.onCharacteristicRead(f.remote, characteristic(LED, false), new byte[]{88}, 0); Handler.drain();
            check(f.sink.led() == pending && f.remote.pending == led(f), "same UUID different object cannot satisfy read");
            f.checkAudio("LED initial read pending"); f.notify(f.button, event(1, 8));
            check(f.sink.buttons.equals(List.of(1)), "button delivered during read");
            read(f, new byte[]{42}, 0, legacy); check(f.sink.led().brightness == 42, "callback bytes safely copied");
            for (int value : new int[]{0, 100}) {
                int gaps = f.sink.gaps, writes = f.remote.commands.size();
                Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
                set(f, value);
                check(f.remote.commands.size() == writes + 1 && Arrays.equals(f.remote.commands.get(writes), new byte[]{(byte)value}), "one exact byte " + value);
                check(f.remote.commandTargets.get(writes) == led(f) && led(f).writeType == 2, "exact characteristic and acknowledged write type");
                pending = f.sink.led(); set(f, 20); requestRead(f);
                check(f.sink.led() == pending && f.remote.commands.size() == writes + 1, "write not replaced while busy");
                f.remote.callback.onCharacteristicWrite(f.remote, characteristic(LED, false), 0); Handler.drain();
                check(f.remote.pending == led(f) && field(f, "operation").equals(9), "foreign write ack ignored");
                audioUnaffected(f, gaps, watch, decoder, 0, "write pending");
                f.checkAudio("LED write pending"); f.notify(f.button, event(2, 8));
                ack(f, 0);
                check(f.sink.led().busy && f.remote.pending == led(f) && field(f, "operation").equals(10), "ATT success not success until readback");
                f.checkAudio("LED readback pending"); f.notify(f.button, event(1, 8));
                read(f, new byte[]{(byte)value}, 0, legacy);
                check(!f.sink.led().busy && f.sink.led().brightness == value && f.sink.led().message.contains("readback verified"), "exact readback confirms current value");
                check(f.sink.led().message.contains("persistence unverified"), "no flash persistence promise");
            }
            set(f, 66); ack(f, 0); read(f, new byte[]{55}, 0, legacy);
            check(f.sink.led().brightness == 55 && f.sink.led().message.contains("mismatch") && !f.sink.led().message.contains("readback verified"), "mismatch reports observed value, never success");
            int writes = f.remote.commands.size(); Handler.advance(1000);
            check(f.remote.commands.size() == writes, "mismatch never retries write"); f.ble.stop(); Handler.drain();
        }
    }
    static void failures() throws Exception {
        for (String mode : List.of("read-reject", "read-security", "read-runtime", "read-status", "read-null", "read-empty", "read-long", "read-range", "prepare-reject", "write-reject", "write-security", "write-runtime", "write-status", "back-reject", "back-security", "back-runtime", "back-status", "back-invalid")) {
            scenario("optional LED failure without audio disruption: " + mode);
            Fixture f = ready();
            if (!mode.startsWith("read-")) known(f, 50);
            int gaps = f.sink.gaps, retry = (Integer)field(f, "retry");
            Object watch = field(f, "audioDeadline"), decoder = field(f, "decoder");
            if (mode.startsWith("read-")) {
                if (mode.equals("read-reject")) f.remote.rejectRead = true;
                if (mode.equals("read-security")) f.remote.readFailure = new SecurityException("denied");
                if (mode.equals("read-runtime")) f.remote.readFailure = new IllegalStateException("native failure");
                requestRead(f);
                switch (mode) {
                    case "read-status": read(f, new byte[]{50}, 133, false); break;
                    case "read-null": read(f, null, 0, false); break;
                    case "read-empty": read(f, new byte[0], 0, false); break;
                    case "read-long": read(f, new byte[]{50, 0}, 0, false); break;
                    case "read-range": read(f, new byte[]{(byte)255}, 0, false); break;
                }
            } else {
                if (mode.equals("prepare-reject")) led(f).rejectValue = true;
                if (mode.equals("write-reject")) f.remote.rejectCommand = true;
                if (mode.equals("write-security")) f.remote.commandFailure = new SecurityException("denied");
                if (mode.equals("write-runtime")) f.remote.commandFailure = new IllegalStateException("native failure");
                set(f, 60);
                if (mode.equals("write-status")) ack(f, 133);
                if (mode.startsWith("back-")) {
                    if (mode.equals("back-reject")) f.remote.rejectRead = true;
                    if (mode.equals("back-security")) f.remote.readFailure = new SecurityException("denied");
                    if (mode.equals("back-runtime")) f.remote.readFailure = new IllegalStateException("native failure");
                    ack(f, 0);
                    if (mode.equals("back-status")) read(f, new byte[]{60}, 133, false);
                    if (mode.equals("back-invalid")) read(f, new byte[]{101}, 0, false);
                }
            }
            check(!f.sink.led().busy && f.sink.led().brightness == -1, "failure invalidates unconfirmed value");
            int writes = f.remote.commands.size(); set(f, 20);
            check(f.remote.commands.size() == writes, "failure cannot authorize next write without read");
            audioUnaffected(f, gaps, watch, decoder, retry, mode);
            f.checkAudio(mode); f.notify(f.button, event(1, 8));
            check(f.sink.buttons.equals(List.of(1)), "failure preserves buttons");
            if (mode.endsWith("security") || mode.endsWith("runtime")) {
                int reads = f.remote.reads.size(); f.remote.readFailure = f.remote.commandFailure = null;
                requestRead(f); check(f.remote.reads.size() == reads, "uncertain submission quarantines optional lane");
            }
            f.ble.stop(); Handler.drain();
        }
    }
    static void timeoutsAndQuarantine() throws Exception {
        for (int stage : new int[]{8, 9, 10}) {
            scenario("timeout quarantines late callbacks and request B at LED stage " + stage);
            Fixture f = ready();
            if (stage != 8) { known(f, 20); set(f, 80); if (stage == 10) ack(f, 0); }
            else requestRead(f);
            int gaps = f.sink.gaps;
            for (int i = 0; i < 3; i++) { Handler.advance(4000); f.audio(); }
            Handler.advance(2999); check(f.sink.led().busy, "LED deadline not reset by PCM or early");
            Handler.advance(1);
            check(!f.sink.led().busy && !f.sink.led().supported && f.sink.led().message.contains("timed out"), "timeout quarantines until natural reconnect");
            check(!f.remote.closed && f.sink.gaps == gaps && f.context.adapter.connections == 1, "LED timeout doesn't reconnect or flush");
            int reads = f.remote.reads.size(), writes = f.remote.commands.size();
            requestRead(f); set(f, 44);
            check(f.remote.reads.size() == reads && f.remote.commands.size() == writes, "B cannot start while native A may be pending");
            f.remote.pending = null;
            f.remote.callback.onCharacteristicRead(f.remote, led(f), new byte[]{80}, 0);
            f.remote.callback.onCharacteristicWrite(f.remote, led(f), 0); Handler.drain();
            check(f.sink.led().brightness == -1 && !f.sink.led().supported, "late A cannot confirm value or revive lane");
            f.checkAudio("quarantined LED"); f.notify(f.button, event(1, 8));
            check(f.sink.buttons.equals(List.of(1)), "buttons continue under quarantine");
            f.remote.callback.onConnectionStateChange(f.remote, 133, 0); Handler.drain();
            f.nextRemote(); addLed(f); Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
            check(f.remote.commands.isEmpty() && f.remote.reads.equals(List.of(f.codec)), "natural reconnect never replays LED");
            known(f, 23); check(f.sink.led().brightness == 23, "natural reconnect restores explicit read lane"); f.ble.stop(); Handler.drain();
        }
        scenario("optional button timeout cannot leave LED using an occupied ATT lane");
        Fixture f = new Fixture(); addLed(f); f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        Handler.advance(15000); requestRead(f); set(f, 10);
        check(f.remote.pending == f.buttonCcc && f.remote.reads.equals(List.of(f.codec)) && f.remote.commands.isEmpty(), "unknown native CCC completion blocks LED, not audio");
        f.checkAudio("button timeout with LED quarantine"); f.ble.stop(); Handler.drain();
    }
    static void lifecycle() throws Exception {
        for (String mode : List.of("stop", "selection", "reconnect")) {
            scenario("queued and stale LED requests/callbacks suppressed: " + mode);
            Fixture f = ready(); known(f, 30); BluetoothGatt old = f.remote;
            BluetoothGattCharacteristic oldLed = led(f);
            if (mode.equals("reconnect")) old.callback.onConnectionStateChange(old, 133, 0); // queued before command
            f.ble.setLedBrightness(90);
            if (mode.equals("stop")) f.ble.stop();
            if (mode.equals("selection")) { f.nextRemote(); addLed(f); f.ble.connect("11:22:33:44:55:66"); }
            Handler.drain(); check(old.commands.isEmpty(), "queued write cannot cross " + mode);
            if (mode.equals("stop")) {
                check(field(f, "handler") == null, "stop retires worker");
            } else {
                if (mode.equals("reconnect")) { f.nextRemote(); addLed(f); Handler.advance(1000); }
                f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
                set(f, 40); check(f.remote.commands.isEmpty(), "old read cannot authorize new connection write");
            }
            int states = f.sink.ledStates.size();
            old.callback.onCharacteristicWrite(old, oldLed, 0);
            old.callback.onCharacteristicRead(old, oldLed, new byte[]{90}, 0); Handler.drain();
            check(f.sink.ledStates.size() == states, "stale callbacks never report success");
            if (!mode.equals("stop")) check(f.remote.commands.isEmpty() && f.remote.reads.equals(List.of(f.codec)), "no replay on new connection");
            f.ble.stop(); Handler.drain();
        }
        scenario("in-flight write Stop/new selection invalidates queued acknowledgement, never reads back");
        for (boolean stop : new boolean[]{true, false}) {
            Fixture f = ready(); known(f, 20); set(f, 70); BluetoothGatt old = f.remote;
            old.pending = null; old.callback.onCharacteristicWrite(old, led(f), 0);
            if (stop) f.ble.stop();
            else { f.nextRemote(); addLed(f); f.ble.connect("11:22:33:44:55:66"); }
            Handler.drain(); check(old.reads.size() == 2, "stop/selection suppresses write callback readback"); f.ble.stop(); Handler.drain();
        }
        scenario("busy-at-entry command isn't delayed until a queued read completes");
        Fixture f = ready(); requestRead(f); f.remote.pending = null;
        f.remote.callback.onCharacteristicRead(f.remote, led(f), new byte[]{30}, 0);
        f.ble.setLedBrightness(40); Handler.drain();
        check(f.remote.commands.isEmpty(), "busy request not replayed after read completion"); f.ble.stop(); Handler.drain();

        scenario("connection identity changes after successful read require a fresh read");
        f = ready(); known(f, 20); addLed(f); set(f, 50);
        check(f.remote.commands.isEmpty() && f.sink.led().brightness == -1, "UUID alone cannot authorize replaced characteristic"); f.ble.stop(); Handler.drain();

        scenario("Stop inside LED busy listener prevents native submission");
        for (boolean write : new boolean[]{false, true}) {
            Fixture x = ready(); if (write) known(x, 20);
            x.sink.ledHook = () -> { if (x.sink.led().busy) x.ble.stop(); };
            if (write) set(x, 60); else requestRead(x);
            check(x.remote.commands.isEmpty() && x.remote.reads.size() == (write ? 2 : 1), "reentrant stop checked after listener");
            check(x.remote.closed && field(x, "handler") == null, "stopped worker closes cleanly");
        }
    }
    static void watchdogAndRetry() throws Exception {
        scenario("LED results and requests do not extend startup audio watchdog or reset retry budget");
        Fixture f = ready(); f.remote.callback.onConnectionStateChange(f.remote, 133, 0); Handler.drain();
        f.nextRemote(); addLed(f); Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
        check(field(f, "retry").equals(1), "fixture has unrecovered retry debt");
        Object watch = field(f, "audioDeadline"); known(f, 25); set(f, 35); ack(f, 0); read(f, new byte[]{35}, 0, false);
        check(field(f, "retry").equals(1) && field(f, "audioDeadline") == watch, "LED success is not decoded PCM proof");
        Handler.advance(29000); requestRead(f); read(f, new byte[]{35}, 0, false);
        Handler.advance(999); check(!f.remote.closed, "no early startup watchdog");
        Handler.advance(1); check(f.remote.closed && f.sink.status().contains("retry in 2 seconds"), "startup watchdog/backoff survives LED activity"); f.ble.stop(); Handler.drain();

        scenario("established 15-second audio watchdog survives pending LED read");
        f = ready(); f.checkAudio("established stream"); Handler.advance(10000); requestRead(f);
        Handler.advance(4999); check(!f.remote.closed, "no early established timeout");
        Handler.advance(1); check(f.remote.closed && f.sink.status().contains("15 seconds"), "LED cannot hide missing PCM");
        f.ble.stop(); Handler.drain();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int size = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(size > 0, "real Opus encoded fixture"); ButtonBleTest.opus = Arrays.copyOf(encoded, size);
        basics(); unsupported(); happyAndBusy(); failures(); timeoutsAndQuarantine(); lifecycle(); watchdogAndRetry();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " LED/BLE host scenarios");
        System.out.println("HOST ONLY: actual OmiBle + Concentus; shared single-flight Android/GATT/Handler fakes; no hardware/persistence acceptance");
    }
}
