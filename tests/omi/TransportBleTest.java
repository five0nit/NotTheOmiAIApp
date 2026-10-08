package app.nottheomi.ai;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.Handler;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Additional fail-closed/fragment/scan regressions; host-only, not Android acceptance. */
public final class TransportBleTest {
    static byte[] packet(int sequence, int fragment, byte[] payload) {
        byte[] result = new byte[payload.length + 3];
        result[0] = (byte)sequence; result[1] = (byte)(sequence >>> 8); result[2] = (byte)fragment;
        System.arraycopy(payload, 0, result, 3, payload.length); return result;
    }
    static void codecs() throws Exception {
        for (int codec : new int[]{20, 21}) for (boolean legacy : new boolean[]{false, true}) {
            scenario("pinned Omi codec " + codec + " actual PCM through " + (legacy ? "legacy" : "modern") + " callbacks");
            Fixture f = new Fixture(); f.connect(); f.toCodec(); f.remote.pending = null;
            if (legacy) { f.codec.setValue(new byte[]{(byte)codec}); f.remote.callback.onCharacteristicRead(f.remote, f.codec, 0); }
            else f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{(byte)codec}, 0);
            Handler.drain(); check(f.remote.pending == f.audioCcc, "supported codec subscribes audio");
            f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
            int size = codec == 20 ? 160 : 320; short[] samples = new short[size];
            for (int i = 0; i < size; i++) samples[i] = (short)(10000 * Math.sin(i * 0.08));
            OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            byte[] encoded = new byte[512]; int length = encoder.encode(samples, 0, size, encoded, 0, encoded.length);
            encoded = Arrays.copyOf(encoded, length);
            for (int seq = 0; seq < 2; seq++) {
                byte[] wire = packet(seq, 0, encoded);
                if (legacy) { f.audio.setValue(wire); f.remote.callback.onCharacteristicChanged(f.remote, f.audio); Handler.drain(); }
                else f.notify(f.audio, wire);
            }
            short[] expected = new short[1920]; int count = new OpusDecoder(16000, 1).decode(encoded, 0, length, expected, 0, expected.length, false);
            check(count == size && f.sink.pcm == 1, "pinned 10/20 ms frame length decoded");
            check(Arrays.equals(f.sink.decoded.get(0), Arrays.copyOf(expected, count)), "actual decoded PCM exact");
            f.ble.stop(); Handler.drain();
        }
        for (int codec : new int[]{0, 1, 19, 22, 255}) {
            scenario("unverified codec fails closed: " + codec);
            Fixture f = new Fixture(); f.connect(); f.toCodec(); f.remote.pending = null;
            f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{(byte)codec}, 0); Handler.drain();
            check(f.remote.closed && f.remote.writes.isEmpty(), "unknown codec cannot subscribe/decode");
            check(f.sink.status().contains("Unsupported Omi codec " + codec), "visible codec rejection");
            Handler.advance(120000); check(f.context.adapter.connections == 1, "unsupported codec not retried forever");
            f.ble.stop(); Handler.drain();
        }
        scenario("codec requires exact read target; malformed multi-byte codec rejected");
        Fixture f = new Fixture(); f.connect(); f.toCodec();
        f.remote.callback.onCharacteristicRead(f.remote, characteristic(CODEC, false), new byte[]{20}, 0);
        Handler.drain(); check(f.remote.pending == f.codec && f.remote.writes.isEmpty(), "same UUID is not read identity");
        f.remote.pending = null;
        f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20, 0}, 0); Handler.drain();
        check(f.remote.closed && f.remote.writes.isEmpty(), "extended codec layout must not be guessed");
        f.ble.stop(); Handler.drain();
    }
    static void mtu() {
        scenario("uncertain native MTU completion never overlaps discovery");
        Fixture f = new Fixture(); f.connect();
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("mtu".equals(f.remote.pending), "native MTU still pending");
        Handler.advance(15000);
        check(f.remote.closed && !f.remote.calls.contains("discover"), "timeout closes before any further GATT operation");
        f.ble.stop(); Handler.drain();
        scenario("rejected MTU may continue, thrown MTU quarantines connection");
        f = new Fixture(); f.remote.rejectMtu = true; f.connect();
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("discover".equals(f.remote.pending), "false means native operation not submitted");
        f.ble.stop(); Handler.drain();
        f = new Fixture(); f.remote.mtuFailure = new IllegalStateException("uncertain native submission"); f.connect();
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check(f.remote.closed && !f.remote.calls.contains("discover"), "exception cannot assume native idle");
        f.ble.stop(); Handler.drain();
    }
    static void framing() throws Exception {
        scenario("real Opus split fragments decode sample-identically, loss resets decoder");
        Fixture f = new Fixture(); f.ready(); int gaps = f.sink.gaps;
        int split = opus.length / 2;
        byte[] first = Arrays.copyOfRange(opus, 0, split), rest = Arrays.copyOfRange(opus, split, opus.length);
        f.notify(f.audio, packet(65534, 0, first)); f.notify(f.audio, packet(65535, 1, rest));
        check(f.sink.pcm == 0, "frame waits for next zero fragment");
        f.notify(f.audio, packet(0, 0, opus));
        short[] expected = new short[1920];
        int count = new OpusDecoder(16000, 1).decode(opus, 0, opus.length, expected, 0, expected.length, false);
        check(f.sink.pcm == 1 && Arrays.equals(f.sink.decoded.get(0), Arrays.copyOf(expected, count)), "actual decoder PCM exact after fragmentation and LE16 wrap");
        f.notify(f.audio, packet(0, 0, opus)); check(f.sink.pcm == 1, "duplicate cannot deliver PCM");
        f.notify(f.audio, packet(2, 0, opus));
        check(f.sink.gaps == gaps + 1 && f.sink.pcm == 1, "missing sequence discards incomplete audio rather than joining");
        f.notify(f.audio, packet(3, 0, opus));
        check(f.sink.pcm == 2 && Arrays.equals(f.sink.decoded.get(1), Arrays.copyOf(expected, count)), "first post-gap frame uses fresh decoder");
        f.ble.stop(); Handler.drain();
        check(f.sink.pcm == 2, "stop never flushes unbounded trailing frame");

        scenario("parser rejects orphan, malformed, overflow and fragment discontinuity");
        OmiBle.FrameAssembler frames = new OmiBle.FrameAssembler();
        check(frames.accept(packet(1, 1, first)) == null && !frames.accepted, "orphan continuation ignored");
        frames.accept(packet(2, 0, first)); frames.accept(packet(3, 2, rest));
        check(frames.gap && !frames.accepted, "missing fragment index discards frame");
        frames.accept(packet(4, 0, new byte[1275])); frames.accept(packet(5, 1, new byte[]{1}));
        check(frames.gap && !frames.accepted, "1275-byte cap bounds accumulation");
        check(frames.accept(new byte[]{1, 2, 3}) == null && frames.gap, "empty payload invalid");
        check(frames.accept(null) == null && frames.gap, "null packet invalid");
        check(frames.accept(packet(20, 0, opus)) == null, "restart begins clean");
        check(Arrays.equals(frames.accept(packet(21, 0, opus)), opus), "restart yields exact frame");
    }
    static void scans() {
        for (String mode : List.of("adapter-permission", "start-permission", "stop-permission", "no-scanner", "disabled")) {
            scenario("safe scan failure: " + mode);
            Fixture f = new Fixture(); BluetoothLeScanner scanner = f.context.adapter.scanner;
            switch (mode) {
                case "adapter-permission": f.context.adapter.failure = new SecurityException("denied"); break;
                case "start-permission": scanner.startFailure = new SecurityException("denied"); break;
                case "stop-permission": scanner.stopFailure = new SecurityException("revoked"); break;
                case "no-scanner": f.context.adapter.scanner = null; break;
                case "disabled": f.context.adapter.enabled = false; break;
            }
            f.ble.scan(); Handler.drain(); Handler.advance(15000); f.ble.stop(); Handler.drain();
            check(f.context.adapter.connections == 0 && !f.sink.statuses.isEmpty(), "scan failure visible without guessed connection");
            int devices = f.sink.devices.size();
            if (scanner.callback != null) scanner.callback.onScanResult(0, new ScanResult(new BluetoothDevice(f.context.adapter)));
            Handler.drain(); check(f.sink.devices.size() == devices, "late scan result suppressed");
        }
        scenario("scan epochs suppress stale callbacks; explicit selection only");
        Fixture f = new Fixture(); f.ble.scan(); Handler.drain(); ScanCallback old = f.context.adapter.scanner.callback;
        f.ble.scan(); Handler.drain(); ScanCallback fresh = f.context.adapter.scanner.callback;
        ScanResult result = new ScanResult(new BluetoothDevice(f.context.adapter));
        old.onScanResult(0, result); old.onScanFailed(3); Handler.drain();
        check(f.sink.devices.isEmpty() && f.sink.status().contains("Scanning"), "old scan cannot publish or end new scan");
        fresh.onScanResult(0, result); Handler.drain();
        check(f.sink.devices.equals(List.of("AA:BB:CC:DD:EE:FF:Omi")) && f.context.adapter.connections == 0, "current result only; no auto-connect");
        fresh.onScanResult(0, new ScanResult(null)); Handler.drain();
        check(f.sink.status().contains("discovery permission error"), "malformed or revoked result cannot crash worker");
        fresh.onScanFailed(2); Handler.drain(); check(f.sink.status().contains("BLE scan failed (2)"), "scan failure reported");
        f.ble.stop(); Handler.drain();
    }
    static void omiGlassOtaLayoutNeverPermitsLedWrites() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            scenario("OmiGlass two-byte OTA status never permits LED writes, legacy=" + legacy);
            Fixture f = LedBleTest.ready();
            for (int status : new int[]{0, 1, 2, 3, 4, 5, 100}) {
                LedBleTest.requestRead(f);
                LedBleTest.read(f, new byte[]{(byte)status, 0}, 0, legacy);
                check(f.sink.led().brightness == -1 && !f.sink.led().busy,
                    "OTA [status,progress] is not a brightness even when byte zero is in range");
                for (int command : new int[]{2, 3, 4}) LedBleTest.set(f, command);
                check(f.remote.commands.isEmpty(), "no OTA start/cancel/status command can be sent as brightness");
            }
            // A later invalid layout must also revoke a previous successful read.
            LedBleTest.known(f, 50);
            LedBleTest.requestRead(f);
            LedBleTest.read(f, new byte[]{2, 50}, 0, legacy);
            LedBleTest.set(f, 2);
            check(f.remote.commands.isEmpty() && Boolean.FALSE.equals(LedBleTest.field(f, "ledReadConfirmed")),
                "two-byte layout revokes prior current-session write authorization");
            f.checkAudio("OmiGlass LED layout rejected without touching audio");
            f.ble.stop(); Handler.drain();
        }
    }
    static void stopDuringSetup() {
        for (String boundary : List.of("mtu", "codec", "audio")) {
            scenario("Stop from setup status prevents follow-up native submission: " + boundary);
            Fixture f = new Fixture(); f.connect();
            if (boundary.equals("audio")) f.toCodec();
            else if (boundary.equals("codec")) {
                f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
                f.remote.pending = null; f.remote.callback.onMtuChanged(f.remote, 247, 0); Handler.drain();
            }
            f.sink.statusHook = () -> { f.sink.statusHook = null; f.ble.stop(); };
            int calls = f.remote.calls.size(); f.remote.pending = null;
            switch (boundary) {
                case "mtu": f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_CONNECTED); break;
                case "codec": f.remote.callback.onServicesDiscovered(f.remote, 0); break;
                case "audio": f.remote.callback.onCharacteristicRead(f.remote, f.codec, new byte[]{20}, 0); break;
            }
            Handler.drain();
            // The local notification registration happens before its status; it
            // is not an ATT request. Stop must still prevent the CCC submission.
            int localOnly = boundary.equals("audio") ? 1 : 0;
            check(f.remote.closed && f.remote.calls.size() == calls + localOnly, "no native request after status-triggered Stop");
            check(f.remote.writes.isEmpty(), "Stop cannot write CCC after callback");
        }
        scenario("null callbacks do not crash transport or manufacture PCM");
        Fixture f = new Fixture(); f.ready();
        f.remote.callback.onCharacteristicChanged(f.remote, null);
        f.remote.callback.onCharacteristicChanged(f.remote, null, new byte[]{1});
        f.remote.callback.onCharacteristicRead(f.remote, null, 0);
        f.remote.callback.onCharacteristicRead(f.remote, null, new byte[]{20}, 0);
        f.remote.callback.onDescriptorWrite(f.remote, null, 0); Handler.drain();
        check(f.sink.pcm == 0 && !f.remote.closed, "null platform inputs ignored");
        f.ble.stop(); Handler.drain();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int size = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(size > 2, "real Opus fixture encoded"); ButtonBleTest.opus = Arrays.copyOf(encoded, size);
        codecs(); mtu(); framing(); scans(); omiGlassOtaLayoutNeverPermitsLedWrites(); stopDuringSetup();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " transport hardening host scenarios");
        System.out.println("HOST ONLY: real decoder/transport with deterministic Android/GATT/scan fakes; no Android suite or wearable acceptance");
    }
}
