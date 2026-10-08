package android.os;

import java.util.*;

/** Host scheduler: actual BLE thread identity, explicit virtual deadlines. */
public final class Looper {
    static final Looper MAIN = new Looper();
    static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();
    final PriorityQueue<Handler.Entry> queue = new PriorityQueue<>(
        Comparator.comparingLong(Handler.Entry::when).thenComparingLong(Handler.Entry::order));
    boolean running, quitting;
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return CURRENT.get(); }
}
