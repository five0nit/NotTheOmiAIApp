package app.nottheomi.ai;

/** Pure-Java regression source; run only when the owner opens the test gate. */
public final class LiveTranscriptTest {
    public static void main(String[] args) {
        LiveTranscript display = new LiveTranscript();
        display.reset("session-a");
        display.partial("speaking");
        LiveTranscript.Snapshot before = display.snapshot();
        display.finalized("same sentence");
        display.finalized("same sentence");
        check(display.snapshot().finalized.equals("same sentence\n\nsame sentence"), "no speech deduplication");
        check(display.snapshot().partial.equals("speaking"), "final alone preserves preview");
        display.partial(null);
        check(display.snapshot().partial.isEmpty(), "stop can clear preview");
        check(!display.snapshot().finalized.isEmpty(), "stop retains finals");
        check(before.partial.equals("speaking") && before.finalized.isEmpty(), "immutable snapshot");
        check(display.snapshot().revision > before.revision, "revision advances");
        long revision = display.snapshot().revision;
        display.partial("");
        display.finalized("   ");
        display.finalized(null);
        check(display.snapshot().revision == revision, "empty/no-op updates are stable");

        display.reset("session-b");
        for (int i = 0; i < 41; i++) display.finalized("segment-" + i);
        String[] segments = display.snapshot().finalized.split("\n\n");
        check(segments.length == 40 && segments[0].equals("segment-1")
                && segments[39].equals("segment-40"), "bounded chronological segments");
        check(display.snapshot().truncated, "segment eviction is disclosed");

        display.reset("session-c");
        display.finalized(repeat('a', 6000));
        display.finalized(repeat('b', 6000));
        check(display.snapshot().finalized.length() <= 12000, "separators count toward bound");
        check(display.snapshot().finalized.equals(repeat('b', 6000)), "oldest segment evicted first");
        display.finalized("x\ud83d\ude00" + repeat('z', 11999));
        check(display.snapshot().finalized.length() <= 12000, "oversized final bounded");
        check(!Character.isLowSurrogate(display.snapshot().finalized.charAt(0)), "final surrogate boundary");
        display.partial("x\ud83d\ude00" + repeat('p', 2399));
        check(display.snapshot().partial.length() <= 2400, "preview bounded");
        check(!Character.isLowSurrogate(display.snapshot().partial.charAt(0)), "preview surrogate boundary");
        check(display.snapshot().truncated, "cropping is disclosed");

        revision = display.snapshot().revision;
        display.reset(null);
        LiveTranscript.Snapshot reset = display.snapshot();
        check(reset.sessionId == null && reset.partial.isEmpty() && reset.finalized.isEmpty()
                && !reset.truncated && reset.revision > revision, "accepted start clears stale display");
        display.reset("session-d");
        check("session-d".equals(display.snapshot().sessionId), "created session association");
        display.partial(repeat('p', 2401));
        check(display.snapshot().truncated, "preview-only crop is disclosed");
        display.partial("");
        check(!display.snapshot().truncated, "cleared preview resets its crop flag");
        System.out.println("LiveTranscript regression checks passed");
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
