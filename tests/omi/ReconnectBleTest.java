package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Host-only state transitions through the actual production OmiBle callbacks. */
public final class ReconnectBleTest {
    static void drop(Fixture f) {
        f.remote.callback.onConnectionStateChange(f.remote, 133, BluetoothProfile.STATE_DISCONNECTED);
        Handler.drain();
    }
    static void delay(Fixture f, long ms) {
        int connections = f.context.adapter.connections;
        check(f.sink.status().contains("retry in " + ms / 1000 + " seconds"), "exact backoff status " + ms);
        check(!f.sink.status().contains("restart to retry"), "recoverable status must not stop CaptureService");
        f.nextRemote();
        Handler.advance(ms - 1);
        check(f.context.adapter.connections == connections, "no early reconnect at " + ms);
        Handler.advance(1);
        check(f.context.adapter.connections == connections + 1, "exactly one reconnect at " + ms);
    }
    static void finish(Fixture f) {
        f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
    }
    static Runnable retryTask(Fixture f) throws Exception {
        java.lang.reflect.Field field = OmiBle.class.getDeclaredField("retryTask");
        field.setAccessible(true); return (Runnable) field.get(f.ble);
    }
    static void persistent() {
        scenario("ten fast recovery retries then battery-bounded persistent retries");
        Fixture f = new Fixture(); f.connect();
        long[] waits = {1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 30000, 30000};
        for (long wait : waits) {
            BluetoothGatt old = f.remote; drop(f);
            check(old.closed && old.disconnected, "failed transport fully closed");
            int statuses = f.sink.statuses.size(), gaps = f.sink.gaps;
            old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED);
            old.callback.onServicesDiscovered(old, 133); Handler.drain();
            check(f.sink.statuses.size() == statuses && f.sink.gaps == gaps, "duplicate failure cannot schedule another retry");
            delay(f, wait);
        }
        finish(f); f.checkAudio("recovery after twelve failures");
        check(f.context.adapter.connections == 13, "no hidden reconnect attempt limit");
        check(f.sink.statuses.stream().noneMatch(s -> s.contains("restart to retry")), "never fatal during transient failures");
        for (int i = 0; i < 3; i++) {
            drop(f); delay(f, 1000); finish(f); f.checkAudio("repeated recovered dropout " + i);
        }
        f.ble.stop();
    }
    static void onlyDecodedResets() {
        scenario("GATT readiness, button events and first buffered frame do not reset backoff");
        Fixture f = new Fixture(); f.connect();
        drop(f); delay(f, 1000); finish(f);
        f.notify(f.button, event(1, 8)); f.audio();
        check(f.sink.pcm == 0, "first frame only buffered, not decoded");
        drop(f); delay(f, 2000); finish(f);
        f.notify(f.audio, new byte[]{0, 0, 0});
        check(f.sink.pcm == 0, "malformed packet not decoded");
        drop(f); delay(f, 4000); finish(f);
        f.audio(); f.audio(); check(f.sink.pcm > 0, "actual Concentus decode successful");
        drop(f); delay(f, 1000); finish(f);
        drop(f); delay(f, 2000); // A connection alone is not a recovered stream.
        f.ble.stop();
    }
    static void timeouts() {
        for (int stage = 1; stage <= 5; stage++) {
            scenario("operation timeout reconnects at stage " + stage);
            Fixture f = new Fixture(); f.connect();
            if (stage == 4) f.toCodec();
            else if (stage == 5) f.toAudioCcc(false);
            else if (stage >= 2) {
                f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
                if (stage == 3) {
                    f.remote.pending = null;
                    f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
                }
            }
            Handler.advance(14999); check(!f.remote.closed, "no early operation timeout");
            if (stage == 2) {
                // Retain the uncertain native MTU operation: timeout cannot
                // authorize an overlapping discovery on this connection.
                Handler.advance(1);
                check(f.remote.closed && f.sink.status().contains("Omi MTU negotiation timed out"), "uncertain MTU closes before retry");
                check(!f.remote.calls.contains("discover"), "MTU timeout never overlaps discovery");
                f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
                check(!f.remote.calls.contains("discover"), "late MTU callback cannot revive closed connection");
            } else {
                Handler.advance(1);
                String namedStage = new String[]{"", "Omi connection", "Bluetooth setup", "Omi service discovery", "Omi audio format check", "Omi audio subscription"}[stage];
                check(f.remote.closed && f.sink.status().contains(namedStage + " timed out"), "named 15s operation deadline");
            }
            delay(f, 1000); f.ble.stop();
        }
        scenario("stalled audio reconnects; malformed and duplicate traffic cannot hide stall");
        for (String mode : List.of("silent", "malformed", "duplicate", "invalid-opus")) {
            Fixture f = new Fixture(); f.ready();
            if (mode.equals("duplicate")) f.audio();
            if (mode.equals("invalid-opus")) f.notify(f.audio, new byte[]{0, 0, 0, 3});
            for (int i = 0; i < 3; i++) {
                Handler.advance(4000);
                if (mode.equals("malformed")) f.notify(f.audio, new byte[]{0, 0, 0});
                if (mode.equals("duplicate")) {
                    f.seq = 0; f.audio();
                }
                if (mode.equals("invalid-opus")) f.notify(f.audio, new byte[]{(byte)(i + 1), 0, 0, 3});
                f.notify(f.button, event(1, 8));
            }
            check(f.sink.pcm == 0, "no successful decode in stalled fixture " + mode);
            Handler.advance(17999); check(!f.remote.closed, "startup watchdog exact before expiry " + mode);
            Handler.advance(1); check(f.remote.closed && f.sink.status().contains("No Omi audio"), "unstarted stream closes " + mode);
            delay(f, 1000); f.ble.stop();
        }
    }
    static void startupAndEstablishedAudio() {
        scenario("first decoded audio can arrive after 15s but established audio retains 15s watchdog");
        Fixture f = new Fixture(); f.ready();
        Handler.advance(20000);
        check(!f.remote.closed, "startup grace preserves ready GATT beyond old 15s cutoff");
        f.audio(); f.audio(); check(f.sink.pcm > 0, "late initial PCM decodes without reconnect");
        int pcm = f.sink.pcm;
        Handler.advance(14999); check(!f.remote.closed, "established stream not closed early");
        Handler.advance(1);
        check(f.remote.closed && f.sink.status().contains("15 seconds"), "established audio timeout unchanged");
        check(f.sink.pcm == pcm, "watchdog did not synthesize PCM");
        delay(f, 1000); f.ble.stop();

        scenario("first buffered frame near startup expiry cannot buy another startup window");
        f = new Fixture(); f.ready(); Handler.advance(29999); f.audio();
        check(!f.remote.closed && f.sink.pcm == 0, "one buffered frame does not prove PCM");
        Handler.advance(1);
        check(f.remote.closed && f.sink.status().contains("30 seconds"), "startup allowance stays bounded from subscription");
        f.ble.stop();

        scenario("first-audio startup deadline cannot survive Stop");
        f = new Fixture(); f.ready(); f.ble.stop(); Handler.drain();
        int statuses = f.sink.statuses.size(), connections = f.context.adapter.connections;
        Handler.advance(120000);
        check(f.sink.statuses.size() == statuses && f.context.adapter.connections == connections,
            "stopped startup deadline cannot reconnect or publish");
    }
    static void observedTimeoutSequence() {
        scenario("observed link status 8 followed by nine 147 timeouts preserves codes and fast retry budget");
        Fixture f = new Fixture(); f.ready(); f.checkAudio("initial real decoded fixture audio");
        long[] waits = {1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000, 5000};
        for (int i = 0; i < waits.length; i++) {
            int code = i == 0 ? 8 : 147;
            if (i > 0) Handler.advance(10000); // Measured platform attempt duration, not app backoff.
            f.remote.callback.onConnectionStateChange(f.remote, code, BluetoothProfile.STATE_DISCONNECTED);
            Handler.drain();
            check(f.sink.status().contains("Bluetooth status " + code), "numeric failure retained " + code);
            delay(f, waits[i]);
        }
        finish(f); f.checkAudio("recovered after observed timeout sequence");
        drop(f); delay(f, 1000); f.ble.stop();
    }
    static void radioOffOn() {
        scenario("Bluetooth disabled at selection survives beyond five retries until enabled");
        Fixture f = new Fixture(); f.context.adapter.enabled = false; f.connect();
        long[] waits = {1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 30000, 30000};
        for (long wait : waits) {
            check(f.sink.status().contains("retry in " + wait / 1000 + " seconds"), "disabled adapter delay");
            Handler.advance(wait);
            check(f.context.adapter.connections == 0, "no connectGatt while Bluetooth disabled");
        }
        check(f.sink.statuses.stream().noneMatch(s -> s.contains("restart to retry")), "disabled radio not terminal");
        f.context.adapter.enabled = true;
        Handler.advance(29999); check(f.context.adapter.connections == 0, "enable waits only until bounded poll");
        Handler.advance(1); check(f.context.adapter.connections == 1, "enabled radio reconnects automatically");
        finish(f); f.checkAudio("radio recovered");
        f.context.adapter.enabled = false; drop(f); Handler.advance(1000);
        check(f.context.adapter.connections == 1 && f.sink.status().contains("retry in 2 seconds"), "disable after streaming remains recoverable");
        f.context.adapter.enabled = true; delay(f, 2000); finish(f); f.checkAudio("second radio recovery");
        f.ble.stop();
    }
    static void cancelAndStale() throws Exception {
        scenario("stop, new selection and already queued callbacks cancel stale work");
        Fixture f = new Fixture(); f.ready(); drop(f);
        Runnable oldRetry = retryTask(f);
        f.ble.stop(); int statuses = f.sink.statuses.size(), connections = f.context.adapter.connections;
        oldRetry.run(); Handler.advance(120000);
        check(f.sink.statuses.size() == statuses && f.context.adapter.connections == connections, "stop cancels retry and manually replayed runnable");
        check(retryTask(f) == null, "stop removes retry handle");

        f = new Fixture(); f.connect(); drop(f); oldRetry = retryTask(f);
        f.nextRemote(); f.ble.connect("11:22:33:44:55:66"); Handler.drain();
        oldRetry.run(); Handler.advance(1000);
        check(f.context.adapter.connections == 2 && !f.remote.closed, "new selection cancels old retry");
        finish(f); f.checkAudio("new selected device"); f.ble.stop();

        f = new Fixture(); f.ble.connect("AA:BB:CC:DD:EE:FF"); f.ble.stop(); Handler.drain();
        check(f.context.adapter.connections == 0, "stop cancels a not-yet-dispatched selection");

        f = new Fixture(); f.ready();
        BluetoothGatt old = f.remote;
        BluetoothGattCharacteristic oldAudio = f.audio, oldButton = f.button, oldCodec = f.codec;
        BluetoothGattDescriptor oldCcc = f.audioCcc;
        drop(f); delay(f, 1000); finish(f);
        int pcm = f.sink.pcm, buttons = f.sink.buttons.size(), gaps = f.sink.gaps;
        statuses = f.sink.statuses.size();
        old.callback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED);
        old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED);
        old.callback.onMtuChanged(old, 247, 0); old.callback.onServicesDiscovered(old, 0);
        old.callback.onCharacteristicRead(old, oldCodec, new byte[]{20}, 0);
        old.callback.onDescriptorWrite(old, oldCcc, 0);
        old.callback.onCharacteristicChanged(old, oldAudio, new byte[]{0, 0, 0, 3});
        old.callback.onCharacteristicChanged(old, oldButton, event(1, 8)); Handler.drain();
        check(f.sink.pcm == pcm && f.sink.buttons.size() == buttons && f.sink.gaps == gaps && f.sink.statuses.size() == statuses,
            "all stale callbacks ignored without replay, reset or retry");
        f.remote.callback.onCharacteristicChanged(f.remote, f.button, event(1, 8));
        f.ble.connect("11:22:33:44:55:66"); f.nextRemote(); Handler.drain();
        check(f.sink.buttons.size() == buttons, "selection epoch suppresses already queued old button");
        f.ble.stop();
    }
    static void stopInsideOpenCallback() {
        for (String boundary : List.of("status", "gap")) {
            scenario("Stop during in-progress open callback: " + boundary);
            Fixture f = new Fixture();
            OmiBle[] client = new OmiBle[1];
            int[] gaps = {0}, statuses = {0};
            client[0] = new OmiBle(f.context, new OmiBle.Listener() {
                public void onDevice(String address, String name) {}
                public void onPcm(short[] samples) { throw new AssertionError("cancelled open delivered PCM"); }
                public void onButton(int event) { throw new AssertionError("cancelled open delivered button"); }
                public void onGap() {
                    // connect() first cleans up the previous selection; the second
                    // gap is open()'s own externally observable boundary.
                    if (++gaps[0] == 2 && boundary.equals("gap")) client[0].stop();
                }
                public void onStatus(String status) {
                    statuses[0]++;
                    if (boundary.equals("status") && status.startsWith("Connecting to selected Omi")) client[0].stop();
                }
            });
            client[0].connect("AA:BB:CC:DD:EE:FF"); Handler.drain();
            check(f.context.adapter.connections == 0, "Stop inside " + boundary + " callback prevents stale connectGatt");
            check(statuses[0] == (boundary.equals("status") ? 1 : 0), "no status published after callback cancelled selection");
            int count = statuses[0]; Handler.advance(120000);
            check(f.context.adapter.connections == 0 && statuses[0] == count, "cancelled open never schedules a retry");
        }
    }
    static void incompleteDiscovery(Fixture f) {
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        f.remote.pending = null; f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
        f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
    }
    static void discoveryRecheck() {
        for (String missing : List.of("service", "codec", "audio")) {
            scenario("incomplete successful discovery rechecked without declaring incompatibility: " + missing);
            Fixture f = new Fixture();
            BluetoothGattService service = f.remote.services.get(OmiBle.SERVICE);
            if (missing.equals("service")) f.remote.services.remove(OmiBle.SERVICE);
            else service.chars.remove(missing.equals("codec") ? CODEC : AUDIO);
            f.connect(); incompleteDiscovery(f);
            check(!f.remote.closed, "partial discovery must not immediately close a valid link");
            check(f.sink.status().contains("rechecking") && !f.sink.status().contains("restart to retry"), "incomplete result stays recoverable");
            check(f.remote.pending == null && f.sink.pcm == 0, "no overlapping operation or invented PCM");
            f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
            Handler.advance(1499); check(Collections.frequency(f.remote.calls, "discover") == 1, "settling delay, duplicate callback ignored");
            Handler.advance(1); check(Collections.frequency(f.remote.calls, "discover") == 2, "exactly one delayed rediscovery");
            f.remote.services.put(OmiBle.SERVICE, service); service.add(f.codec); service.add(f.audio);
            f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
            check(f.remote.pending == f.codec, "complete recheck advances to codec");
            f.remote.pending = null; f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0); Handler.drain();
            f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0); f.checkAudio("same-link rediscovery recovery");
            check(f.context.adapter.connections == 1, "no needless fresh connection"); f.ble.stop();
        }
        scenario("repeated missing service preserves recovery backoff, no false incompatible stop");
        Fixture f = new Fixture(); f.connect();
        long[] waits = {1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 30000};
        for (long wait : waits) {
            f.remote.services.clear(); incompleteDiscovery(f); Handler.advance(1500);
            f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
            check(f.remote.closed && f.remote.disconnected, "unresolved recheck closes old GATT");
            check(f.sink.status().contains("audio service") && !f.sink.status().contains("restart to retry"), "missing service reason retained, no terminal classification");
            delay(f, wait);
        }
        finish(f); f.checkAudio("missing-service sequence recovered");
        drop(f); delay(f, 1000); f.ble.stop();

        scenario("stop cancels rediscovery and stale callback cannot revive it");
        f = new Fixture(); f.remote.services.clear(); f.connect(); incompleteDiscovery(f);
        BluetoothGatt old = f.remote; f.ble.stop(); Handler.drain(); int statuses = f.sink.statuses.size();
        old.callback.onServicesDiscovered(old, 0); Handler.advance(120000);
        check(Collections.frequency(old.calls, "discover") == 1 && f.context.adapter.connections == 1, "Stop prevents delayed discovery and retries");
        check(f.sink.statuses.size() == statuses, "no stale status after Stop");

        scenario("new selection supersedes old delayed rediscovery");
        f = new Fixture(); f.remote.services.clear(); f.connect(); incompleteDiscovery(f); old = f.remote;
        f.nextRemote(); f.ble.connect("11:22:33:44:55:66"); Handler.drain(); Handler.advance(1500);
        check(Collections.frequency(old.calls, "discover") == 1 && old.closed, "old discovery cancelled by selection");
        finish(f); f.checkAudio("new selection after partial old discovery"); f.ble.stop();

        scenario("lost rediscovery callback has its own bounded operation deadline");
        f = new Fixture(); f.remote.services.clear(); f.connect(); incompleteDiscovery(f); Handler.advance(1500);
        Handler.advance(14999); check(!f.remote.closed, "recheck callback deadline not early");
        Handler.advance(1); check(f.remote.closed && f.sink.status().contains("discovery timed out"), "lost recheck retries instead of hanging");
        delay(f, 1000); f.ble.stop();

        for (String missing : List.of("audio-ccc", "audio-properties")) {
            scenario("partial audio attributes get same bounded rediscovery: " + missing);
            f = new Fixture(); f.connect(); f.toCodec();
            if (missing.equals("audio-ccc")) f.audio.descriptors.clear(); else f.audio.properties = 0;
            f.remote.pending = null; f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0); Handler.drain();
            check(!f.remote.closed && f.sink.status().contains("rechecking"), "attributes may be incomplete, not proof of incompatibility");
            Handler.advance(1500); f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
            f.remote.pending = null; f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0); Handler.drain();
            check(f.remote.closed, "one recheck per connection even for missing attributes"); delay(f, 1000); f.ble.stop();
        }
    }
    static void classifications() {
        scenario("permission and confirmed unsupported codec terminal, incomplete discovery retryable");
        for (String mode : List.of("permission", "codec")) {
            Fixture f = new Fixture();
            if (mode.equals("permission")) f.context.adapter.failure = new SecurityException("fixture denied");
            if (mode.equals("service")) f.remote.services.clear();
            if (mode.equals("audio-ccc")) f.audio.descriptors.clear();
            if (mode.equals("audio-properties")) f.audio.properties = 0;
            f.connect();
            if (mode.equals("service")) {
                f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
                f.remote.pending = null; f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
                f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 0); Handler.drain();
            } else if (!mode.equals("permission")) {
                f.toCodec(); f.remote.pending = null;
                f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{(byte)(mode.equals("codec") ? 99 : 20)}, 0); Handler.drain();
            }
            check(f.sink.status().contains("restart to retry"), "terminal failure exposed " + mode);
            int count = f.context.adapter.connections; Handler.advance(120000);
            check(f.context.adapter.connections == count, "terminal failure cannot retry " + mode); f.ble.stop();
        }
        Fixture f = new Fixture(); f.connect();
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        f.remote.pending = null; f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
        f.remote.pending = null; f.remote.callback.onServicesDiscovered(f.remote, 133); Handler.drain();
        delay(f, 1000); finish(f); f.checkAudio("transient discovery failure"); f.ble.stop();

        f = new Fixture(); f.context.adapter.failure = new IllegalStateException("radio transition"); f.connect();
        check(f.sink.status().contains("retry in 1 seconds"), "runtime transport failure retryable, not assumed permission denial");
        f.context.adapter.failure = null; delay(f, 1000); finish(f); f.checkAudio("runtime connection recovery"); f.ble.stop();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275];
        int size = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(size > 0, "real Opus fixture encoded"); ButtonBleTest.opus = Arrays.copyOf(encoded, size);
        discoveryRecheck(); observedTimeoutSequence(); startupAndEstablishedAudio(); persistent(); onlyDecodedResets(); timeouts(); radioOffOn(); cancelAndStale(); classifications(); stopInsideOpenCallback();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " reconnect host scenarios");
        System.out.println("HOST ONLY: actual OmiBle + Concentus, shared fake Android/GATT/Handler; no wearable acceptance claimed");
    }
}
