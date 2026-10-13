package app.nottheomi.ai;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;
import org.concentus.OpusDecoder;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Stock Omi client; writes enable audio/button CCCs or explicitly requested LED brightness.
 * Protocol verified at omi d1fdcb4cc4fbd021eac799630e476f144e6bc18a:
 * app/lib/services/devices/{models.dart,connectors/omi_connection.dart},
 * app/lib/utils/audio/wav_bytes.dart: LE16 packet sequence + u8 fragment index.
 * Fragment zero begins a new Opus frame; the following zero completes the old one.
 */
@SuppressLint("MissingPermission")
public final class OmiBle {
    /** Callbacks are FIFO on the BLE worker, except initial/disconnected optional state.
     * UI listeners must marshal to main. */
    public interface Listener {
        void onDevice(String address, String name); void onPcm(short[] samples);
        void onStatus(String status); void onGap(); void onButton(int event);
        /** Carry this token through a UI queue; revalidate with connectionGeneration()
         * before acting. The token belongs to the notification, not dispatch time. */
        default void onButton(int event, long generation) { onButton(event); }
        default void onButton(int event, long generation, long configuration) { onButton(event, generation); }
        default long buttonConfigurationGeneration() { return 0; }
        default boolean tripleEnabled() { return false; }
        /** Highest assigned tap count; legacy listeners keep their triple opt-in. */
        default int maxButtonTaps() { return tripleEnabled() ? 3 : 2; }
        default void onLedState(LedState state) {}
        default void onHapticStatus(String status) {}
        /** Standard BAS percentage, 0..100; -1 means unknown/unavailable. */
        default void onBattery(int percent) {}
    }
    /** Device-reported current brightness; not proof of flash persistence. */
    public static final class LedState {
        public final boolean supported, busy;
        public final int brightness;
        public final String message;
        public LedState(boolean supported, boolean busy, int brightness, String message) {
            this.supported = supported; this.busy = busy;
            this.brightness = brightness; this.message = message;
        }
    }
    public static final UUID SERVICE = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214");
    private static final UUID AUDIO = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214");
    private static final UUID CODEC = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214");
    // Exact stock UUIDs: app/lib/services/devices/models.dart at the pin above.
    private static final UUID BUTTON_SERVICE = UUID.fromString("23ba7924-0000-1000-7450-346eac492e92");
    private static final UUID BUTTON = UUID.fromString("23ba7925-0000-1000-7450-346eac492e92");
    // transport.c at the pin above: READ + WRITE, one unsigned byte 0..100.
    private static final UUID SETTINGS = UUID.fromString("19b10010-e8f2-537e-4f6c-d104768a1214");
    private static final UUID LED = UUID.fromString("19b10011-e8f2-537e-4f6c-d104768a1214");
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final long BATTERY_REFRESH_MS = 60000L;
    // Pinned firmware src/haptic.c: acknowledged one-byte write, 1 = 100 ms.
    private static final UUID HAPTIC_SERVICE = UUID.fromString("cab1ab95-2ea5-4f4d-bb56-874b72cfc984");
    private static final UUID HAPTIC = UUID.fromString("cab1ab96-2ea5-4f4d-bb56-874b72cfc984");
    private static final UUID FEATURES_SERVICE = UUID.fromString("19b10020-e8f2-537e-4f6c-d104768a1214");
    private static final UUID FEATURES = UUID.fromString("19b10021-e8f2-537e-4f6c-d104768a1214");
    private static final long HAPTIC_FRESH_MS = 2000;
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context; private final Listener listener;
    // One queue owns GATT state, framing, decode and listener delivery. Splitting
    // decode from button/gap delivery would reorder task boundaries.
    private final Object workerLock = new Object();
    private HandlerThread worker;
    private Handler handler;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner; private volatile BluetoothGatt gatt;
    private String selected; private boolean stopped = true, scanning; private int retry;
    private volatile int operation;
    private final AtomicLong selectionEpoch = new AtomicLong();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile long gattGeneration;
    private boolean controlsOnly;
    private long activeSelection;
    // Operations 1..5 are connect/MTU/discover/codec/audio CCC; 6 is idle,
    // 7 is button CCC (required in controls-only mode); 8/9/10 are LED
    // read/write/readback; 11 is BAS read;
    // 12 is the bounded settling delay before one incomplete-discovery recheck.
    // 13/15 are first/subsequent haptic writes; 14 reserves the inter-pulse gap.
    // 16 reads modern firmware capabilities before each pattern; legacy UUID alone is not support.
    // Audio and buttons keep flowing during optional operations.
    private Runnable deadline, audioDeadline, retryTask;
    private boolean audioReady, buttonReady, audioReceived, discoveryRechecked;
    private BluetoothGattCharacteristic codecCharacteristic, audioCharacteristic, buttonCharacteristic;
    private BluetoothGattDescriptor pendingDescriptor;
    private BluetoothGattCharacteristic ledCharacteristic;
    private boolean ledReadConfirmed, ledQuarantined;
    // ledQuarantined gates the shared optional ATT lane, including BAS reads.
    private BluetoothGattCharacteristic batteryCharacteristic;
    private Runnable batteryPoll;
    private BluetoothGattCharacteristic hapticCharacteristic;
    private BluetoothGattCharacteristic hapticFeatures;
    private Runnable hapticGap;
    private BooleanSupplier hapticAllowed;
    private String hapticPattern;
    private int hapticPulse;
    private long hapticGeneration, hapticRequestedAt;
    private int ledRequested = -1;
    private volatile LedState ledState = new LedState(false, false, -1,
        "LED brightness unknown; read during an existing live connection");
    private OpusDecoder decoder;
    private final FrameAssembler frames = new FrameAssembler();
    private final ButtonGestures gestures = new ButtonGestures();
    private Runnable gestureDeadline;
    private long gestureConfiguration;
    // In-memory, payload-free diagnostics. Never retain packet bytes, selection,
    // user strings or transcript text. Counters are approximate live snapshots.
    private enum Event { OPEN, CONNECTION_CALLBACK, NATIVE_LOSS, GATT_TIMEOUT, AUDIO_TIMEOUT, FAILURE, STOP_OR_REPLACE }
    private final ArrayDeque<String> diagnosticEvents = new ArrayDeque<>();
    private final AtomicLong ingressPackets = new AtomicLong(), lastIngressAt = new AtomicLong(-1);
    private volatile long openedConnections, decodedFrames, decodedSamples, lastDecodedAt = -1;
    private volatile int lastConnectionStatus = -1, negotiatedMtu = -1;
    private volatile Event lastCloseReason = Event.STOP_OR_REPLACE;
    private void diagnosticEvent(Event event, int value) {
        synchronized (diagnosticEvents) {
            if (diagnosticEvents.size() == 32) diagnosticEvents.removeFirst();
            diagnosticEvents.addLast("elapsed_ms=" + SystemClock.elapsedRealtime() + " event=" + event
                + " value=" + value + " operation=" + operation);
        }
    }
    String diagnostics() {
        long now = SystemClock.elapsedRealtime(), ingress = lastIngressAt.get(), decoded = lastDecodedAt;
        String history;
        synchronized (diagnosticEvents) { history = String.join("\n", diagnosticEvents); }
        return "ble_snapshot=approximate operation=" + operation + " attempts=" + openedConnections
            + " connection_status=" + lastConnectionStatus + " mtu=" + negotiatedMtu + " close_reason=" + lastCloseReason
            + " ingress_packets=" + ingressPackets.get() + " ingress_age_ms=" + (ingress < 0 ? -1 : Math.max(0, now-ingress))
            + " decoded_frames=" + decodedFrames + " decoded_samples=" + decodedSamples
            + " decoded_age_ms=" + (decoded < 0 ? -1 : Math.max(0, now-decoded)) + "\n" + history;
    }
    public OmiBle(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        listener.onLedState(ledState);
        listener.onBattery(-1);
    }
    public void readLedBrightness() { requestLed(false, -1); }
    public void setLedBrightness(int value) { requestLed(true, value); }
    /** Optional best-effort output. Never connects, retries or buffers alerts.
     * The guard is generation-bound by the caller and checked before each write.
     * Firmware self-stops each pulse; it has no BLE stop command. */
    public void requestDoublePulse(BooleanSupplier allowed) { requestPattern(HapticPatterns.DOUBLE, allowed); }
    /** Stock, bounded presets only. Unknown IDs fall back to the double pattern. */
    public void requestPattern(String pattern, BooleanSupplier allowed) {
        final String selectedPattern = HapticPatterns.normalize(pattern);
        synchronized (workerLock) {
            BluetoothGatt remote = gatt;
            long generation = connectionGeneration.get(), requestedAt = SystemClock.elapsedRealtime();
            int entryOperation = operation;
            if (handler == null) { listener.onHapticStatus("Connect your Omi first"); return; }
            handler.post(() -> {
                if (!current(remote) || generation != connectionGeneration.get() || !permitted(allowed)) return;
                if (!(audioReady || (controlsOnly && buttonReady)) || entryOperation != 6 || operation != 6) {
                    listener.onHapticStatus("Omi busy — notification pulse skipped"); return;
                }
                if (ledQuarantined) {
                    listener.onHapticStatus("Optional controls unavailable until the next natural reconnect"); return;
                }
                if (SystemClock.elapsedRealtime() - requestedAt > HAPTIC_FRESH_MS) return;
                try {
                    BluetoothGattCharacteristic target = findHaptic(remote);
                    if (target == null) { listener.onHapticStatus("This Omi firmware does not expose a writable vibration control"); return; }
                    hapticCharacteristic = target; hapticAllowed = allowed;
                    hapticPattern = selectedPattern; hapticPulse = 0;
                    hapticGeneration = generation; hapticRequestedAt = requestedAt;
                    hapticFeatures = findHapticFeatures(remote);
                    if (hapticFeatures == null) {
                        hapticFailure(remote, "Vibration unsupported: modern Omi capability service required", false); return;
                    }
                    arm(16);
                    if (!hapticCurrent(remote)) { hapticFailure(remote, "Notification pulse cancelled", false); return; }
                    if (!remote.readCharacteristic(hapticFeatures))
                        hapticFailure(remote, "Cannot verify vibration capability; pulse skipped", false);
                } catch (RuntimeException e) { hapticFailure(remote, "Vibration unavailable; optional controls paused", true); }
            });
        }
    }
    private static boolean permitted(BooleanSupplier allowed) {
        try { return allowed != null && allowed.getAsBoolean(); }
        catch (RuntimeException e) { return false; }
    }
    private BluetoothGattCharacteristic findHaptic(BluetoothGatt remote) {
        BluetoothGattService service = remote.getService(HAPTIC_SERVICE);
        if (service == null || !HAPTIC_SERVICE.equals(service.getUuid())) return null;
        BluetoothGattCharacteristic target = service.getCharacteristic(HAPTIC);
        return target != null && HAPTIC.equals(target.getUuid()) &&
            (target.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? target : null;
    }
    private BluetoothGattCharacteristic findHapticFeatures(BluetoothGatt remote) {
        BluetoothGattService service = remote.getService(FEATURES_SERVICE);
        if (service == null || !FEATURES_SERVICE.equals(service.getUuid())) return null;
        BluetoothGattCharacteristic target = service.getCharacteristic(FEATURES);
        return target != null && FEATURES.equals(target.getUuid()) &&
            (target.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? target : null;
    }
    private void hapticFeaturesRead(BluetoothGatt remote, BluetoothGattCharacteristic target, byte[] value, int status) {
        postEvent(() -> {
            if (!current(remote, 16) || ledQuarantined || target != hapticFeatures) return;
            try {
                if (!hapticCurrent(remote)) { hapticFailure(remote, "Notification pulse cancelled", false); return; }
                if (findHapticFeatures(remote) != target || status != BluetoothGatt.GATT_SUCCESS
                        || value == null || value.length != 4) {
                    hapticFailure(remote, "Vibration capability unverified; pulse skipped", false); return;
                }
                // Exact 32-bit little-endian modern Omi feature word; bit 5 = haptic.
                int features = (value[0] & 255) | ((value[1] & 255) << 8)
                    | ((value[2] & 255) << 16) | ((value[3] & 255) << 24);
                if ((features & (1 << 5)) == 0) {
                    hapticFailure(remote, "Vibration unsupported by this Omi firmware", false); return;
                }
                writeHaptic(remote, 13);
            } catch (RuntimeException e) {
                hapticFailure(remote, "Vibration capability check failed; optional controls paused", true);
            }
        });
    }
    private boolean hapticCurrent(BluetoothGatt remote) {
        return current(remote) && !ledQuarantined && hapticGeneration == connectionGeneration.get()
            && SystemClock.elapsedRealtime() - hapticRequestedAt <= HAPTIC_FRESH_MS && permitted(hapticAllowed);
    }
    private void clearHaptic() {
        if (hapticGap != null) handler.removeCallbacks(hapticGap);
        hapticGap = null; hapticAllowed = null; hapticCharacteristic = null; hapticFeatures = null;
        hapticPattern = null; hapticPulse = 0;
    }
    private void hapticFailure(BluetoothGatt remote, String message, boolean quarantine) {
        if (!current(remote)) return;
        cancelDeadline(); clearHaptic(); operation = 6;
        if (quarantine) {
            quarantineOptional();
            publishLed(false, false, -1, "Optional controls unavailable until the next natural reconnect");
        }
        listener.onHapticStatus(message);
        // No audio gap, watchdog reset, disconnect or write retry.
    }
    private void writeHaptic(BluetoothGatt remote, int stage) {
        if (!hapticCurrent(remote)) { hapticFailure(remote, "Notification pulse cancelled", false); return; }
        try {
            if (findHaptic(remote) != hapticCharacteristic) {
                hapticFailure(remote, "Vibration control changed; pulse skipped", false); return;
            }
            hapticCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!hapticCharacteristic.setValue(new byte[]{(byte)HapticPatterns.preset(hapticPattern, hapticPulse)})) {
                hapticFailure(remote, "Cannot prepare vibration command", false); return;
            }
            arm(stage);
            if (!hapticCurrent(remote)) { hapticFailure(remote, "Notification pulse cancelled", false); return; }
            if (!remote.writeCharacteristic(hapticCharacteristic))
                hapticFailure(remote, "Vibration command rejected; pulse skipped", false);
        } catch (RuntimeException e) { hapticFailure(remote, "Vibration submission uncertain; optional controls paused", true); }
    }
    private void hapticWritten(BluetoothGatt remote, BluetoothGattCharacteristic target, int status) {
        if (!current(remote) || ledQuarantined || target != hapticCharacteristic || (operation != 13 && operation != 15)) return;
        if (status != BluetoothGatt.GATT_SUCCESS) { hapticFailure(remote, "Vibration command failed; pulse skipped", false); return; }
        if (!hapticCurrent(remote)) { hapticFailure(remote, "Notification pulse cancelled", false); return; }
        if (hapticPulse + 1 == HapticPatterns.pulseCount(hapticPattern)) {
            String label = HapticPatterns.label(hapticPattern);
            cancelDeadline(); clearHaptic(); operation = 6;
            listener.onHapticStatus(label + " commands acknowledged by Omi"); return;
        }
        long spacing = HapticPatterns.gapAfterMs(hapticPattern, hapticPulse);
        hapticPulse++;
        cancelDeadline(); operation = 14;
        hapticGap = new Runnable() { @Override public void run() {
            if (hapticGap != this || !current(remote, 14)) return;
            hapticGap = null; writeHaptic(remote, 15);
        }};
        // ACK-relative spacing exceeds the preceding preset's nominal duration.
        // ATT acknowledgement proves transport acceptance, not the physical feel.
        handler.postDelayed(hapticGap, spacing);
    }
    /** Invalidated synchronously by selection/Stop and native loss, and on close.
     * Compare against Listener.onButton(event, generation) at downstream dispatch.
     * This is an instance-local invalidation token, not connection readiness. */
    public long connectionGeneration() { return connectionGeneration.get(); }
    private void requestLed(boolean write, int value) {
        // Capture the connection at entry, not at dispatch: commands must never
        // migrate across a queued Stop, selection, or natural reconnect.
        synchronized (workerLock) {
            long request = selectionEpoch.get(); BluetoothGatt remote = gatt;
            int entryOperation = operation;
            if (handler == null) { listener.onLedState(ledState); return; }
            handler.post(() -> {
                if (request != selectionEpoch.get() || remote != gatt) return;
                if (ledState.busy) { listener.onLedState(ledState); return; }
                if (!current(remote) || !audioReady || operation != 6 || entryOperation != 6) {
                    publishLed(ledState.supported, false, ledState.brightness,
                        "LED request rejected: live audio connection must be ready and idle"); return;
                }
                if (ledQuarantined) {
                    publishLed(false, false, -1, "LED controls unavailable until the next natural reconnect"); return;
                }
                if (write && (value < 0 || value > 100)) {
                    publishLed(ledState.supported, false, ledState.brightness, "LED brightness must be 0–100"); return;
                }
                try {
                    BluetoothGattCharacteristic target = findLed(remote);
                    if (target == null) {
                        ledCharacteristic = null; ledReadConfirmed = false;
                        publishLed(false, false, -1, "LED brightness unsupported by this firmware"); return;
                    }
                    if (write) {
                        if (!ledReadConfirmed || target != ledCharacteristic) {
                            ledReadConfirmed = false;
                            publishLed(true, false, -1, "Read LED brightness successfully before setting it"); return;
                        }
                        ledRequested = value;
                        // Legacy API is available throughout minSdk26..34, as for
                        // this client's CCC writes. Never use WRITE_NO_RESPONSE.
                        target.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        if (!target.setValue(new byte[]{(byte)value})) {
                            ledFailure(remote, "Cannot prepare LED write; read again", false); return;
                        }
                        arm(9); publishLed(true, true, ledState.brightness, "Setting LED brightness; awaiting readback");
                        if (current(remote, 9) && !remote.writeCharacteristic(target))
                            ledFailure(remote, "LED write rejected; read again", false);
                    } else {
                        ledCharacteristic = target; ledReadConfirmed = false; ledRequested = -1;
                        arm(8); publishLed(true, true, -1, "Reading LED brightness");
                        if (current(remote, 8) && !remote.readCharacteristic(target))
                            ledFailure(remote, "LED read rejected; try an explicit read again", false);
                    }
                } catch (RuntimeException e) {
                    // An exception may occur after native submission. Do not
                    // risk accepting its late callback as a later request.
                    ledFailure(remote, "LED operation failed; unavailable until next natural reconnect", true);
                }
            });
        }
    }
    private BluetoothGattCharacteristic findLed(BluetoothGatt remote) {
        BluetoothGattService service = remote.getService(SETTINGS);
        BluetoothGattCharacteristic target = service == null ? null : service.getCharacteristic(LED);
        int required = BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE;
        return target != null && LED.equals(target.getUuid()) && (target.getProperties() & required) == required ? target : null;
    }
    private void publishLed(boolean supported, boolean busy, int brightness, String message) {
        ledState = new LedState(supported, busy, brightness, message); listener.onLedState(ledState);
    }
    private void ledFailure(BluetoothGatt remote, String reason, boolean quarantine) {
        if (!current(remote)) return;
        cancelDeadline(); operation = 6; ledReadConfirmed = false; ledRequested = -1;
        if (quarantine) quarantineOptional();
        publishLed(!ledQuarantined && ledCharacteristic != null, false, -1, reason);
        // Optional control failures NEVER touch audio, gaps, retry or connection.
    }
    private void ledRead(BluetoothGatt remote, BluetoothGattCharacteristic target, byte[] value, int status) { postEvent(() -> {
        if (!current(remote) || ledQuarantined || target != ledCharacteristic ||
            (operation != 8 && operation != 10)) return;
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length != 1 || (value[0] & 255) > 100) {
            ledFailure(remote, "LED read failed or returned invalid brightness; read again", false); return;
        }
        int brightness = value[0] & 255; boolean readback = operation == 10;
        int requested = ledRequested;
        cancelDeadline(); operation = 6; ledReadConfirmed = true; ledRequested = -1;
        publishLed(true, false, brightness,
            readback && brightness != requested ? "LED readback mismatch: requested " + requested + "%, device reports " + brightness + "%"
            : "Device-reported LED brightness: " + brightness + "%" + (readback ? " (readback verified; reboot persistence unverified)" : ""));
    }); }
    private void cancelBatteryPoll() {
        if (batteryPoll != null) handler.removeCallbacks(batteryPoll);
        batteryPoll = null;
    }
    private void quarantineOptional() {
        // An optional native request may still be outstanding. Neither LED nor
        // BAS may submit again on this GATT, even if its late callback arrives.
        ledQuarantined = true; ledReadConfirmed = false; ledRequested = -1;
        batteryCharacteristic = null; cancelBatteryPoll(); listener.onBattery(-1);
    }
    private BluetoothGattCharacteristic findBattery(BluetoothGatt remote) {
        BluetoothGattService service = remote.getService(BATTERY_SERVICE);
        if (service == null || !BATTERY_SERVICE.equals(service.getUuid())) return null;
        BluetoothGattCharacteristic target = service.getCharacteristic(BATTERY);
        return target != null && BATTERY.equals(target.getUuid()) &&
            (target.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? target : null;
    }
    private void scheduleBattery(BluetoothGatt remote, long delay) {
        cancelBatteryPoll();
        if (!current(remote) || !audioReady || ledQuarantined) return;
        batteryPoll = new Runnable() { @Override public void run() {
            if (batteryPoll != this || !current(remote) || !audioReady || ledQuarantined) return;
            batteryPoll = null;
            // Skip a busy lane for this interval. Never cancel/delay a LED
            // deadline/readback or enqueue a second native ATT operation.
            scheduleBattery(remote, BATTERY_REFRESH_MS);
            if (operation != 6) return;
            try {
                BluetoothGattCharacteristic target = findBattery(remote);
                if (target == null) { batteryCharacteristic = null; listener.onBattery(-1); return; }
                if (!current(remote, 6) || ledQuarantined) return;
                batteryCharacteristic = target; arm(11);
                if (current(remote, 11) && !remote.readCharacteristic(target)) batteryFailure(remote, false);
            } catch (RuntimeException e) {
                // Includes permission loss: native submission is uncertain.
                batteryFailure(remote, true);
            }
        } };
        handler.postDelayed(batteryPoll, delay);
    }
    private void batteryFailure(BluetoothGatt remote, boolean quarantine) {
        if (!current(remote)) return;
        cancelDeadline(); operation = 6; batteryCharacteristic = null;
        if (quarantine) {
            quarantineOptional();
            publishLed(false, false, -1, "Optional controls unavailable until the next natural reconnect");
        } else listener.onBattery(-1);
        // Battery is telemetry, never evidence of PCM life or retry recovery.
    }
    private void batteryRead(BluetoothGatt remote, BluetoothGattCharacteristic target, byte[] value, int status) { postEvent(() -> {
        if (!current(remote, 11) || ledQuarantined || target != batteryCharacteristic) return;
        try {
            if (findBattery(remote) != target || status != BluetoothGatt.GATT_SUCCESS ||
                value == null || value.length != 1 || (value[0] & 255) > 100) {
                batteryFailure(remote, false); return;
            }
        } catch (RuntimeException e) { batteryFailure(remote, true); return; }
        cancelDeadline(); operation = 6; batteryCharacteristic = null;
        listener.onBattery(value[0] & 255);
    }); }
    private interface SelectionAction { void run(long request); }
    private void select(SelectionAction action) {
        synchronized (workerLock) {
            connectionGeneration.incrementAndGet();
            long request = selectionEpoch.incrementAndGet();
            if (handler == null) {
                worker = new HandlerThread("OmiBle"); worker.start();
                handler = new Handler(worker.getLooper());
            }
            handler.post(() -> { if (request == selectionEpoch.get()) action.run(request); });
        }
    }
    // Late platform callbacks never create a worker after stop. The epoch also
    // invalidates work already queued before a new selection/stop request.
    private void postEvent(Runnable action) {
        synchronized (workerLock) {
            long request = selectionEpoch.get();
            if (handler != null) handler.post(() -> { if (request == selectionEpoch.get()) action.run(); });
        }
    }
    public void scan() { select(request -> {
        stopInternal();
        if (request != selectionEpoch.get()) return; // Optional-state listener may stop/reselect.
        activeSelection = request; stopped = false;
        try {
            if (adapter == null || !adapter.isEnabled()) { listener.onStatus("Bluetooth unavailable or disabled"); return; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { listener.onStatus("BLE scanner unavailable"); return; }
            scanning = true;
            scanCallback = scanCallbackFor(request);
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            listener.onStatus("Scanning for Omi; select a device to connect");
            handler.postDelayed(scanEnd, 15000);
        } catch (RuntimeException e) { stopScan(); listener.onStatus("BLE scan failed: check Bluetooth permissions"); }
    }); }
    /** Existing capture mode: codec, audio CCC, then optional button CCC. */
    public void connect(String address) { connect(address, false); }
    /** Button-only mode: no codec read, audio CCC, decoder, or audio watchdog.
     * Missing button support is a visible retryable failure, never audio fallback. */
    public void connectControls(String address) { connect(address, true); }
    private void connect(String address, boolean controls) { select(request -> {
        stopInternal();
        if (request != selectionEpoch.get()) return;
        activeSelection = request; controlsOnly = controls;
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) { listener.onStatus("Invalid selected Bluetooth address"); return; }
        selected = address; stopped = false; retry = 0; open();
    }); }
    public void stop() { stop(null); }
    /** Completion runs after native disconnect/close and stopInternal(), outside
     * workerLock. Normally on the BLE worker after quitSafely is requested; on the
     * caller if already idle. A newer selection retains the same FIFO worker, but
     * cannot cancel this closure barrier. Safe to post a new connection from it.
     * This does NOT await actual thread exit; never join the BLE worker here. */
    public void stop(Runnable afterClosed) {
        synchronized (workerLock) {
            connectionGeneration.incrementAndGet();
            long request = selectionEpoch.incrementAndGet();
            if (handler != null) {
                Handler closingHandler = handler; HandlerThread closingWorker = worker;
                closingHandler.post(() -> {
                    if (afterClosed == null && request != selectionEpoch.get()) return;
                    try { stopInternal(); }
                    finally {
                        synchronized (workerLock) {
                            // A concurrent/reentrant start keeps this same FIFO queue.
                            if (request == selectionEpoch.get() && handler == closingHandler) {
                                closingHandler.removeCallbacksAndMessages(null);
                                closingWorker.quitSafely(); handler = null; worker = null;
                            }
                        }
                        if (afterClosed != null) afterClosed.run();
                    }
                });
                return;
            }
        }
        try { listener.onBattery(-1); }
        finally { if (afterClosed != null) afterClosed.run(); }
    }
    private void open() {
        if (stopped || selected == null || activeSelection != selectionEpoch.get()) return;
        listener.onGap(); frames.reset(); decoder = null;
        // Listener delivery may block while Stop/new selection invalidates this
        // request. Queued cleanup cannot run until open() returns.
        if (stopped || selected == null || activeSelection != selectionEpoch.get()) return;
        try {
            if (adapter == null) { fail("Bluetooth unavailable on this device", false); return; }
            if (!adapter.isEnabled()) { fail("Bluetooth disabled; waiting for Bluetooth", true); return; }
            listener.onStatus("Connecting to selected Omi · keep wearable nearby");
            if (stopped || selected == null || activeSelection != selectionEpoch.get()) return;
            openedConnections++; negotiatedMtu = -1; diagnosticEvent(Event.OPEN, 0);
            gattGeneration = connectionGeneration.get();
            gatt = adapter.getRemoteDevice(selected).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("Cannot create BLE connection", true); return; }
            arm(1);
        } catch (SecurityException e) { fail("BLE connection permission denied", false); }
        catch (RuntimeException e) { fail("BLE connection failed", true); }
    }
    private final Runnable scanEnd = this::finishScan;
    private void finishScan() { if (activeSelection != selectionEpoch.get()) return; stopScan(); listener.onStatus("Scan complete; select an Omi device"); }
    private ScanCallback scanCallback;
    private ScanCallback scanCallbackFor(long request) { return new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { postEvent(() -> {
            if (!scanning || request != activeSelection || request != selectionEpoch.get()) return;
            try { String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                listener.onDevice(result.getDevice().getAddress(), name == null ? "Omi" : name);
            } catch (RuntimeException e) { listener.onStatus("Device discovery permission error"); }
        }); }
        @Override public void onScanFailed(int code) { postEvent(() -> {
            if (!scanning || request != activeSelection || request != selectionEpoch.get()) return;
            stopScan(); listener.onStatus("BLE scan failed (" + code + ")");
        }); }
    }; }
    private void stopScan() {
        handler.removeCallbacks(scanEnd); scanning = false;
        if (scanner != null) { try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {} scanner = null; }
    }
    private void cancelDeadline() { if (deadline != null) handler.removeCallbacks(deadline); deadline = null; }
    private void arm(int op) {
        cancelDeadline(); operation = op; BluetoothGatt remote = gatt;
        deadline = new Runnable() { @Override public void run() {
            if (deadline != this || !current(remote, op)) return;
            // A missing callback is not proof that the native MTU request ended.
            // Close before retrying; never overlap discovery with uncertain work.
            if (op == 2) fail("Omi MTU negotiation timed out", true, Event.GATT_TIMEOUT);
            else if (op == 7) {
                if (controlsOnly) { buttonUnavailable(remote, "subscription timed out"); return; }
                // The optional ATT operation may still be pending natively.
                quarantineOptional(); buttonUnavailable(remote, "subscription timed out");
            }
            else if (op >= 8 && op <= 10) ledFailure(remote,
                "LED operation timed out; unavailable until next natural reconnect", true);
            else if (op == 11) batteryFailure(remote, true);
            else if (op == 13 || op == 15 || op == 16) hapticFailure(remote, "Vibration timed out; optional controls paused", true);
            else fail((op == 1 ? "Omi connection" : op == 3 ? "Omi service discovery" : op == 4 ? "Omi audio format check" : "Omi audio subscription") + " timed out", true, Event.GATT_TIMEOUT);
        } };
        handler.postDelayed(deadline, op == 13 || op == 15 || op == 16 ? 1500 : 15000);
    }
    private boolean current(BluetoothGatt remote) { return !stopped && activeSelection == selectionEpoch.get() && remote != null && remote == gatt; }
    private boolean current(BluetoothGatt remote, int op) { return current(remote) && operation == op; }
    private void closeGatt() {
        connectionGeneration.incrementAndGet();
        cancelGestures();
        clearHaptic();
        cancelDeadline(); cancelBatteryPoll(); BluetoothGatt old = gatt; gatt = null; operation = 0;
        cancelAudioWatch(); audioReady = buttonReady = audioReceived = false;
        discoveryRechecked = false;
        codecCharacteristic = audioCharacteristic = buttonCharacteristic = null; pendingDescriptor = null;
        ledCharacteristic = null; ledReadConfirmed = ledQuarantined = false; ledRequested = -1;
        batteryCharacteristic = null;
        if (old != null) { try { old.disconnect(); } catch (RuntimeException ignored) {} try { old.close(); } catch (RuntimeException ignored) {} }
        frames.reset(); decoder = null;
        publishLed(false, false, -1, "LED brightness unknown; read during an existing live connection");
        listener.onBattery(-1);
        listener.onHapticStatus("Connect your Omi before testing notification vibrations");
    }
    private void stopInternal() {
        lastCloseReason = Event.STOP_OR_REPLACE; diagnosticEvent(lastCloseReason, 0);
        stopped = true; selected = null; stopScan();
        cancelRetry();
        closeGatt(); listener.onGap();
    }
    private void cancelRetry() {
        if (retryTask != null) handler.removeCallbacks(retryTask);
        retryTask = null;
    }
    private void fail(String status, boolean reconnect) {
        fail(status, reconnect, Event.FAILURE);
    }
    private void fail(String status, boolean reconnect, Event cause) {
        lastCloseReason = cause; diagnosticEvent(cause, reconnect ? 1 : 0);
        cancelRetry(); closeGatt(); listener.onGap();
        if (!stopped && reconnect && selected != null) {
            // A native timeout already took ~10s in the observed incident. Do
            // not add another 30s during the initial recovery burst. Bound that
            // burst, then preserve battery during sustained unavailability.
            long delay = retry < 10 ? Math.min(5000L, 1000L << Math.min(retry, 3)) : 30000L;
            if (retry < 10) retry++;
            retryTask = new Runnable() { @Override public void run() {
                if (retryTask != this || stopped || selected == null) return;
                retryTask = null; open();
            } };
            handler.postDelayed(retryTask, delay);
            listener.onStatus(status + "; retry in " + (delay / 1000) + " seconds");
        } else {
            stopped = true;
            listener.onStatus(status + (controlsOnly ? "; controls disconnected, restart to retry" : "; capture disconnected, restart to retry"));
        }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt remote, int status, int state) {
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                // Revoke main-queued button actions at native ingress, even while
                // the worker is busy. An old GATT cannot revoke its replacement.
                synchronized (workerLock) {
                    long generation = gattGeneration;
                    if (remote != null && remote == gatt)
                        connectionGeneration.compareAndSet(generation, generation + 1);
                }
            }
            postEvent(() -> {
            if (!current(remote)) return;
            lastConnectionStatus = status; diagnosticEvent(Event.CONNECTION_CALLBACK, status);
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Omi connection lost (Bluetooth status " + status + ")", true, Event.NATIVE_LOSS); return;
            }
            if (state == BluetoothProfile.STATE_CONNECTED && operation == 1) {
                // Eight-byte stock button events fit the default ATT MTU.
                if (controlsOnly) { discover(remote); return; }
                listener.onStatus("Bluetooth linked · negotiating audio packet size");
                if (!current(remote, 1)) return;
                try { arm(2); if (!remote.requestMtu(247)) discover(remote); }
                catch (SecurityException e) { fail("BLE connection permission denied", false); }
                catch (RuntimeException e) { fail("Omi MTU negotiation failed", true); }
            }
        }); }
        @Override public void onMtuChanged(BluetoothGatt remote, int mtu, int status) { postEvent(() -> { if (current(remote, 2)) { negotiatedMtu = status == BluetoothGatt.GATT_SUCCESS ? mtu : -1; discover(remote); } }); }
        @Override public void onServicesDiscovered(BluetoothGatt remote, int status) { postEvent(() -> {
            if (!current(remote, 3)) return;
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Omi service discovery failed", true); return; }
                if (controlsOnly) {
                    cancelDeadline(); operation = 6; subscribeButton(remote); return;
                }
                BluetoothGattService service = remote.getService(SERVICE);
                if (service == null || service.getCharacteristic(CODEC) == null || service.getCharacteristic(AUDIO) == null) {
                    recheckDiscovery(remote, service == null ? "Omi audio service unavailable"
                        : "Omi audio service incomplete"); return;
                }
                listener.onStatus("Bluetooth linked · checking Omi audio format");
                if (!current(remote, 3)) return;
                codecCharacteristic = service.getCharacteristic(CODEC);
                arm(4); if (!remote.readCharacteristic(codecCharacteristic)) fail("Cannot read Omi codec", true);
            } catch (SecurityException e) { fail("BLE connection permission denied", false); }
            catch (RuntimeException e) { fail("Omi service discovery failed", true); }
        }); }
        @Override public void onCharacteristicRead(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic == null) return;
            byte[] value = characteristic.getValue(); characteristicRead(remote, characteristic, value == null ? null : value.clone(), status);
        }
        @Override public void onCharacteristicRead(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (characteristic != null) characteristicRead(remote, characteristic, value == null ? null : value.clone(), status);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, int status) { postEvent(() -> {
            if (characteristic != null && HAPTIC.equals(characteristic.getUuid())) {
                hapticWritten(remote, characteristic, status); return;
            }
            if (!current(remote, 9) || ledQuarantined || characteristic != ledCharacteristic) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { ledFailure(remote, "LED write failed; read again", false); return; }
            try {
                if (findLed(remote) != ledCharacteristic) { ledFailure(remote, "LED characteristic changed; read again", false); return; }
                arm(10);
                if (!remote.readCharacteristic(ledCharacteristic)) ledFailure(remote, "LED readback rejected; brightness unconfirmed", false);
            } catch (RuntimeException e) { ledFailure(remote, "LED readback failed; unavailable until next natural reconnect", true); }
        }); }
        @Override public void onDescriptorWrite(BluetoothGatt remote, BluetoothGattDescriptor descriptor, int status) { postEvent(() -> {
            // Both descriptors have UUID 0x2902: match the actual pending target,
            // not just its UUID, and never accept an earlier connection's ack.
            if (!current(remote) || descriptor == null || descriptor != pendingDescriptor || !CCC.equals(descriptor.getUuid())) return;
            if (!controlsOnly && operation == 5) {
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Omi notification subscription failed", true); return; }
                cancelDeadline(); pendingDescriptor = null; operation = 6;
                audioReady = true; armAudioWatch(); subscribeButton(remote);
            } else if (operation == 7) {
                if (status != BluetoothGatt.GATT_SUCCESS) { buttonUnavailable(remote, "subscription rejected"); return; }
                cancelDeadline(); pendingDescriptor = null; operation = 6; buttonReady = true;
                listener.onStatus(streamStatus() + "; button controls ready");
                scheduleBattery(remote, 1000L);
            }
        }); }
        @Override public void onCharacteristicChanged(BluetoothGatt remote, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) return;
            byte[] value = characteristic.getValue(); if (value != null) packet(remote, characteristic, value.clone());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, byte[] value) { if (value != null) packet(remote, characteristic, value.clone()); }
    };
    private void characteristicRead(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (LED.equals(characteristic.getUuid())) ledRead(remote, characteristic, value, status);
        else if (BATTERY.equals(characteristic.getUuid())) batteryRead(remote, characteristic, value, status);
        else if (FEATURES.equals(characteristic.getUuid())) hapticFeaturesRead(remote, characteristic, value, status);
        else codecRead(remote, characteristic, value, status);
    }
    private void recheckDiscovery(BluetoothGatt remote, String reason) {
        if (!current(remote)) return;
        // A successful callback can expose an incomplete service table. It is
        // not proof that a previously working wearable is incompatible. Allow
        // one quiet, serial recheck, then use the normal fresh-GATT backoff.
        // No hidden cache-refresh API, radio reset or setting/firmware writes.
        if (discoveryRechecked) { fail(reason + "; check selected wearable", true); return; }
        discoveryRechecked = true;
        cancelDeadline(); operation = 12;
        deadline = new Runnable() { @Override public void run() {
            if (deadline != this || !current(remote, 12)) return;
            deadline = null; discover(remote);
        } };
        handler.postDelayed(deadline, 1500L);
        listener.onStatus(reason + "; rechecking discovery");
    }
    private void discover(BluetoothGatt remote) {
        if (!current(remote)) return;
        listener.onStatus(controlsOnly ? "Bluetooth linked · discovering Omi button service"
            : "Bluetooth linked · discovering Omi audio service");
        if (!current(remote)) return; // Stop/new selection during listener delivery.
        try { arm(3); if (!remote.discoverServices()) fail("Cannot discover Omi services", true); }
        catch (SecurityException e) { fail("BLE connection permission denied", false); }
        catch (RuntimeException e) { fail("BLE discovery failed", true); }
    }
    private void codecRead(BluetoothGatt remote, BluetoothGattCharacteristic target, byte[] value, int status) { postEvent(() -> {
        if (controlsOnly || !current(remote, 4) || target != codecCharacteristic || !CODEC.equals(target.getUuid())) return;
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length != 1) { fail("Omi codec read failed", true); return; }
        int codec = value[0] & 255;
        // Pinned DevKit 20 (10 ms) and Omi 21 (20 ms) share 16 kHz mono Opus
        // and the same notification framing; packet headers carry frame length.
        if (codec != 20 && codec != 21) { fail("Unsupported Omi codec " + codec + " (requires Opus 20 or 21)", false); return; }
        try {
            decoder = new OpusDecoder(16000, 1); frames.reset();
            BluetoothGattCharacteristic audio = remote.getService(SERVICE).getCharacteristic(AUDIO);
            audioCharacteristic = audio;
            BluetoothGattDescriptor ccc = audio.getDescriptor(CCC);
            if (ccc == null || (audio.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) { recheckDiscovery(remote, "Omi audio notifications unavailable"); return; }
            if (!remote.setCharacteristicNotification(audio, true)) { fail("Cannot enable Omi audio notifications", true); return; }
            listener.onStatus("Bluetooth linked · enabling Omi audio stream");
            if (!current(remote, 4)) return;
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); pendingDescriptor = ccc; arm(5);
            if (!remote.writeDescriptor(ccc)) fail("Cannot enable Omi audio notifications", true);
        } catch (SecurityException e) { fail("BLE connection permission denied", false); }
        catch (RuntimeException e) { fail("Omi subscription failed", true); }
        catch (Exception e) { fail("Omi decoder failed", false); }
    }); }
    private void subscribeButton(BluetoothGatt remote) {
        if (!current(remote, 6)) return;
        // Capture completes audio CCC first; controls-only starts here directly.
        // Do NOT read button state: stock firmware retains the last press value.
        buttonReady = false;
        try {
            BluetoothGattService service = remote.getService(BUTTON_SERVICE);
            buttonCharacteristic = service == null ? null : service.getCharacteristic(BUTTON);
            if (buttonCharacteristic == null || (buttonCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                buttonUnavailable(remote, "not supported by this firmware"); return;
            }
            BluetoothGattDescriptor ccc = buttonCharacteristic.getDescriptor(CCC);
            if (ccc == null || !remote.setCharacteristicNotification(buttonCharacteristic, true)) {
                buttonUnavailable(remote, "notifications unavailable"); return;
            }
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); pendingDescriptor = ccc; arm(7);
            listener.onStatus(streamStatus() + "; enabling button controls");
            if (current(remote, 7) && !remote.writeDescriptor(ccc)) buttonUnavailable(remote, "cannot enable notifications");
        } catch (SecurityException e) { fail("BLE connection permission denied", false); }
        catch (RuntimeException e) { quarantineOptional(); buttonUnavailable(remote, "subscription failed"); }
    }
    private void buttonUnavailable(BluetoothGatt remote, String reason) {
        if (!current(remote)) return;
        if (controlsOnly) { fail("Omi button controls unavailable (" + reason + ")", true); return; }
        cancelDeadline(); pendingDescriptor = null; operation = 6; buttonReady = false;
        if (buttonCharacteristic != null) {
            try { remote.setCharacteristicNotification(buttonCharacteristic, false); } catch (RuntimeException ignored) {}
        }
        buttonCharacteristic = null;
        listener.onStatus(streamStatus() + "; button controls unavailable (" + reason + ")");
        scheduleBattery(remote, 1000L);
        // No reconnect, audio gap, or audio watchdog reset for optional failure.
    }
    private String streamStatus() {
        if (controlsOnly) return "Omi connected: controls only (not recording)";
        return audioReceived ? "Omi connected: receiving audio · local transcription active"
            : "Omi connected: waiting for first audio";
    }
    private void cancelAudioWatch() { if (audioDeadline != null) handler.removeCallbacks(audioDeadline); audioDeadline = null; }
    private void armAudioWatch() {
        cancelAudioWatch(); BluetoothGatt remote = gatt;
        if (controlsOnly) return;
        // CCC acknowledgement is not PCM readiness. Give firmware startup its
        // own bounded grace, without weakening loss detection once PCM flowed.
        long timeout = audioReceived ? 15000L : 30000L;
        audioDeadline = new Runnable() { @Override public void run() {
            if (audioDeadline == this && current(remote) && audioReady)
                fail("No Omi audio for " + timeout / 1000 + " seconds", true, Event.AUDIO_TIMEOUT);
        } };
        handler.postDelayed(audioDeadline, timeout);
    }
    private void packet(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, byte[] value) {
        long generation = gattGeneration;
        long arrivedAt = SystemClock.elapsedRealtime();
        long configuration = characteristic != null && BUTTON.equals(characteristic.getUuid())
            ? listener.buttonConfigurationGeneration() : -1;
        // Before worker dispatch: distinguish ingress from worker/decode stalls.
        // No diagnostic IO or allocation per packet; never keep packet contents.
        if (remote != null && remote == gatt && characteristic != null && AUDIO.equals(characteristic.getUuid())) {
            ingressPackets.incrementAndGet(); lastIngressAt.set(SystemClock.elapsedRealtime());
        }
        postEvent(() -> {
        if (!current(remote)) return;
        if (buttonReady && characteristic == buttonCharacteristic) {
            int event = ButtonEvent.decode(value);
            if (event != ButtonEvent.NONE && generation == connectionGeneration.get()) {
                if (controlsOnly) retry = 0; // A fresh button, not CCC ack, proves controls life.
                dispatchTap(remote, event, generation, configuration, arrivedAt);
            }
            return; // Buttons never refresh the audio watchdog.
        }
        if (controlsOnly || !audioReady || characteristic != audioCharacteristic || decoder == null) return;
        byte[] opus = frames.accept(value);
        // Startup is bounded from subscription, not from the first fragment.
        // Only decoded PCM proves life: partial/corrupt/duplicate traffic cannot
        // extend the startup allowance or reset reconnect backoff.
        if (frames.gap) { listener.onGap(); listener.onStatus("BLE audio gap: incomplete task discarded"); try { decoder = new OpusDecoder(16000, 1); } catch (Exception e) { fail("Opus decoder failed", false); return; } }
        if (opus == null) return;
        try { short[] pcm = new short[1920]; int count = decoder.decode(opus, 0, opus.length, pcm, 0, pcm.length, false);
            if (count > 0 && current(remote)) {
                decodedFrames++; decodedSamples += count; lastDecodedAt = SystemClock.elapsedRealtime();
                boolean firstAudio = !audioReceived;
                audioReceived = true; retry = 0; armAudioWatch(); listener.onPcm(Arrays.copyOf(pcm, count));
                if (firstAudio) {
                    listener.onStatus(streamStatus() + (buttonReady ? "; button controls ready"
                        : operation == 7 ? "; enabling button controls" : "; button controls unavailable"));
                }
            }
        } catch (Exception e) { frames.reset(); listener.onGap(); listener.onStatus("Invalid Opus frame: incomplete task discarded"); try { decoder = new OpusDecoder(16000, 1); } catch (Exception ignored) { fail("Opus decoder failed", false); } }
    }); }
    private void cancelGestures() {
        if (gestureDeadline != null) handler.removeCallbacks(gestureDeadline);
        gestureDeadline = null; gestures.reset();
    }
    private void dispatchTap(BluetoothGatt remote, int event, long generation, long configuration, long arrivedAt) {
        if (configuration != listener.buttonConfigurationGeneration()) { cancelGestures(); return; }
        if (configuration != gestureConfiguration) { cancelGestures(); gestureConfiguration = configuration; }
        if (gestureDeadline != null) handler.removeCallbacks(gestureDeadline);
        gestureDeadline = null;
        int maxTaps = ButtonGestures.normalizeMaxTaps(listener.maxButtonTaps());
        for (int resolved : gestures.accept(event, arrivedAt, maxTaps)) {
            if (!current(remote) || generation != connectionGeneration.get()
                    || configuration != listener.buttonConfigurationGeneration()) { cancelGestures(); return; }
            listener.onButton(resolved, generation, configuration);
        }
        if (gestures.deadline() < 0) return;
        gestureDeadline = new Runnable() { public void run() {
            if (gestureDeadline != this) return;
            gestureDeadline = null;
            if (!current(remote) || generation != connectionGeneration.get()
                    || maxTaps != ButtonGestures.normalizeMaxTaps(listener.maxButtonTaps())
                    || configuration != listener.buttonConfigurationGeneration()) {
                gestures.reset(); return;
            }
            int resolved = gestures.expire(SystemClock.elapsedRealtime());
            if (resolved != ButtonEvent.NONE) listener.onButton(resolved, generation, configuration);
        }};
        handler.postDelayed(gestureDeadline, Math.max(0, gestures.deadline() - SystemClock.elapsedRealtime()));
    }
    /** Package-visible pure packet parser for JVM tests. Last frame is discarded on stop. */
    static final class FrameAssembler {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private int last = -1, fragment = -1; boolean gap, accepted;
        void reset() { last = fragment = -1; pending.reset(); gap = accepted = false; }
        byte[] accept(byte[] packet) {
            gap = accepted = false;
            if (packet == null || packet.length <= 3) { reset(); gap = true; return null; }
            int seq = (packet[0] & 255) | ((packet[1] & 255) << 8), part = packet[2] & 255;
            if (seq == last) return null; // duplicated notification is not new audio
            if (last != -1 && (seq != ((last + 1) & 65535) || (part != 0 && part != fragment + 1))) { reset(); gap = true; }
            if (last == -1 && part != 0) return null;
            byte[] complete = part == 0 && pending.size() > 0 ? pending.toByteArray() : null;
            if (part == 0) pending.reset();
            if (pending.size() + packet.length - 3 > 1275) { reset(); gap = true; return null; }
            pending.write(packet, 3, packet.length - 3); last = seq; fragment = part; accepted = true;
            return complete;
        }
    }
}
