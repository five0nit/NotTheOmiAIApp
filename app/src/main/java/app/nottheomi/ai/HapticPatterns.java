package app.nottheomi.ai;

/** Bounded stock modern-Omi patterns, independent of Android and transport.
 * Every pulse is a separate acknowledged one-byte preset, never a byte-array
 * pattern. Modern firmware presets: 1 = 100ms, 3 = 500ms. Unknown IDs retain the
 * existing double-pulse default; these IDs may be persisted, labels may not.
 */
public final class HapticPatterns {
    public static final String SHORT = "short", DOUBLE = "double", TRIPLE = "triple", LONG = "long";
    public static final String DEFAULT = DOUBLE;
    private static final long QUIET_MS = 120;

    private HapticPatterns() {}

    /** Fresh arrays prevent settings callers from modifying the catalogue. */
    public static String[] ids() { return new String[]{SHORT, DOUBLE, TRIPLE, LONG}; }
    public static String[] labels() {
        String[] ids = ids();
        for (int i = 0; i < ids.length; i++) ids[i] = label(ids[i]);
        return ids;
    }
    public static boolean isKnown(String id) {
        return SHORT.equals(id) || DOUBLE.equals(id) || TRIPLE.equals(id) || LONG.equals(id);
    }
    public static String normalize(String id) { return isKnown(id) ? id : DEFAULT; }
    public static String label(String id) {
        switch (normalize(id)) {
            case SHORT: return "Short pulse";
            case TRIPLE: return "Triple pulse";
            case LONG: return "Long pulse";
            default: return "Double pulse";
        }
    }
    public static int pulseCount(String id) {
        switch (normalize(id)) {
            case DOUBLE: return 2;
            case TRIPLE: return 3;
            default: return 1;
        }
    }
    public static int preset(String id, int pulse) {
        if (pulse < 0 || pulse >= pulseCount(id)) throw new IndexOutOfBoundsException("Haptic pulse index");
        return LONG.equals(normalize(id)) ? 3 : 1;
    }
    public static long pulseDurationMs(String id, int pulse) { return preset(id, pulse) == 3 ? 500 : 100; }
    /** Delay measured after the preceding ACK; strictly longer than that pulse. */
    public static long gapAfterMs(String id, int pulse) { return pulseDurationMs(id, pulse) + QUIET_MS; }
}
