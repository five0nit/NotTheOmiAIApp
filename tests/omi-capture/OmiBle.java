package app.nottheomi.ai;

import java.util.concurrent.*;

/** FIFO fake only. Production OmiBle is NOT replaced in the application. */
public final class OmiBle {
    public interface Listener {
        void onDevice(String address, String name); void onPcm(short[] samples);
        void onStatus(String value); void onGap(); void onButton(int event);
        default void onBattery(int value) {} default void onLedState(LedState value) {}
    }
    public static final class LedState {
        public final boolean supported, busy; public final int brightness; public final String message;
        public LedState(boolean s, boolean b, int v, String m) { supported=s; busy=b; brightness=v; message=m; }
    }
    static volatile OmiBle instance;
    static volatile int connects, stops, exited, ledReads, ledWrites;
    static volatile boolean initialFailure;
    static CountDownLatch stopGate, stopEntered = new CountDownLatch(1);
    final Listener listener;
    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private volatile boolean stopping, quit;
    private Thread thread;
    public OmiBle(android.content.Context c, Listener l) {
        listener=l; instance=this;
        l.onLedState(new LedState(false,false,-1,"unknown")); l.onBattery(-1);
    }
    public void connect(String address) {
        if (android.app.Service.foregroundStarts == 0) throw new AssertionError("connect without foreground");
        connects++;
        thread = new Thread(() -> {
            try { while (!quit) tasks.take().run(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            finally { exited++; }
        }, "fake-OmiBle");
        thread.start();
        tasks.add(() -> {
            if (stopping) return;
            listener.onGap(); listener.onGap();
            if (initialFailure) listener.onStatus("Bluetooth unavailable; capture disconnected, restart to retry");
            else listener.onStatus("Connecting to selected Omi · keep wearable nearby");
        });
    }
    public void stop() {
        if (stopping || thread == null) return;
        stopping=true; stops++;
        tasks.add(() -> {
            stopEntered.countDown(); await(stopGate);
            listener.onBattery(-1); listener.onGap(); quit=true;
        });
    }
    public void readLedBrightness() { ledReads++; }
    public void setLedBrightness(int value) { ledWrites++; }
    static void send(Runnable action) throws Exception {
        OmiBle current=instance;
        CountDownLatch done = new CountDownLatch(1);
        if (current.stopping) return;
        current.tasks.add(() -> { try { if (!current.stopping) action.run(); } finally { done.countDown(); } });
        if (!done.await(3,TimeUnit.SECONDS)) throw new AssertionError("BLE callback blocked");
    }
    static void pcm(short[] samples) throws Exception { send(() -> instance.listener.onPcm(samples)); }
    static void button(int event) throws Exception { send(() -> instance.listener.onButton(event)); }
    static void gap() throws Exception { send(() -> instance.listener.onGap()); }
    private static void await(CountDownLatch gate) {
        if (gate!=null) try { gate.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
