package android.os;

/** Unlike the passive-sink BLE fixtures, this worker can actually be joined. */
public final class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); Handler.register(looper); }
    public Looper getLooper() { return looper; }
    @Override public void run() { Handler.loop(looper); }
    public boolean quitSafely() {
        synchronized (Handler.LOCK) {
            looper.quitting = true;
            looper.queue.removeIf(e -> e.when() > Handler.now());
            Handler.LOCK.notifyAll();
            return true;
        }
    }
}
