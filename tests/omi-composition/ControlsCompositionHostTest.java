package app.nottheomi.ai;

import static app.nottheomi.ai.OmiCompositionHostTest.*;
import android.app.Service;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Real service + BLE, including actual JVM exit barriers and wire button ingress. */
public final class ControlsCompositionHostTest {
    static final UUID BUTTON_SERVICE = UUID.fromString("23ba7924-0000-1000-7450-346eac492e92");
    static final UUID BUTTON = UUID.fromString("23ba7925-0000-1000-7450-346eac492e92");
    static final int LONG = 3, PRESS = 4, RELEASE = 5; // Reserved wire values, deliberately not ButtonEvent actions.
    static BluetoothGattCharacteristic button;
    static BluetoothGattDescriptor buttonCcc;
    static final List<CountDownLatch> gates = new ArrayList<>();
    static int captures;

    static CountDownLatch gate() { CountDownLatch gate = new CountDownLatch(1); gates.add(gate); return gate; }
    static OmiBle ble() throws Exception { return (OmiBle) field(service, "ble"); }
    static OmiBle.Listener listener(OmiBle owner) throws Exception { return (OmiBle.Listener) field(owner, "listener"); }
    static HandlerThread worker(OmiBle owner) throws Exception { return (HandlerThread) field(owner, "worker"); }
    static void command(String action) {
        check(service.onStartCommand(new Intent().setAction(action), 0, 7) == Service.START_NOT_STICKY,
            "explicit non-sticky command " + action);
    }
    static void prepareRemote() {
        nextRemote();
        BluetoothGattService buttons = new BluetoothGattService(BUTTON_SERVICE);
        button = new BluetoothGattCharacteristic(BUTTON); buttonCcc = new BluetoothGattDescriptor(CCC);
        button.add(buttonCcc); buttons.add(button); remote.services.put(BUTTON_SERVICE, buttons);
    }
    static void create() {
        SharedPreferences.values.put("address", "AA:BB:CC:DD:EE:FF");
        service.onCreate(); prepareRemote();
    }
    static void controls() throws Exception {
        create(); command(OmiCaptureService.ACTION_CONTROLS);
        until(() -> service.adapter.connections == 1);
        controlsReady();
    }
    static void capture() throws Exception {
        create(); captures++; command(OmiCaptureService.ACTION_START);
        until(() -> service.adapter.connections == 1);
        audioReady(); firstPcm();
    }
    static void controlsReady() throws Exception {
        check(ble().getClass() == OmiBle.class, "controls owner is production OmiBle");
        remote.callback.onConnectionStateChange(remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("discover".equals(remote.pending), "controls skips MTU and discovers buttons directly");
        remote.pending = null; remote.callback.onServicesDiscovered(remote, 0); Handler.drain();
        check(remote.pending == buttonCcc, "controls subscribes only to button CCC");
        remote.pending = null; remote.callback.onDescriptorWrite(remote, buttonCcc, 0); Handler.drain();
        check(remote.pending == null && !remote.closed && OmiCaptureService.controlsActive && !OmiCaptureService.active,
            "controls public idle state never claims recording");
        check(remote.reads.isEmpty() && remote.writes.equals(List.of(buttonCcc))
                && !remote.calls.contains("mtu") && field(ble(), "decoder") == null,
            "controls has no codec read, audio CCC, MTU, or decoder");
        check(OmiCaptureService.controlsState != null && !OmiCaptureService.controlsState.isEmpty(),
            "controls exposes a separate public status");
    }
    static void audioReady() {
        remote.callback.onConnectionStateChange(remote, 0, BluetoothProfile.STATE_CONNECTED); Handler.drain();
        check("mtu".equals(remote.pending), "recording still negotiates audio MTU");
        remote.pending = null; remote.callback.onMtuChanged(remote, 247, 0); Handler.drain();
        check("discover".equals(remote.pending), "recording discovery follows MTU");
        remote.pending = null; remote.callback.onServicesDiscovered(remote, 0); Handler.drain();
        check(remote.pending == codec, "recording reads codec");
        remote.pending = null; remote.callback.onCharacteristicRead(remote, codec, new byte[]{21}, 0); Handler.drain();
        check(remote.pending == ccc, "recording audio CCC precedes button CCC");
        remote.pending = null; remote.callback.onDescriptorWrite(remote, ccc, 0); Handler.drain();
        check(remote.pending == buttonCcc, "recording supports real button notifications");
        remote.pending = null; remote.callback.onDescriptorWrite(remote, buttonCcc, 0); Handler.drain();
        check(remote.pending == null && OmiCaptureService.active && !OmiCaptureService.controlsActive,
            "recording and idle controls are mutually exclusive");
    }
    static void wireButton(BluetoothGatt link, BluetoothGattCharacteristic target, int event) {
        // Stock firmware's exact eight-byte little-endian ButtonEvent encoding.
        link.callback.onCharacteristicChanged(link, target, new byte[]{(byte) event, 0, 0, 0, 0, 0, 0, 0});
    }
    static void press(int event) { wireButton(remote, button, event); Handler.drain(); }
    static void noCaptureResources() {
        check(PreviewModelInstaller.entered.getCount() == 1 && PreviewModel.created == 0 && PreviewRecognizer.created == 0,
            "idle controls never prepares or constructs speech resources");
        check(Recordings.gets == 0 && Recordings.ids().isEmpty() && Recordings.total() == 0,
            "idle controls never opens storage or creates an archive");
        check(PowerManager.held == 0 && PowerManager.acquires == 0 && OmiCaptureService.startedAt == 0,
            "idle controls never takes a capture wake lock or starts the PCM clock");
        check(RefinementJobService.pauses == 0, "idle controls does not pause refinement as capture");
    }
    static void quietNoRevival(int connections) throws Exception {
        Handler.advance(180000);
        until(() -> !OmiCaptureService.active && !OmiCaptureService.controlsActive);
        check(service.adapter.connections == connections, "terminal disconnect never opens another GATT");
    }
    static void afterExit(HandlerThread oldWorker, BluetoothGatt oldLink, boolean archiveFinished) {
        service.adapter.beforeConnect = () -> {
            if (oldWorker.isAlive() || !oldLink.closed || !oldLink.disconnected)
                throw new AssertionError("replacement connect before native close AND actual old worker exit");
            if (archiveFinished && (!Recordings.finishedIds().equals(Recordings.ids())
                    || PreviewModel.created != PreviewModel.closed || PreviewRecognizer.created != PreviewRecognizer.closed
                    || PowerManager.held != 0))
                throw new AssertionError("standby connect before archive/ASR/wake-lock teardown");
        };
    }
    static void stopToStandby(boolean disconnectDuringDrain) throws Exception {
        capture(); OmiBle old = ble(); BluetoothGatt oldLink = remote;
        BluetoothGattCharacteristic oldAudio = audio, oldButton = button;
        HandlerThread oldWorker = worker(old); oldWorker.exitGate = gate();
        Recordings.audioGate = gate(); PreviewRecognizer.finalGate = gate();
        frame(opus); until(() -> Recordings.audioEntered.getCount() == 0);
        frame(opus); // Third accepted PCM frame is queued behind a blocked archive commit.
        check(Recordings.total() == 640, "archive stall is real, not a fabricated saved prefix");
        prepareRemote(); afterExit(oldWorker, oldLink, true);
        if (disconnectDuringDrain) command(OmiCaptureService.ACTION_STOP);
        else {
            wireButton(oldLink, oldButton, ButtonEvent.DOUBLE); Handler.drain(); // Legacy default is Stop.
        }
        until(() -> oldWorker.exitEntered.getCount() == 0);
        check(oldLink.closed && oldWorker.isAlive() && OmiCaptureService.active && !OmiCaptureService.controlsActive,
            "Stop closes native GATT but keeps capture busy through actual worker exit/drain");
        check(service.adapter.connections == 1 && Recordings.finishedIds().isEmpty(), "no early standby or finalization");
        if (disconnectDuringDrain) OmiCaptureService.requestDisconnect();
        // Synchronously revoked old transport callbacks cannot append more PCM or start a new capture.
        oldLink.callback.onCharacteristicChanged(oldLink, oldAudio, packet(20, opus));
        SharedPreferences.values.put("single_action", "start"); wireButton(oldLink, oldButton, ButtonEvent.SINGLE);
        Handler.drain();
        Recordings.audioGate.countDown(); until(() -> Recordings.total() == 1920);
        check(service.adapter.connections == 1 && !OmiCaptureService.controlsActive, "archive drain cannot bypass BLE exit barrier");
        oldWorker.exitGate.countDown(); until(() -> PreviewRecognizer.finalEntered.getCount() == 0);
        check(!oldWorker.isAlive() && OmiCaptureService.active && !OmiCaptureService.controlsActive,
            "ASR final result remains a separate busy drain after BLE exit");
        check(service.adapter.connections == 1 && Recordings.finishedIds().isEmpty(), "ASR finalization precedes standby");
        PreviewRecognizer.finalGate.countDown();
        if (disconnectDuringDrain) quietNoRevival(1);
        else {
            until(() -> service.adapter.connections == 2 && OmiCaptureService.controlsActive && !OmiCaptureService.active);
            check(ble() != old, "Stop builds a fresh controls-only transport, not a reused audio transport");
            controlsReady();
            check(Recordings.gets == 1 && PreviewModel.created == 1, "standby opens no second archive or speech model");
        }
        oneSegment(3, false);
        check(Recordings.text("segment-1").contains("stop final") && PreviewRecognizer.accepted == 3,
            "all pre-Stop accepted PCM and final ASR are retained before standby/disconnect");
    }
    static void controlsToCapture(String action, boolean cancel) throws Exception {
        controls(); OmiBle old = ble(); HandlerThread oldWorker = worker(old);
        BluetoothGatt oldLink = remote; BluetoothGattCharacteristic oldButton = button;
        OmiBle.Listener oldListener = listener(old); long oldGeneration = old.connectionGeneration();
        oldWorker.exitGate = gate(); prepareRemote(); afterExit(oldWorker, oldLink, false);
        SharedPreferences.values.put("single_action", action);
        wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drain();
        until(() -> oldWorker.exitEntered.getCount() == 0);
        check(oldWorker.isAlive() && oldLink.closed && !OmiCaptureService.controlsActive,
            "button start/toggle revokes idle before actual closing thread exit");
        check(PreviewModel.created == 0 && Recordings.gets == 0 && service.adapter.connections == 1,
            "capture resources/connect wait for controls worker exit, not its close callback");
        if (cancel) OmiCaptureService.requestDisconnect();
        oldWorker.exitGate.countDown();
        if (cancel) {
            quietNoRevival(1); noCaptureResources(); return;
        }
        captures++;
        until(() -> service.adapter.connections == 2);
        audioReady(); firstPcm();
        check(ble() != old && !oldWorker.isAlive(), "capture owns a new transport after real controls exit");
        // Replay both the service's retired listener and actual old-GATT notifications.
        SharedPreferences.values.put("single_action", "bookmark");
        SharedPreferences.values.put("double_action", "stop");
        oldListener.onButton(ButtonEvent.SINGLE, oldGeneration);
        oldListener.onButton(ButtonEvent.DOUBLE, oldGeneration);
        oldListener.onButton(ButtonEvent.DOUBLE);
        wireButton(oldLink, oldButton, ButtonEvent.DOUBLE); Handler.drain();
        check(OmiCaptureService.active && Recordings.text("segment-1").stream().noneMatch(t -> t.contains("bookmark")),
            "retired service listener and old GATT cannot bookmark or stop the replacement recording");
        OmiCaptureService.requestDisconnect(); until(() -> !OmiCaptureService.active);
        oneSegment(1, false);
    }
    static void scenario(String name) throws Exception {
        switch (name) {
            case "controls_reject_phone":
            case "controls_reject_permission":
            case "controls_reject_notification":
            case "controls_stop_transition":
            case "controls_stop_intent_transition": {
                controls(); OmiBle old = ble(); HandlerThread oldWorker = worker(old);
                oldWorker.exitGate = gate();
                SharedPreferences.values.put("single_action", "start");
                press(ButtonEvent.SINGLE);
                until(() -> oldWorker.exitEntered.getCount() == 0);
                check(oldWorker.isAlive() && !OmiCaptureService.active && !OmiCaptureService.controlsActive,
                    "pending capture remains behind the controls worker exit barrier");
                String rejection = null;
                switch (name) {
                    case "controls_reject_phone": CaptureService.active = true; rejection = "phone microphone"; break;
                    case "controls_reject_permission": Context.denied = "connect"; rejection = "Bluetooth permissions"; break;
                    case "controls_reject_notification": android.app.NotificationManager.enabled = false; rejection = "notifications"; break;
                    case "controls_stop_transition": OmiCaptureService.requestStopCapture(); break;
                    case "controls_stop_intent_transition": command(OmiCaptureService.ACTION_STOP); break;
                }
                oldWorker.exitGate.countDown(); until(ControlsCompositionHostTest::workersExited);
                Handler.drain(); // Joiner exit precedes its queued main-thread cleanup.
                check(field(service, "owner") == null && !(Boolean) field(service, "foreground")
                        && Service.foregroundStarts == Service.foregroundStops,
                    "rejected/cancelled pending capture releases owner and foreground without a follow-up Disconnect");
                quietNoRevival(1); noCaptureResources();
                if (rejection != null) check(OmiCaptureService.state.contains(rejection), "rejection reason remains visible");
                break;
            }
            case "controls_media": {
                controls(); SharedPreferences.values.put("single_action", "media_pause");
                SharedPreferences.values.put("double_action", "media_play_pause");
                press(ButtonEvent.SINGLE); press(ButtonEvent.DOUBLE);
                check(ButtonActions.media == 2, "both configured media gestures dispatched in standby");
                noCaptureResources();
                check(OmiCaptureService.controlsActive && service.adapter.connections == 1, "media leaves standby unchanged");
                break;
            }
            case "capture_media": {
                capture();
                SharedPreferences.values.put("single_action", "media_pause");
                SharedPreferences.values.put("double_action", "media_play_pause");
                press(ButtonEvent.SINGLE); press(ButtonEvent.DOUBLE);
                check(ButtonActions.media == 2 && OmiCaptureService.active, "media does not stop capture");
                check(Recordings.finishedIds().isEmpty() && service.adapter.connections == 1, "media leaves recording transport intact");
                OmiCaptureService.requestDisconnect(); until(() -> !OmiCaptureService.active); oneSegment(1, false);
                break;
            }
            case "controls_idle": {
                controls(); noCaptureResources();
                frame(opus); frame(opus); press(ButtonEvent.SINGLE); press(ButtonEvent.DOUBLE); press(LONG);
                Handler.advance(180000);
                check(OmiCaptureService.controlsActive && !OmiCaptureService.active && service.adapter.connections == 1 && !remote.closed,
                    "idle default bookmark/stop/LONG and incoming audio never start recording or an audio watchdog");
                noCaptureResources();
                OmiBle old = ble(); HandlerThread oldWorker = worker(old); oldWorker.exitGate = gate();
                command(OmiCaptureService.ACTION_DISCONNECT);
                until(() -> oldWorker.exitEntered.getCount() == 0);
                check(!OmiCaptureService.controlsActive && oldWorker.isAlive() && remote.closed, "Disconnect revokes idle before native worker exit");
                oldWorker.exitGate.countDown(); until(() -> !oldWorker.isAlive());
                quietNoRevival(1); break;
            }
            case "controls_start_exit": controlsToCapture("start", false); break;
            case "controls_toggle_exit": controlsToCapture("toggle", false); break;
            case "controls_disconnect_transition": controlsToCapture("start", true); break;
            case "controls_stop_standby": stopToStandby(false); break;
            case "controls_disconnect_drain": stopToStandby(true); break;
            case "controls_stop_backoff": {
                capture(); OmiBle old = ble(); HandlerThread oldWorker = worker(old);
                BluetoothGatt oldLink = remote; BluetoothGattCharacteristic oldButton = button;
                drop(); gapProcessedWithoutRollover(); Runnable staleRetry = (Runnable) field(old, "retryTask");
                check(staleRetry != null, "recording owns a real retry before intentional Stop");
                prepareRemote(); afterExit(oldWorker, oldLink, true); OmiCaptureService.requestStopCapture();
                until(() -> service.adapter.connections == 2 && OmiCaptureService.controlsActive);
                controlsReady(); oneSegment(1, true);
                SharedPreferences.values.put("single_action", "start");
                staleRetry.run(); wireButton(oldLink, oldButton, ButtonEvent.SINGLE);
                Handler.advance(180000);
                check(service.adapter.connections == 2 && OmiCaptureService.controlsActive && !OmiCaptureService.active,
                    "Stop cancels old capture retry yet retains the fresh standby without stale revival");
                check(field(old, "retryTask") == null && field(old, "handler") == null && !oldWorker.isAlive(),
                    "old recording worker and retry handles are actually retired"); break;
            }
            case "controls_stale_gatt": {
                controls(); OmiBle old = ble(); BluetoothGatt oldLink = remote;
                BluetoothGattCharacteristic oldButton = button; long generation = old.connectionGeneration();
                SharedPreferences.values.put("single_action", "start");
                wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drainWorkers();
                check(!OmiCaptureService.active && service.adapter.connections == 1, "start is queued to main, not performed on BLE thread");
                oldLink.callback.onConnectionStateChange(oldLink, 8, BluetoothProfile.STATE_DISCONNECTED);
                check(old.connectionGeneration() != generation, "native loss revokes the queued button at callback ingress");
                Handler.drain(); noCaptureResources();
                check(OmiCaptureService.controlsActive && !OmiCaptureService.active, "main-queued stale start is fenced after native loss");
                prepareRemote(); Handler.advance(999); check(service.adapter.connections == 1, "controls retry honors backoff");
                Handler.advance(1); check(service.adapter.connections == 2, "controls naturally retries selected wearable");
                controlsReady(); wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drain();
                noCaptureResources();
                SharedPreferences.values.put("single_action", "shortcut");
                int launches = ButtonActions.launches;
                wireButton(remote, button, ButtonEvent.SINGLE); Handler.drainWorkers();
                check(ButtonActions.launches == launches, "shortcut remains queued until main dispatch");
                remote.callback.onConnectionStateChange(remote, 8, BluetoothProfile.STATE_DISCONNECTED); Handler.drain();
                check(ButtonActions.launches == launches && !OmiCaptureService.active, "native loss also fences main-queued shortcut");
                OmiCaptureService.requestDisconnect(); quietNoRevival(2); break;
            }
            case "controls_stale_listener": {
                controls(); OmiBle old = ble(); OmiBle.Listener oldListener = listener(old);
                long generation = old.connectionGeneration(); BluetoothGatt oldLink = remote;
                BluetoothGattCharacteristic oldButton = button;
                SharedPreferences.values.put("single_action", "start");
                wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drainWorkers();
                OmiCaptureService.requestDisconnect(); Handler.drain();
                oldListener.onButton(ButtonEvent.SINGLE, generation); oldListener.onButton(ButtonEvent.SINGLE);
                wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drain();
                quietNoRevival(1); noCaptureResources();
                check(oldLink.closed && field(old, "handler") == null, "explicit disconnect retires real listener/GATT owner"); break;
            }
            case "controls_live_buttons": {
                capture();
                press(ButtonEvent.SINGLE);
                until(() -> Recordings.text("segment-1").contains("[Omi button bookmark]"));
                SharedPreferences.values.put("single_action", "ignore"); press(ButtonEvent.SINGLE);
                SharedPreferences.values.put("single_action", "shortcut"); press(ButtonEvent.SINGLE);
                check(ButtonActions.launches == 1, "live action mapping takes effect without restarting capture");
                press(LONG); press(PRESS); press(RELEASE);
                check(ButtonActions.launches == 1 && OmiCaptureService.active, "firmware-reserved gestures remain ignored");
                SharedPreferences.values.put("single_action", "start"); press(ButtonEvent.SINGLE);
                check(service.adapter.connections == 1 && PreviewModel.created == 1, "start while recording is idempotent");
                OmiBle old = ble(); HandlerThread oldWorker = worker(old); BluetoothGatt oldLink = remote;
                prepareRemote(); afterExit(oldWorker, oldLink, true);
                SharedPreferences.values.put("single_action", "toggle");
                BluetoothGattCharacteristic oldButton = oldLink.getService(BUTTON_SERVICE).getCharacteristic(BUTTON);
                wireButton(oldLink, oldButton, ButtonEvent.SINGLE); Handler.drain();
                until(() -> service.adapter.connections == 2 && OmiCaptureService.controlsActive);
                controlsReady(); oneSegment(1, false);
                check(Recordings.text("segment-1").stream().filter(t -> t.equals("[Omi button bookmark]")).count() == 1,
                    "only original default bookmark is archived, mappings do not add phantom controls"); break;
            }
            case "controls_prepcm_stop": {
                create(); captures++; command(OmiCaptureService.ACTION_START);
                until(() -> service.adapter.connections == 1); audioReady();
                OmiCaptureService.requestStopCapture(); quietNoRevival(1);
                check(Recordings.total() == 0 && "cancelled".equals(Recordings.status("segment-1")), "pre-PCM Stop cancels archive without standby"); break;
            }
            case "controls_fatal_stop": {
                capture(); Context.denied = "connect"; Handler.advance(1000);
                until(() -> !OmiCaptureService.active); quietNoRevival(1);
                check(Recordings.finishedIds().equals(List.of("segment-1")) && "error".equals(Recordings.status("segment-1"))
                    && Arrays.equals(Recordings.bytes("segment-1"), decoded(1)), "fatal permission loss retains exact PCM and finalizes error without standby");
                break;
            }
            default: throw new AssertionError("unknown controls scenario " + name);
        }
    }
    static boolean workersExited() {
        return Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() &&
            (t.getName().equals("OmiBle") || t.getName().startsWith("omi-")));
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { backgroundFailure.compareAndSet(null, error); error.printStackTrace(); });
        try {
            encodeFixture();
            try { scenario(args[0]); }
            finally {
                gates.forEach(CountDownLatch::countDown);
                OmiCaptureService.requestDisconnect();
                until(() -> !OmiCaptureService.active && !OmiCaptureService.controlsActive && workersExited()
                    && Service.foregroundStarts == Service.foregroundStops);
            }
            check(backgroundFailure.get() == null, "no background exceptions");
            check(PowerManager.held == 0 && PreviewModel.created == PreviewModel.closed && PreviewRecognizer.created == PreviewRecognizer.closed,
                "all native speech doubles and wake locks closed exactly once");
            check(RefinementJobService.pauses == captures && RefinementJobService.schedules == captures,
                "only real recording episodes bracket refinement priority");
            check(Service.foregroundStarts == Service.foregroundStops && Service.foregroundStops > 0,
                "every real foreground lifetime is torn down after explicit Disconnect");
            check(workersExited(), "all actual BLE/capture/speech/transition threads exited");
            System.out.println("ControlsCompositionHostTest PASS " + args[0] + ": " + assertions
                + " assertions; segments=" + Recordings.ids() + "; bytes=" + Recordings.total());
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
