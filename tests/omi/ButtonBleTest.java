package app.nottheomi.ai;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import java.util.*;
import org.concentus.*;

/** Host fixtures, not Android/BLE hardware acceptance. Uses production OmiBle. */
public final class ButtonBleTest {
    static final UUID AUDIO = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214");
    static final UUID CODEC = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214");
    static final UUID BUTTON_SERVICE = UUID.fromString("23ba7924-0000-1000-7450-346eac492e92");
    static final UUID BUTTON = UUID.fromString("23ba7925-0000-1000-7450-346eac492e92");
    static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static int assertions, scenarios;
    static byte[] opus;
    static void check(boolean ok, String why) { assertions++; if (!ok) throw new AssertionError(why); }
    static void scenario(String name) { scenarios++; System.out.println("  " + name); }
    static byte[] event(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < Math.min(4, length); i++) bytes[i] = (byte)(value >>> (i * 8));
        return bytes;
    }
    static BluetoothGattCharacteristic characteristic(UUID uuid, boolean descriptor) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid);
        if (descriptor) c.add(new BluetoothGattDescriptor(CCC));
        return c;
    }
    static final class Sink implements OmiBle.Listener {
        final List<Integer> buttons = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<OmiBle.LedState> ledStates = new ArrayList<>();
        final List<Integer> batteries = new ArrayList<>();
        Runnable batteryHook, statusHook;
        Runnable ledHook;
        final List<String> devices = new ArrayList<>();
        final List<short[]> decoded = new ArrayList<>();
        int gaps, pcm, samples;
        public void onDevice(String address, String name) { devices.add(address + ":" + name); }
        public void onPcm(short[] value) { pcm++; samples += value.length; decoded.add(value.clone()); }
        public void onStatus(String status) { statuses.add(status); if (statusHook != null) statusHook.run(); }
        public void onGap() { gaps++; }
        public void onButton(int event) { buttons.add(event); }
        public void onLedState(OmiBle.LedState state) { ledStates.add(state); if (ledHook != null) ledHook.run(); }
        public void onBattery(int percent) { batteries.add(percent); if (batteryHook != null) batteryHook.run(); }
        int battery() { return batteries.get(batteries.size() - 1); }
        OmiBle.LedState led() { return ledStates.get(ledStates.size() - 1); }
        String status() { return statuses.get(statuses.size() - 1); }
    }
    static final class Fixture {
        final Context context = new Context();
        final Sink sink = new Sink();
        final OmiBle ble;
        BluetoothGatt remote;
        BluetoothGattCharacteristic audio, codec, button;
        BluetoothGattDescriptor audioCcc, buttonCcc;
        int seq;
        Fixture() { Handler.reset(); ble = new OmiBle(context, sink); nextRemote(); }
        void nextRemote() {
            seq = 0; remote = new BluetoothGatt();
            BluetoothGattService audioService = new BluetoothGattService(OmiBle.SERVICE);
            audio = characteristic(AUDIO, true); codec = characteristic(CODEC, false);
            audioCcc = audio.getDescriptor(CCC); audioService.add(audio); audioService.add(codec);
            remote.services.put(OmiBle.SERVICE, audioService);
            BluetoothGattService buttonService = new BluetoothGattService(BUTTON_SERVICE);
            button = characteristic(BUTTON, true); buttonCcc = button.getDescriptor(CCC);
            button.setValue(event(1, 8)); // retained prior press MUST NOT be read/replayed
            buttonService.add(button); remote.services.put(BUTTON_SERVICE, buttonService);
            context.adapter.next = remote;
        }
        void connect() { ble.connect("AA:BB:CC:DD:EE:FF"); Handler.drain(); }
        void toCodec() {
            remote.callback.onConnectionStateChange(remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
            check("mtu".equals(remote.pending), "MTU before discovery");
            check(sink.status().contains("negotiating audio packet size"), "MTU stage visible");
            remote.pending = null; remote.callback.onMtuChanged(remote, 247, 0); Handler.drain();
            check("discover".equals(remote.pending), "discovery after MTU");
            check(sink.status().contains("discovering Omi audio service"), "discovery stage visible");
            remote.pending = null; remote.callback.onServicesDiscovered(remote, 0); Handler.drain();
            check(remote.pending == codec, "codec read after discovery");
            check(sink.status().contains("checking Omi audio format"), "codec stage visible");
            check(remote.reads.equals(List.of(codec)), "only codec read; no retained button replay");
        }
        void toAudioCcc(boolean legacy) {
            toCodec(); remote.pending = null;
            if (legacy) { codec.setValue(new byte[]{20}); remote.callback.onCharacteristicRead(remote, codec, 0); }
            else remote.callback.onCharacteristicRead(remote, codec, new byte[]{20}, 0);
            Handler.drain();
            check(remote.pending == audioCcc, "audio CCC follows codec read");
            check(sink.status().contains("enabling Omi audio stream"), "subscription stage visible");
            check(remote.writes.equals(List.of(audioCcc)), "button CCC not started before audio ack");
            check(Arrays.equals(audioCcc.getValue(), new byte[]{1, 0}), "audio CCC notification-only write");
        }
        void ack(BluetoothGattDescriptor target, int status) {
            if (remote.pending == target) remote.pending = null;
            remote.callback.onDescriptorWrite(remote, target, status); Handler.drain();
        }
        void ready() { connect(); toAudioCcc(false); ack(audioCcc, 0); ack(buttonCcc, 0); }
        void notify(BluetoothGattCharacteristic target, byte[] value) {
            remote.callback.onCharacteristicChanged(remote, target, value); Handler.drain();
        }
        void readButton() {
            remote.callback.onCharacteristicRead(remote, button, event(1, 8), 0);
            button.setValue(event(2, 8)); remote.callback.onCharacteristicRead(remote, button, 0);
            Handler.drain();
        }
        void audio() {
            byte[] wire = new byte[opus.length + 3];
            wire[0] = (byte)seq; wire[1] = (byte)(seq >> 8); seq++;
            System.arraycopy(opus, 0, wire, 3, opus.length); notify(audio, wire);
        }
        void checkAudio(String label) {
            int before = sink.pcm; audio(); audio();
            check(sink.pcm > before && sink.samples >= 320, label + ": real Concentus PCM delivered");
        }
    }
    static void parser() {
        scenario("strict stock 8-byte LE32 event plus zero reserved word");
        check(ButtonEvent.decode(null) == 0, "null ignored");
        for (int size = 0; size < 8; size++) check(ButtonEvent.decode(event(1, size)) == 0, "short event ignored " + size);
        for (int size : new int[]{8}) {
            check(ButtonEvent.decode(event(1, size)) == 1, "single layout " + size);
            check(ButtonEvent.decode(event(2, size)) == 2, "double layout " + size);
        }
        for (long value : new long[]{0, 3, 4, 5, 6, 255, 256, 257, 258, 65537, 0x01000001L, 0x80000001L, 0xffffffffL})
            check(ButtonEvent.decode(event(value, 8)) == 0, "ignored full uint32 " + value);
        byte[] twoWords = event(1, 8); Arrays.fill(twoWords, 4, 8, (byte)255);
        check(ButtonEvent.decode(twoWords) == 0, "nonzero reserved word rejected");
        check(ButtonEvent.decode(event(1, 9)) == 0, "extra bytes rejected");
        check(ButtonEvent.decode(event(1, 12)) == 0, "extended format rejected");
        twoWords = event(0, 8); twoWords[4] = 2;
        check(ButtonEvent.decode(twoWords) == 0, "second word cannot become event");
        check(ButtonEvent.decode(new byte[]{0, 0, 0, 1}) == 0, "big-endian value is not SINGLE");
    }
    static void serialAndFresh() {
        scenario("serial GATT, target CCC identity, fresh notifications, both callback APIs");
        Fixture f = new Fixture(); f.connect(); f.toAudioCcc(true);
        f.readButton(); f.notify(f.button, event(1, 8));
        check(f.sink.buttons.isEmpty(), "no initial read or pre-subscribe event");
        BluetoothGattDescriptor unrelated = new BluetoothGattDescriptor(CCC);
        f.ack(unrelated, 0); f.ack(f.buttonCcc, 0);
        check(f.remote.pending == f.audioCcc && f.remote.writes.size() == 1, "unrelated CCC cannot mark audio ready");
        f.ack(f.audioCcc, 0);
        check(f.remote.pending == f.buttonCcc, "button CCC starts only after audio ack");
        check(f.remote.writes.equals(List.of(f.audioCcc, f.buttonCcc)), "two serial CCC writes only");
        check(Arrays.equals(f.buttonCcc.getValue(), new byte[]{1, 0}), "button CCC enable only");
        f.checkAudio("button subscription pending");
        f.ack(f.audioCcc, 0); f.notify(f.button, event(1, 8)); f.readButton();
        check(f.sink.buttons.isEmpty(), "duplicate audio ack cannot ready button");
        f.ack(f.buttonCcc, 0);
        check(f.sink.status().contains("button controls ready"), "ready status only after button ack");
        f.readButton(); check(f.sink.buttons.isEmpty(), "reads ignored even after subscription");
        byte[] mutable = event(1, 8);
        f.remote.callback.onCharacteristicChanged(f.remote, f.button, mutable); mutable[0] = 2; Handler.drain();
        check(f.sink.buttons.equals(List.of(1)), "API33 notification value snapshot");
        mutable = event(2, 8); f.button.setValue(mutable);
        f.remote.callback.onCharacteristicChanged(f.remote, f.button); mutable[0] = 1; Handler.drain();
        check(f.sink.buttons.equals(List.of(1, 2)), "legacy notification value snapshot");
        for (long value : new long[]{0, 3, 4, 5, 6, 257, 0x80000001L, 0xffffffffL}) f.notify(f.button, event(value, 8));
        f.notify(f.button, event(1, 1)); f.notify(f.button, null);
        f.notify(characteristic(BUTTON, true), event(1, 8));
        check(f.sink.buttons.equals(List.of(1, 2)), "power, malformed, unknown and foreign characteristic ignored");
        f.notify(f.button, event(1, 8)); f.notify(f.button, event(1, 8));
        check(f.sink.buttons.equals(List.of(1, 2, 1, 1)), "separate SINGLE notifications not incorrectly deduplicated");
        f.checkAudio("button subscription complete");
        check(f.remote.reads.equals(List.of(f.codec)), "never read button state");
        f.ble.stop();
    }
    static void optionalFallbacks() {
        for (String mode : List.of("no-service", "no-characteristic", "no-notify-property", "no-ccc", "notify-false", "notify-throws", "write-false", "write-throws", "ack-fails")) {
            scenario("optional failure preserves audio: " + mode);
            Fixture f = new Fixture();
            switch (mode) {
                case "no-service": f.remote.services.remove(BUTTON_SERVICE); break;
                case "no-characteristic": f.remote.services.get(BUTTON_SERVICE).chars.clear(); break;
                case "no-notify-property": f.button.properties = 0; break;
                case "no-ccc": f.button.descriptors.clear(); break;
                case "notify-false": f.remote.rejectNotify.add(BUTTON); break;
                case "notify-throws": f.remote.throwNotify.add(BUTTON); break;
                case "write-false": f.remote.rejectWrite.add(BUTTON); break;
                case "write-throws": f.remote.throwWrite.add(BUTTON); break;
            }
            f.connect(); f.toAudioCcc(false); int gaps = f.sink.gaps; f.ack(f.audioCcc, 0);
            if (mode.equals("ack-fails")) f.ack(f.buttonCcc, 133);
            check(!f.remote.closed && !f.remote.disconnected, "optional failure cannot disconnect " + mode);
            check(f.sink.gaps == gaps, "optional failure cannot discard task/audio " + mode);
            check(f.sink.status().contains("button controls unavailable") && f.sink.status().contains("waiting for first audio"), "degraded status must not claim audio before PCM " + mode);
            f.notify(f.button, event(1, 8)); f.readButton(); f.ack(f.buttonCcc, 0);
            check(f.sink.buttons.isEmpty(), "failed subscription cannot dispatch even after late ack " + mode);
            f.checkAudio(mode);
            check(f.sink.status().contains("local transcription active") && f.sink.status().contains("button controls unavailable"), "decoded audio promotes degraded readiness " + mode);
            check(f.context.adapter.connections == 1 && f.remote.reads.equals(List.of(f.codec)), "no retry or button read " + mode);
            f.ble.stop();
        }
    }
    static void optionalTimeout() {
        scenario("independent optional timeout with continuous real audio and late ack");
        Fixture f = new Fixture(); f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        int gaps = f.sink.gaps;
        for (int i = 0; i < 4; i++) { Handler.advance(4000); f.audio(); }
        check(f.sink.status().contains("button controls unavailable (subscription timed out)"), "audio notifications do not cancel optional timeout");
        check(!f.remote.closed && f.sink.gaps == gaps && f.context.adapter.connections == 1, "button timeout does not reconnect/discard audio");
        f.ack(f.buttonCcc, 0); f.notify(f.button, event(1, 8));
        check(f.sink.buttons.isEmpty() && !f.sink.status().contains("controls ready"), "late optional ack cannot revive readiness");
        f.checkAudio("after optional timeout");
        f.ble.stop();
    }
    static void watchdogs() {
        scenario("audio watchdog persists while button pending, after success and after fallback");
        for (String mode : List.of("pending", "ready", "absent")) {
            Fixture f = new Fixture();
            if (mode.equals("absent")) f.remote.services.remove(BUTTON_SERVICE);
            f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
            if (mode.equals("ready")) f.ack(f.buttonCcc, 0);
            int gaps = f.sink.gaps;
            Handler.advance(7000); f.notify(f.button, event(1, 8));
            Handler.advance(7000); f.notify(f.button, event(2, 8));
            check(!f.remote.closed, "no premature audio timeout " + mode);
            Handler.advance(15999); f.notify(f.button, event(1, 8));
            check(!f.remote.closed, "startup audio deadline not early " + mode);
            Handler.advance(1);
            check(f.remote.closed && f.sink.status().contains("No Omi audio for 30 seconds"), "button traffic cannot hide missing startup audio " + mode);
            check(f.sink.gaps == gaps + 1, "actual audio timeout signals gap " + mode);
            f.ble.stop();
        }
        scenario("button success does not reset most recent audio deadline");
        Fixture f = new Fixture(); f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        Handler.advance(1000); f.audio(); f.audio(); // decoded PCM: expires at 16000
        Handler.advance(9000); f.ack(f.buttonCcc, 0);
        Handler.advance(5999); check(!f.remote.closed, "audio deadline remains exact before expiry");
        Handler.advance(1); check(f.remote.closed, "button ack cannot postpone audio watchdog");
        f.ble.stop();
    }
    static void reconnectAndStop() {
        scenario("old connection callbacks, reset readiness, queued events and stop cancellation");
        Fixture f = new Fixture(); f.ready();
        BluetoothGatt old = f.remote;
        BluetoothGattCharacteristic oldButton = f.button, oldAudio = f.audio, oldCodec = f.codec;
        BluetoothGattDescriptor oldCcc = f.buttonCcc;
        old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
        check(old.closed, "disconnect closes old GATT");
        f.nextRemote(); Handler.advance(1000);
        check(f.context.adapter.connections == 2, "reconnect opens next GATT");
        f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        int statuses = f.sink.statuses.size(), gaps = f.sink.gaps;
        old.callback.onCharacteristicChanged(old, oldButton, event(1, 8));
        old.callback.onCharacteristicChanged(old, oldAudio, new byte[]{0, 0, 0, 1});
        old.callback.onDescriptorWrite(old, oldCcc, 0);
        old.callback.onCharacteristicRead(old, oldCodec, new byte[]{20}, 0);
        old.callback.onServicesDiscovered(old, 133);
        old.callback.onMtuChanged(old, 247, 0);
        old.callback.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED);
        Handler.drain();
        f.notify(f.button, event(1, 8)); f.readButton();
        check(f.sink.buttons.isEmpty(), "reconnect button readiness reset before ack");
        check(f.sink.statuses.size() == statuses && f.sink.gaps == gaps && !f.remote.closed, "all stale GATT callbacks gated");
        f.ack(f.buttonCcc, 0); f.notify(f.button, event(2, 8));
        check(f.sink.buttons.equals(List.of(2)), "fresh new-connection event delivered");
        f.checkAudio("reconnected");
        f.remote.callback.onCharacteristicChanged(f.remote, f.button, event(1, 8));
        f.ble.stop(); Handler.drain();
        check(f.sink.buttons.equals(List.of(2)), "already-queued notification suppressed by stop");
        int connections = f.context.adapter.connections; statuses = f.sink.statuses.size();
        Handler.advance(60000);
        check(f.context.adapter.connections == connections && f.sink.statuses.size() == statuses, "stop cancels watchdog, optional deadline and retry");
    }
    static void audioFailure() {
        scenario("required audio CCC failure remains fatal/retryable, optional write never starts");
        Fixture f = new Fixture(); f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 133);
        check(f.remote.closed && f.remote.writes.equals(List.of(f.audioCcc)), "required subscription failure reconnects without button attempt");
        check(f.sink.status().contains("retry"), "required audio failure keeps retry policy");
        f.ble.stop();
    }
    static void connectionFeedback() {
        scenario("readiness requires decoded PCM, not CCCs or buffered packets; resets on reconnect");
        Fixture f = new Fixture(); f.ready();
        check(f.sink.status().contains("waiting for first audio"), "button ack is not audio proof");
        check(f.sink.statuses.stream().noneMatch(s -> s.contains("local transcription active")), "no premature live status in setup");
        f.audio();
        check(f.sink.pcm == 0 && f.sink.status().contains("waiting for first audio"), "buffered first frame not sufficient");
        f.audio();
        check(f.sink.pcm > 0 && f.sink.status().contains("receiving audio"), "decoded PCM marks ready");
        int statuses = f.sink.statuses.size(); f.audio();
        check(f.sink.statuses.size() == statuses, "normal PCM does not spam status");
        f.remote.callback.onConnectionStateChange(f.remote, 0, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
        check(f.sink.status().contains("retry in 1 seconds"), "disconnect publishes retry");
        f.nextRemote(); Handler.advance(1000); f.toAudioCcc(false); f.ack(f.audioCcc, 0); f.ack(f.buttonCcc, 0);
        check(f.sink.status().contains("waiting for first audio"), "reconnect cannot inherit earlier PCM proof");
        f.ble.stop(); Handler.drain(); statuses = f.sink.statuses.size(); Handler.advance(60000);
        check(f.sink.statuses.size() == statuses, "stop cancels pending feedback and retry");

        scenario("human-readable connect timeout retains existing retry");
        f = new Fixture(); f.connect(); Handler.advance(15000);
        check(f.sink.status().contains("Omi connection timed out; retry in 1 seconds"), "named timeout instead of operation code");
        f.ble.stop(); Handler.drain();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] pcm = new short[320];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275];
        int length = encoder.encode(pcm, 0, pcm.length, encoded, 0, encoded.length);
        check(length > 0, "real Opus encoded fixture"); opus = Arrays.copyOf(encoded, length);
        parser(); serialAndFresh(); optionalFallbacks(); optionalTimeout(); watchdogs(); reconnectAndStop(); audioFailure(); connectionFeedback();
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " focused button/BLE host scenarios");
        System.out.println("HOST ONLY: Android/BLE fakes + actual production OmiBle, ButtonEvent and Concentus; no wearable/runtime acceptance claimed");
    }
}
