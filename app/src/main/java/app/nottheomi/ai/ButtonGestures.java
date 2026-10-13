package app.nottheomi.ai;

/** Optional grouping of finalized stock SINGLE/DOUBLE notifications, not raw edges.
 * Triple-only retains the fixed 650ms window. Assigning four/five taps extends it
 * by 300ms per extra tap, from the first notification, never a sliding deadline.
 * Connection owners reset on loss/replacement and fence configuration generations.
 */
final class ButtonGestures {
    static final long WINDOW_MS = 650;
    private int taps, maxTaps = 2;
    private long deadline = -1;
    static int normalizeMaxTaps(int value) { return Math.max(2, Math.min(5, value)); }
    static long windowMs(int maxTaps) {
        int bounded = normalizeMaxTaps(maxTaps);
        return bounded == 2 ? 0 : WINDOW_MS + (bounded - 3) * 300L;
    }
    long deadline() { return deadline; }
    void reset() { taps = 0; deadline = -1; }
    private static int gesture(int taps) {
        switch (taps) {
            case 1: return ButtonEvent.SINGLE;
            case 2: return ButtonEvent.DOUBLE;
            case 3: return ButtonEvent.TRIPLE;
            case 4: return ButtonEvent.QUADRUPLE;
            case 5: return ButtonEvent.QUINTUPLE;
            default: return ButtonEvent.NONE;
        }
    }
    int expire(long now) {
        if (taps == 0 || now < deadline) return ButtonEvent.NONE;
        int event = gesture(taps);
        reset(); return event;
    }
    /** Source-compatible triple opt-in. */
    int[] accept(int event, long now, boolean enabled) { return accept(event, now, enabled ? 3 : 2); }
    int[] accept(int event, long now, int highestAssignedTaps) {
        if (event != ButtonEvent.SINGLE && event != ButtonEvent.DOUBLE) return new int[0];
        int bounded = normalizeMaxTaps(highestAssignedTaps);
        if (maxTaps != bounded) { reset(); maxTaps = bounded; }
        if (bounded == 2) { reset(); return new int[]{event}; }
        int expired = expire(now);
        int emitted = ButtonEvent.NONE;
        // A stock double is indivisible: overflowing it cannot manufacture an
        // assigned higher gesture by borrowing one tap from that notification.
        if (taps + event > bounded) {
            emitted = gesture(taps);
            reset();
        }
        if (taps == 0) {
            long window = windowMs(bounded);
            deadline = now > Long.MAX_VALUE - window ? Long.MAX_VALUE : now + window;
        }
        taps += event;
        if (taps == bounded) { emitted = gesture(taps); reset(); }
        if (expired != ButtonEvent.NONE && emitted != ButtonEvent.NONE) return new int[]{expired, emitted};
        if (expired != ButtonEvent.NONE || emitted != ButtonEvent.NONE)
            return new int[]{expired != ButtonEvent.NONE ? expired : emitted};
        return new int[0];
    }
}
