package android.os;

import java.util.*;

/** Deterministic deadline clock; real worker thread and main-thread pump.
 * Advancing jumps the clock; tests advance one relevant deadline at a time.
 * drain() fences all currently runnable Handler work, not service/storage work.
 */
public final class Handler {
    static final Object LOCK = new Object();
    static final List<Looper> loopers = new ArrayList<>();
    static { loopers.add(Looper.MAIN); }
    private static long clock, sequence;
    record Entry(long when, long order, Handler owner, Runnable task) {}
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public Looper getLooper() { return looper; }
    static void register(Looper l) { synchronized (LOCK) { loopers.add(l); } }
    public static long now() { synchronized (LOCK) { return clock; } }
    public boolean post(Runnable r) { return postDelayed(r, 0); }
    public boolean postDelayed(Runnable r, long delay) {
        synchronized (LOCK) {
            if (looper.quitting) return false;
            looper.queue.add(new Entry(clock + delay, sequence++, this, r)); LOCK.notifyAll(); return true;
        }
    }
    public void removeCallbacks(Runnable r) {
        synchronized (LOCK) { looper.queue.removeIf(e -> e.owner() == this && e.task() == r); }
    }
    public void removeCallbacksAndMessages(Object token) {
        if (token != null) throw new UnsupportedOperationException("untagged callbacks only");
        synchronized (LOCK) { looper.queue.removeIf(e -> e.owner() == this); }
    }
    static boolean due(Looper l) { return !l.queue.isEmpty() && l.queue.peek().when() <= clock; }
    static void execute(Looper l, Entry e) {
        Looper.CURRENT.set(l);
        try { e.task().run(); }
        finally { Looper.CURRENT.remove(); synchronized (LOCK) { l.running = false; LOCK.notifyAll(); } }
    }
    static void loop(Looper l) {
        while (true) {
            Entry e;
            synchronized (LOCK) {
                while (!due(l)) {
                    if (l.quitting) return;
                    try { LOCK.wait(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                e = l.queue.remove(); l.running = true;
            }
            execute(l, e);
        }
    }
    public static void drain() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            Entry e = null;
            synchronized (LOCK) {
                if (due(Looper.MAIN)) { e = Looper.MAIN.queue.remove(); Looper.MAIN.running = true; }
                else if (loopers.stream().noneMatch(l -> l.running || due(l))) return;
                else {
                    if (System.nanoTime() >= deadline) throw new AssertionError("Handler drain timeout");
                    try { LOCK.wait(2); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
            }
            if (e != null) execute(Looper.MAIN, e);
        }
    }
    public static void advance(long milliseconds) {
        synchronized (LOCK) { clock += milliseconds; LOCK.notifyAll(); }
        drain();
    }
}
