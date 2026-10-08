package app.nottheomi.ai;

import android.app.Service;
import android.bluetooth.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.concentus.*;

/** Production service owns production BLE; only Android/GATT/storage/preview are doubles. */
public final class OmiCompositionHostTest {
    static final UUID AUDIO = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214");
    static final UUID CODEC = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214");
    static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    static final OmiCaptureService service = new OmiCaptureService();
    static BluetoothGatt remote;
    static BluetoothGattCharacteristic audio, codec;
    static BluetoothGattDescriptor ccc;
    static byte[] opus;
    static int sequence, assertions;

    static void check(boolean ok, String why) {
        assertions++;
        if (!ok) throw new AssertionError(why + " [state=" + OmiCaptureService.state + ", transport=" + OmiCaptureService.transport + ", ops=" + Recordings.log() + "]");
    }
    static void until(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (true) {
            Handler.drain();
            if (backgroundFailure.get() != null) throw new AssertionError("background failure", backgroundFailure.get());
            if (ready.getAsBoolean()) return;
            if (System.nanoTime() >= end) throw new AssertionError("wait timeout; state=" + OmiCaptureService.state + "; ops=" + Recordings.log());
            Thread.sleep(2);
        }
    }
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static void nextRemote() {
        sequence = 0; remote = new BluetoothGatt();
        BluetoothGattService advertised = new BluetoothGattService(OmiBle.SERVICE);
        audio = new BluetoothGattCharacteristic(AUDIO); codec = new BluetoothGattCharacteristic(CODEC);
        ccc = new BluetoothGattDescriptor(CCC); audio.add(ccc);
        advertised.add(audio); advertised.add(codec); remote.services.put(OmiBle.SERVICE, advertised);
        service.adapter.next = remote;
    }
    static void start() throws Exception {
        nextRemote(); SharedPreferences.values.put("address", "AA:BB:CC:DD:EE:FF"); service.onCreate();
        check(service.onStartCommand(new Intent().setAction(OmiCaptureService.ACTION_START), 0, 1) == Service.START_NOT_STICKY,
            "explicit non-sticky service start");
        until(() -> service.adapter.connections == 1);
        check(field(service, "ble").getClass() == OmiBle.class, "service constructs real OmiBle");
        check(OmiCaptureService.active && OmiCaptureService.startedAt == 0, "active preparation is not PCM");
        check(!OmiCaptureService.state.startsWith("Recording"), "no false recording before PCM");
    }
    static void ready() {
        remote.callback.onConnectionStateChange(remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("mtu".equals(remote.pending), "MTU first");
        remote.pending = null; remote.callback.onMtuChanged(remote, 247, 0); Handler.drain();
        check("discover".equals(remote.pending), "discovery after MTU");
        remote.pending = null; remote.callback.onServicesDiscovered(remote, 0); Handler.drain();
        check(remote.pending == codec, "codec read after discovery");
        remote.pending = null; remote.callback.onCharacteristicRead(remote, codec, new byte[]{21}, 0); Handler.drain();
        check(remote.pending == ccc, "audio CCC after codec 21");
        remote.pending = null; remote.callback.onDescriptorWrite(remote, ccc, 0); Handler.drain();
        check(!remote.closed && remote.pending == null, "audio ready with optional services absent");
    }
    static byte[] packet(int seq, byte[] payload) {
        byte[] bytes = new byte[payload.length + 3]; bytes[0] = (byte)seq; bytes[1] = (byte)(seq >>> 8);
        System.arraycopy(payload, 0, bytes, 3, payload.length); return bytes;
    }
    static void frame(byte[] payload) {
        remote.callback.onCharacteristicChanged(remote, audio, packet(sequence++, payload)); Handler.drain();
    }
    static void firstPcm() throws Exception {
        frame(opus); check(Recordings.total() == 0 && OmiCaptureService.startedAt == 0, "buffered frame does not prove PCM");
        frame(opus); until(() -> Recordings.total() == 640);
        check(OmiCaptureService.state.startsWith("Recording") && OmiCaptureService.startedAt > 0, "real decode starts recording");
    }
    static void drop() {
        remote.callback.onConnectionStateChange(remote, 8, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
        check(remote.closed && remote.disconnected, "production disconnect closes failed GATT");
        check(OmiCaptureService.active && OmiCaptureService.transport.contains("retry in 1 seconds"), "real retry remains active");
    }
    static void gapProcessedWithoutRollover() throws Exception {
        until(() -> Recordings.text("segment-1").stream().anyMatch(t -> t.startsWith("[Omi audio gap")));
        check(Recordings.ids().equals(List.of("segment-1")) && Recordings.finishedIds().isEmpty(),
            "processed gap alone neither finalizes old segment nor creates an empty successor");
    }
    static void retry() {
        int count = service.adapter.connections; nextRemote(); Handler.advance(999);
        check(service.adapter.connections == count, "no reconnect before exact backoff");
        Handler.advance(1); check(service.adapter.connections == count + 1, "one real reconnect at backoff deadline");
    }
    static byte[] decoded(int count) throws Exception {
        OpusDecoder decoder = new OpusDecoder(16000, 1); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int n = 0; n < count; n++) {
            short[] pcm = new short[1920]; int length = decoder.decode(opus, 0, opus.length, pcm, 0, pcm.length, false);
            check(length == 320, "reference Concentus frame is exactly 20 ms PCM");
            for (int i = 0; i < length; i++) { bytes.write(pcm[i] & 255); bytes.write((pcm[i] >>> 8) & 255); }
        }
        return bytes.toByteArray();
    }
    static void stop() throws Exception {
        service.onStartCommand(new Intent().setAction(OmiCaptureService.ACTION_STOP), 0, 2);
        until(() -> !OmiCaptureService.active);
    }
    static void oneSegment(int frames, boolean gap) throws Exception {
        check(Recordings.ids().equals(List.of("segment-1")) && Recordings.finishedIds().equals(Recordings.ids()), "exactly one finished segment");
        check(Arrays.equals(Recordings.bytes("segment-1"), decoded(frames)), "one-segment bytes retained sample-exactly");
        check(Recordings.text("segment-1").stream().filter(t -> t.startsWith("[Omi audio gap")).count() == (gap ? 1 : 0), "exact gap count without invented startup gap");
        check("saved".equals(Recordings.status("segment-1")), "valid PCM saved");
    }
    static void splitSegments(int oldFrames) throws Exception {
        check(Recordings.ids().equals(List.of("segment-1", "segment-2")), "recovered PCM must create a distinct ordered segment");
        check(Recordings.finishedIds().equals(Recordings.ids()), "segments finish in creation order");
        check(Arrays.equals(Recordings.bytes("segment-1"), decoded(oldFrames)), "all earlier PCM retained exactly, no resumed splice");
        check(Arrays.equals(Recordings.bytes("segment-2"), decoded(1)), "resumed segment starts with fresh-decoder PCM, no loss or silence");
        check(Recordings.text("segment-1").stream().filter(t -> t.startsWith("[Omi audio gap")).count() == 1, "one old-segment gap marker");
        check(Recordings.text("segment-2").stream().noneMatch(t -> t.startsWith("[Omi audio gap")), "old gap not misfiled to new segment");
        check(Recordings.text("segment-2").stream().anyMatch(t -> t.startsWith("[Omi audio resumed after a gap")), "continuation explicitly marked");
        check("segment-2".equals(OmiCaptureService.sessionId), "public session ID follows resumed segment");
        check("saved".equals(Recordings.status("segment-1")) && "saved".equals(Recordings.status("segment-2")), "both segments saved");
        List<String> log = Recordings.log();
        check(log.indexOf("finish:segment-1") < log.indexOf("create:segment-2"), "old archive finishes before successor exists");
    }
    static void scenario(String name) throws Exception {
        start();
        switch (name) {
            case "startup_retry":
                drop(); check(Recordings.total() == 0 && OmiCaptureService.startedAt == 0, "startup failure has no PCM");
                retry(); ready(); firstPcm(); stop(); oneSegment(1, false); break;
            case "sequence_loss":
                ready(); firstPcm(); sequence++; frame(opus);
                check(OmiCaptureService.state.contains("Recovering") && Recordings.total() == 640, "sequence loss enters service recovery without fabricated PCM");
                gapProcessedWithoutRollover();
                frame(opus); until(() -> Recordings.total() == 1280); stop(); splitSegments(1);
                check(service.adapter.connections == 1, "same-link packet gap needs no reconnect"); break;
            case "malformed_opus":
                ready(); firstPcm(); frame(new byte[]{3}); until(() -> Recordings.total() == 1280);
                frame(opus); check(OmiCaptureService.transport.startsWith("Invalid Opus frame"), "Concentus rejection reaches real service");
                check(OmiCaptureService.state.contains("Recovering"), "malformed Opus enters recovery");
                gapProcessedWithoutRollover();
                frame(opus); frame(opus); until(() -> Recordings.total() == 1920);
                stop(); splitSegments(2); check(service.adapter.connections == 1, "decode gap remains on current link"); break;
            case "link_recovery":
                ready(); firstPcm(); BluetoothGatt old = remote; BluetoothGattCharacteristic oldAudio = audio;
                drop(); check(OmiCaptureService.state.contains("Recovering"), "link loss visible in service");
                gapProcessedWithoutRollover();
                retry(); ready(); frame(opus);
                check(Recordings.ids().size() == 1, "GATT ready and buffered frame do not roll segment");
                old.callback.onCharacteristicChanged(old, oldAudio, packet(2, opus)); Handler.drain();
                check(Recordings.total() == 640, "stale old-link PCM ignored");
                frame(opus); until(() -> Recordings.total() == 1280); stop(); splitSegments(1);
                check(service.adapter.connections == 2, "one connection plus one reconnect"); break;
            case "recovery_before_deadline":
                ready(); firstPcm(); drop(); gapProcessedWithoutRollover();
                nextRemote(); Handler.advance(119999); ready(); frame(opus); frame(opus);
                until(() -> Recordings.total() == 1280);
                Handler.advance(2);
                check(OmiCaptureService.active && OmiCaptureService.state.startsWith("Recording"),
                    "accepted PCM immediately before deadline extends recovery beyond old deadline");
                stop(); splitSegments(1); break;
            case "recovery_at_deadline":
            case "startup_at_deadline": {
                boolean recovering = name.equals("recovery_at_deadline");
                if (recovering) { ready(); firstPcm(); }
                drop();
                OmiBle owner = (OmiBle) field(service, "ble");
                Runnable pendingRetry = (Runnable) field(owner, "retryTask");
                BluetoothGatt oldLink = remote; BluetoothGattCharacteristic oldCharacteristic = audio;
                Handler.advance(120000); until(() -> !OmiCaptureService.active);
                check(OmiCaptureService.state.contains(recovering ? "recovery timed out" : "startup timed out"),
                    "composed service terminates at exact 120-second no-PCM boundary");
                int attempts = service.adapter.connections, retained = Recordings.total();
                pendingRetry.run();
                oldLink.callback.onConnectionStateChange(oldLink, 0, BluetoothProfile.STATE_CONNECTED);
                oldLink.callback.onCharacteristicChanged(oldLink, oldCharacteristic, packet(2, opus));
                Handler.advance(180000);
                check(!OmiCaptureService.active && service.adapter.connections == attempts
                        && Recordings.total() == retained,
                    "terminal deadline fences actual stale retries and late GATT audio");
                check(retained == (recovering ? 640 : 0), "deadline retains exact earlier PCM only");
                if (recovering) check(Arrays.equals(Recordings.bytes("segment-1"), decoded(1)),
                    "timeout keeps saved PCM sample-exactly");
                break;
            }
            case "stop_backoff":
                ready(); firstPcm(); drop(); OmiBle ble = (OmiBle)field(service, "ble");
                Runnable staleRetry = (Runnable)field(ble, "retryTask"); check(staleRetry != null, "actual retry task scheduled before Stop");
                BluetoothGatt stopped = remote; BluetoothGattCharacteristic stoppedAudio = audio;
                stop(); int count = service.adapter.connections, bytes = Recordings.total();
                staleRetry.run(); stopped.callback.onConnectionStateChange(stopped, 0, BluetoothProfile.STATE_CONNECTED);
                stopped.callback.onCharacteristicChanged(stopped, stoppedAudio, packet(2, opus)); Handler.advance(180000);
                check(!OmiCaptureService.active && service.adapter.connections == count && Recordings.total() == bytes,
                    "Stop fences stale retry, stale GATT callbacks and three minutes of deadlines");
                check(field(ble, "retryTask") == null && field(ble, "handler") == null, "retry and worker handles cleared");
                oneSegment(1, true); break;
            default: throw new AssertionError("unknown scenario " + name);
        }
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { backgroundFailure.compareAndSet(null, error); error.printStackTrace(); });
        try {
            OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            short[] samples = new short[320];
            for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
            byte[] encoded = new byte[1275]; int length = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
            check(length > 0, "fixture encoded by real vendored Concentus"); opus = Arrays.copyOf(encoded, length);
            try { scenario(args[0]); }
            finally { OmiCaptureService.requestStopCapture(); until(() -> !OmiCaptureService.active); }
            check(backgroundFailure.get() == null, "no background exceptions");
            check(PowerManager.held == 0, "wake lock released");
            check(PreviewModel.created == PreviewModel.closed && PreviewRecognizer.created == PreviewRecognizer.closed, "native doubles closed exactly once");
            check(RefinementJobService.pauses == 1 && RefinementJobService.schedules == 1, "capture/refinement priority hooks bracket actual composition owner");
            check(Service.foregroundStarts == 1 && Service.foregroundStops == 1, "single FGS lifecycle across recovery");
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() &&
                Set.of("OmiBle", "omi-capture", "omi-offline-speech").contains(t.getName())), "all actual capture/BLE/speech threads exited");
            System.out.println("OmiCompositionHostTest PASS " + args[0] + ": " + assertions + " assertions; segments=" + Recordings.ids() + "; bytes=" + Recordings.total());
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
