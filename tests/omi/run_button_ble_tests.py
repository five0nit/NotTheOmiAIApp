#!/usr/bin/env python3
"""HOST-ONLY: compile unchanged OmiBle/ButtonEvent + real vendored Concentus.

Android/Bluetooth are deterministic fakes, with one outstanding GATT operation
and a virtual Handler clock. No BLE hardware, Android runtime, network, Gradle,
new dependencies or firmware writes. Tests exercise production callback code,
not a reimplemented subscription state machine. Temporary classes are removed.
"""
from pathlib import Path
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    'android/annotation/SuppressLint.java': '''
        package android.annotation; public @interface SuppressLint { String[] value(); }
    ''',
    'android/os/Looper.java': '''
        package android.os;
        public final class Looper {
            private static final Looper MAIN = new Looper();
            public static Looper getMainLooper() { return MAIN; }
            public static Looper myLooper() { return MAIN; }
        }
    ''',
    # Virtual worker: the test driver pumps its FIFO using Handler.drain/advance.
    'android/os/HandlerThread.java': '''
        package android.os;
        public final class HandlerThread {
            public HandlerThread(String name) {} public void start() {}
            public Looper getLooper() { return Looper.getMainLooper(); }
            public boolean quitSafely() { return true; }
        }
    ''',
    'android/os/Handler.java': '''
        package android.os;
        import java.util.*;
        public final class Handler {
            private static long now, sequence;
            private record Entry(long when, long order, Handler owner, Runnable task) {}
            private static final PriorityQueue<Entry> queue = new PriorityQueue<>(
                Comparator.comparingLong(Entry::when).thenComparingLong(Entry::order));
            public Handler(Looper l) {}
            public Looper getLooper() { return Looper.getMainLooper(); }
            public boolean post(Runnable r) { return postDelayed(r, 0); }
            public boolean postDelayed(Runnable r, long delay) { queue.add(new Entry(now + delay, sequence++, this, r)); return true; }
            public void removeCallbacks(Runnable r) { queue.removeIf(e -> e.owner == this && e.task == r); }
            public void removeCallbacksAndMessages(Object token) {
                if (token != null) throw new UnsupportedOperationException("fixture supports untagged callbacks only");
                queue.removeIf(e -> e.owner == this);
            }
            public static void reset() { queue.clear(); now = sequence = 0; }
            public static void drain() { advance(0); }
            public static void advance(long ms) {
                long end = now + ms; int bound = 10000;
                while (!queue.isEmpty() && queue.peek().when <= end) {
                    if (--bound == 0) throw new AssertionError("unbounded handler loop");
                    Entry e = queue.remove(); now = e.when; e.task.run();
                }
                now = end;
            }
        }
    ''',
    'android/os/ParcelUuid.java': '''
        package android.os; import java.util.UUID;
        public final class ParcelUuid { public ParcelUuid(UUID u) {} }
    ''',
    'android/content/Context.java': '''
        package android.content; import android.bluetooth.*;
        public class Context {
            public static final String BLUETOOTH_SERVICE = "bluetooth";
            public final BluetoothAdapter adapter = new BluetoothAdapter();
            public Context getApplicationContext() { return this; }
            public Object getSystemService(String name) { return new BluetoothManager(adapter); }
        }
    ''',
    'android/bluetooth/BluetoothManager.java': '''
        package android.bluetooth;
        public class BluetoothManager {
            private final BluetoothAdapter adapter;
            public BluetoothManager(BluetoothAdapter a) { adapter = a; }
            public BluetoothAdapter getAdapter() { return adapter; }
        }
    ''',
    'android/bluetooth/BluetoothAdapter.java': '''
        package android.bluetooth; import android.bluetooth.le.*;
        public class BluetoothAdapter {
            public BluetoothGatt next; public int connections;
            public boolean enabled = true; public RuntimeException failure;
            public boolean isEnabled() { if (failure != null) throw failure; return enabled; }
            public static boolean checkBluetoothAddress(String a) { return a.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"); }
            public BluetoothDevice getRemoteDevice(String a) { return new BluetoothDevice(this); }
            public BluetoothLeScanner scanner = new BluetoothLeScanner();
            public BluetoothLeScanner getBluetoothLeScanner() { return scanner; }
        }
    ''',
    'android/bluetooth/BluetoothDevice.java': '''
        package android.bluetooth; import android.content.Context;
        public class BluetoothDevice {
            public static final int TRANSPORT_LE = 2;
            private final BluetoothAdapter adapter;
            public BluetoothDevice(BluetoothAdapter a) { adapter = a; }
            public String getAddress() { return "AA:BB:CC:DD:EE:FF"; }
            public BluetoothGatt connectGatt(Context c, boolean auto, BluetoothGattCallback callback, int transport) {
                adapter.connections++; adapter.next.callback = callback; return adapter.next;
            }
        }
    ''',
    'android/bluetooth/BluetoothProfile.java': '''
        package android.bluetooth; public interface BluetoothProfile { int STATE_DISCONNECTED = 0, STATE_CONNECTED = 2; }
    ''',
    'android/bluetooth/BluetoothGattService.java': '''
        package android.bluetooth; import java.util.*;
        public class BluetoothGattService {
            public final UUID uuid; public final Map<UUID, BluetoothGattCharacteristic> chars = new HashMap<>();
            public BluetoothGattService(UUID u) { uuid = u; }
            public UUID getUuid() { return uuid; }
            public BluetoothGattCharacteristic getCharacteristic(UUID u) { return chars.get(u); }
            public void add(BluetoothGattCharacteristic c) { chars.put(c.getUuid(), c); }
        }
    ''',
    'android/bluetooth/BluetoothGattCharacteristic.java': '''
        package android.bluetooth; import java.util.*;
        public class BluetoothGattCharacteristic {
            public static final int PROPERTY_NOTIFY = 16, PROPERTY_READ = 2, PROPERTY_WRITE = 8, WRITE_TYPE_DEFAULT = 2;
            public int writeType; public void setWriteType(int type) { writeType = type; }
            public boolean rejectValue;
            private final UUID uuid; private byte[] value;
            public int properties = PROPERTY_NOTIFY;
            public final Map<UUID, BluetoothGattDescriptor> descriptors = new HashMap<>();
            public BluetoothGattCharacteristic(UUID u) { uuid = u; }
            public UUID getUuid() { return uuid; }
            public int getProperties() { return properties; }
            public byte[] getValue() { return value; }
            public boolean setValue(byte[] v) { if (rejectValue) return false; value = v; return true; }
            public BluetoothGattDescriptor getDescriptor(UUID u) { return descriptors.get(u); }
            public void add(BluetoothGattDescriptor d) { d.characteristic = this; descriptors.put(d.getUuid(), d); }
        }
    ''',
    'android/bluetooth/BluetoothGattDescriptor.java': '''
        package android.bluetooth; import java.util.*;
        public class BluetoothGattDescriptor {
            public static final byte[] ENABLE_NOTIFICATION_VALUE = {1, 0};
            private final UUID uuid; private byte[] value;
            public BluetoothGattCharacteristic characteristic;
            public BluetoothGattDescriptor(UUID u) { uuid = u; }
            public UUID getUuid() { return uuid; }
            public BluetoothGattCharacteristic getCharacteristic() { return characteristic; }
            public boolean setValue(byte[] v) { value = v.clone(); return true; }
            public byte[] getValue() { return value; }
        }
    ''',
    'android/bluetooth/BluetoothGattCallback.java': '''
        package android.bluetooth;
        public abstract class BluetoothGattCallback {
            public void onConnectionStateChange(BluetoothGatt g, int s, int state) {}
            public void onMtuChanged(BluetoothGatt g, int mtu, int s) {}
            public void onServicesDiscovered(BluetoothGatt g, int s) {}
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int s) {}
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int s) {}
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int s) {}
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) {}
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {}
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {}
        }
    ''',
    'android/bluetooth/BluetoothGatt.java': '''
        package android.bluetooth; import java.util.*;
        public class BluetoothGatt {
            public static final int GATT_SUCCESS = 0;
            public BluetoothGattCallback callback;
            public final Map<UUID, BluetoothGattService> services = new HashMap<>();
            public final List<String> calls = new ArrayList<>();
            public final Set<UUID> rejectNotify = new HashSet<>(), throwNotify = new HashSet<>(), rejectWrite = new HashSet<>(), throwWrite = new HashSet<>();
            public final List<BluetoothGattCharacteristic> reads = new ArrayList<>();
            public final List<BluetoothGattDescriptor> writes = new ArrayList<>();
            public final List<byte[]> commands = new ArrayList<>();
            public final List<BluetoothGattCharacteristic> commandTargets = new ArrayList<>();
            public boolean rejectCommand, rejectRead;
            public RuntimeException commandFailure, readFailure;
            public boolean writeCharacteristic(BluetoothGattCharacteristic c) {
                commands.add(c.getValue().clone()); commandTargets.add(c);
                if (commandFailure != null) throw commandFailure;
                if (rejectCommand) return false;
                return begin("command:" + c.getUuid(), c);
            }
            public Object pending; public boolean disconnected, closed;
            private boolean begin(String label, Object target) {
                if (pending != null) throw new AssertionError("overlapping GATT operations: " + calls + " -> " + label);
                calls.add(label); pending = target; return true;
            }
            public boolean rejectMtu; public RuntimeException mtuFailure;
            public boolean requestMtu(int mtu) {
                if (mtuFailure != null) throw mtuFailure;
                if (rejectMtu) return false;
                return begin("mtu", "mtu");
            }
            public boolean discoverServices() { return begin("discover", "discover"); }
            public BluetoothGattService getService(UUID uuid) { return services.get(uuid); }
            public boolean readCharacteristic(BluetoothGattCharacteristic c) {
                reads.add(c);
                if (readFailure != null) throw readFailure;
                if (rejectRead) return false;
                return begin("read:" + c.getUuid(), c);
            }
            public boolean setCharacteristicNotification(BluetoothGattCharacteristic c, boolean enabled) {
                calls.add("local-notify:" + c.getUuid() + ":" + enabled);
                if (throwNotify.contains(c.getUuid())) throw new IllegalStateException("fixture notification failure");
                return !rejectNotify.contains(c.getUuid());
            }
            public boolean writeDescriptor(BluetoothGattDescriptor d) {
                UUID uuid = d.getCharacteristic().getUuid(); writes.add(d);
                if (throwWrite.contains(uuid)) throw new IllegalStateException("fixture descriptor failure");
                if (rejectWrite.contains(uuid)) return false;
                return begin("write:" + uuid, d);
            }
            public void disconnect() { disconnected = true; }
            public void close() { closed = true; pending = null; }
        }
    ''',
    'android/bluetooth/le/BluetoothLeScanner.java': '''
        package android.bluetooth.le; import java.util.*;
        public class BluetoothLeScanner {
            public ScanCallback callback; public int starts, stops;
            public RuntimeException startFailure, stopFailure;
            public void startScan(List<ScanFilter> f, ScanSettings s, ScanCallback c) {
                starts++; callback = c; if (startFailure != null) throw startFailure;
            }
            public void stopScan(ScanCallback c) { stops++; if (stopFailure != null) throw stopFailure; }
        }
    ''',
    'android/bluetooth/le/ScanCallback.java': '''
        package android.bluetooth.le; public class ScanCallback { public void onScanResult(int t, ScanResult r) {} public void onScanFailed(int c) {} }
    ''',
    'android/bluetooth/le/ScanResult.java': '''
        package android.bluetooth.le; import android.bluetooth.*;
        public class ScanResult {
            private final BluetoothDevice device;
            public ScanResult(BluetoothDevice d) { device = d; }
            public ScanRecord getScanRecord() { return null; }
            public BluetoothDevice getDevice() { return device; }
        }
    ''',
    'android/bluetooth/le/ScanRecord.java': '''
        package android.bluetooth.le; public class ScanRecord { public String getDeviceName() { return null; } }
    ''',
    'android/bluetooth/le/ScanFilter.java': '''
        package android.bluetooth.le; import android.os.ParcelUuid;
        public class ScanFilter { public static class Builder { public Builder setServiceUuid(ParcelUuid u) { return this; } public ScanFilter build() { return new ScanFilter(); } } }
    ''',
    'android/bluetooth/le/ScanSettings.java': '''
        package android.bluetooth.le;
        public class ScanSettings { public static final int SCAN_MODE_LOW_LATENCY = 2; public static class Builder { public Builder setScanMode(int m) { return this; } public ScanSettings build() { return new ScanSettings(); } } }
    ''',
}


def main():
    src = ROOT / 'app/src/main/java'
    with tempfile.TemporaryDirectory(prefix='omi-button-ble-tests-') as tmp:
        work = Path(tmp)
        stubs = []
        for name, text in SOURCES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(text))
            stubs.append(path)
        production = [src / 'app/nottheomi/ai/OmiBle.java', src / 'app/nottheomi/ai/ButtonEvent.java']
        production += sorted((src / 'org/concentus').glob('*.java'))
        subprocess.run(['javac', '-d', str(work), *map(str, stubs), *map(str, production),
                        str(ROOT / 'tests/omi/ButtonBleTest.java')], check=True, timeout=120)
        subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.ButtonBleTest'], check=True, timeout=60)


if __name__ == '__main__':
    main()
