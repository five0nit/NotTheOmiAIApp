package android.os;

/** Unlike the passive-sink BLE fixtures, this worker can actually be joined. */
public final class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); Handler.register(looper); }
    public Looper getLooper() { return looper; }
    // Per-worker gate separates native close/quitSafely from actual JVM exit.
    // Tests install it before Stop, then release it in finally.
    public volatile java.util.concurrent.CountDownLatch exitGate;
    public final java.util.concurrent.CountDownLatch exitEntered = new java.util.concurrent.CountDownLatch(1);
    @Override public void run() {
        Handler.loop(looper);
        exitEntered.countDown();
        java.util.concurrent.CountDownLatch gate = exitGate;
        if (gate != null) {
            try { gate.await(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        }
    }
    public boolean quitSafely() {
        synchronized (Handler.LOCK) {
            looper.quitting = true;
            looper.queue.removeIf(e -> e.when() > Handler.now());
            Handler.LOCK.notifyAll();
            return true;
        }
    }
}
