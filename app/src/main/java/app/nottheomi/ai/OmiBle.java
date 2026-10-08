package app.nottheomi.ai;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import org.concentus.OpusDecoder;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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
        default void onLedState(LedState state) {}
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
    private long activeSelection;
    // Operations 1..5 are connect/MTU/discover/codec/audio CCC; 6 is idle,
    // 7 is optional button CCC; 8/9/10 are LED read/write/readback; 11 is BAS read;
    // 12 is the bounded settling delay before one incomplete-discovery recheck.
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
    private int ledRequested = -1;
    private volatile LedState ledState = new LedState(false, false, -1,
        "LED brightness unknown; read during an existing live connection");
    private OpusDecoder decoder;
    private final FrameAssembler frames = new FrameAssembler();
    public OmiBle(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        listener.onLedState(ledState);
        listener.onBattery(-1);
    }
    public void readLedBrightness() { requestLed(false, -1); }
    public void setLedBrightness(int value) { requestLed(true, value); }
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
    public void connect(String address) { select(request -> {
        stopInternal();
        if (request != selectionEpoch.get()) return;
        activeSelection = request;
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) { listener.onStatus("Invalid selected Bluetooth address"); return; }
        selected = address; stopped = false; retry = 0; open();
    }); }
    public void stop() {
        synchronized (workerLock) {
            long request = selectionEpoch.incrementAndGet();
            if (handler == null) { listener.onBattery(-1); return; }
            Handler closingHandler = handler; HandlerThread closingWorker = worker;
            closingHandler.post(() -> {
                if (request != selectionEpoch.get()) return;
                try { stopInternal(); }
                finally {
                    synchronized (workerLock) {
                        // A concurrent/reentrant start keeps this same FIFO queue.
                        if (request == selectionEpoch.get() && handler == closingHandler) {
                            closingHandler.removeCallbacksAndMessages(null);
                            closingWorker.quitSafely(); handler = null; worker = null;
                        }
                    }
                }
            });
        }
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
            if (op == 2) fail("Omi MTU negotiation timed out", true);
            else if (op == 7) {
                // The optional ATT operation may still be pending natively.
                quarantineOptional(); buttonUnavailable(remote, "subscription timed out");
            }
            else if (op >= 8 && op <= 10) ledFailure(remote,
                "LED operation timed out; unavailable until next natural reconnect", true);
            else if (op == 11) batteryFailure(remote, true);
            else fail((op == 1 ? "Omi connection" : op == 3 ? "Omi service discovery" : op == 4 ? "Omi audio format check" : "Omi audio subscription") + " timed out", true);
        } };
        handler.postDelayed(deadline, 15000);
    }
    private boolean current(BluetoothGatt remote) { return !stopped && activeSelection == selectionEpoch.get() && remote != null && remote == gatt; }
    private boolean current(BluetoothGatt remote, int op) { return current(remote) && operation == op; }
    private void closeGatt() {
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
    }
    private void stopInternal() {
        stopped = true; selected = null; stopScan();
        cancelRetry();
        closeGatt(); listener.onGap();
    }
    private void cancelRetry() {
        if (retryTask != null) handler.removeCallbacks(retryTask);
        retryTask = null;
    }
    private void fail(String status, boolean reconnect) {
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
            listener.onStatus(status + "; capture disconnected, restart to retry");
        }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt remote, int status, int state) { postEvent(() -> {
            if (!current(remote)) return;
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Omi connection lost (Bluetooth status " + status + ")", true); return;
            }
            if (state == BluetoothProfile.STATE_CONNECTED && operation == 1) {
                listener.onStatus("Bluetooth linked · negotiating audio packet size");
                if (!current(remote, 1)) return;
                try { arm(2); if (!remote.requestMtu(247)) discover(remote); }
                catch (SecurityException e) { fail("BLE connection permission denied", false); }
                catch (RuntimeException e) { fail("Omi MTU negotiation failed", true); }
            }
        }); }
        @Override public void onMtuChanged(BluetoothGatt remote, int mtu, int status) { postEvent(() -> { if (current(remote, 2)) discover(remote); }); }
        @Override public void onServicesDiscovered(BluetoothGatt remote, int status) { postEvent(() -> {
            if (!current(remote, 3)) return;
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Omi service discovery failed", true); return; }
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
            if (operation == 5) {
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
        listener.onStatus("Bluetooth linked · discovering Omi audio service");
        if (!current(remote)) return; // Stop/new selection during listener delivery.
        try { arm(3); if (!remote.discoverServices()) fail("Cannot discover Omi services", true); }
        catch (SecurityException e) { fail("BLE connection permission denied", false); }
        catch (RuntimeException e) { fail("BLE discovery failed", true); }
    }
    private void codecRead(BluetoothGatt remote, BluetoothGattCharacteristic target, byte[] value, int status) { postEvent(() -> {
        if (!current(remote, 4) || target != codecCharacteristic || !CODEC.equals(target.getUuid())) return;
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
        // Audio CCC has completed before this optional GATT operation begins.
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
        return audioReceived ? "Omi connected: receiving audio · local transcription active"
            : "Omi connected: waiting for first audio";
    }
    private void cancelAudioWatch() { if (audioDeadline != null) handler.removeCallbacks(audioDeadline); audioDeadline = null; }
    private void armAudioWatch() {
        cancelAudioWatch(); BluetoothGatt remote = gatt;
        // CCC acknowledgement is not PCM readiness. Give firmware startup its
        // own bounded grace, without weakening loss detection once PCM flowed.
        long timeout = audioReceived ? 15000L : 30000L;
        audioDeadline = new Runnable() { @Override public void run() {
            if (audioDeadline == this && current(remote) && audioReady)
                fail("No Omi audio for " + timeout / 1000 + " seconds", true);
        } };
        handler.postDelayed(audioDeadline, timeout);
    }
    private void packet(BluetoothGatt remote, BluetoothGattCharacteristic characteristic, byte[] value) { postEvent(() -> {
        if (!current(remote)) return;
        if (buttonReady && characteristic == buttonCharacteristic) {
            int event = ButtonEvent.decode(value);
            if (event != ButtonEvent.NONE) listener.onButton(event);
            return; // Buttons never refresh the audio watchdog.
        }
        if (!audioReady || characteristic != audioCharacteristic || decoder == null) return;
        byte[] opus = frames.accept(value);
        // Startup is bounded from subscription, not from the first fragment.
        // Only decoded PCM proves life: partial/corrupt/duplicate traffic cannot
        // extend the startup allowance or reset reconnect backoff.
        if (frames.gap) { listener.onGap(); listener.onStatus("BLE audio gap: incomplete task discarded"); try { decoder = new OpusDecoder(16000, 1); } catch (Exception e) { fail("Opus decoder failed", false); return; } }
        if (opus == null) return;
        try { short[] pcm = new short[1920]; int count = decoder.decode(opus, 0, opus.length, pcm, 0, pcm.length, false);
            if (count > 0 && current(remote)) {
                boolean firstAudio = !audioReceived;
                audioReceived = true; retry = 0; armAudioWatch(); listener.onPcm(Arrays.copyOf(pcm, count));
                if (firstAudio) {
                    listener.onStatus(streamStatus() + (buttonReady ? "; button controls ready"
                        : operation == 7 ? "; enabling button controls" : "; button controls unavailable"));
                }
            }
        } catch (Exception e) { frames.reset(); listener.onGap(); listener.onStatus("Invalid Opus frame: incomplete task discarded"); try { decoder = new OpusDecoder(16000, 1); } catch (Exception ignored) { fail("Opus decoder failed", false); } }
    }); }
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
