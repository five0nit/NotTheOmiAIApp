package app.nottheomi.ai;

import java.util.ArrayDeque;

/**
 * Bounded, synchronized RAM-only screen projection, never a storage/ASR input.
 * Services publish finals only after their encrypted append succeeds. Process
 * death clears this view; the durable recording remains the source of truth.
 */
public final class LiveTranscript {
    public static final int MAX_SEGMENTS = 40;
    public static final int MAX_FINAL_CHARS = 12000;
    public static final int MAX_PARTIAL_CHARS = 2400;

    private final ArrayDeque<String> finals = new ArrayDeque<>();
    private String sessionId;
    private String partial = "";
    private int finalChars;
    private boolean finalsTruncated, partialTruncated;
    private long revision;

    /** Immutable coherent UI value. Strings contain no mutable backing state. */
    public static final class Snapshot {
        public final String sessionId, partial, finalized;
        public final long revision;
        /** True when older finals or part of the current preview were cropped. */
        public final boolean truncated;

        private Snapshot(String sessionId, String partial, String finalized,
                long revision, boolean truncated) {
            this.sessionId = sessionId;
            this.partial = partial;
            this.finalized = finalized;
            this.revision = revision;
            this.truncated = truncated;
        }
    }

    /** Clear immediately on accepted start (null), then associate the created ID. */
    public synchronized void reset(String id) {
        sessionId = id;
        partial = "";
        finals.clear();
        finalChars = 0;
        finalsTruncated = partialTruncated = false;
        revision++;
    }

    /** Keep the most recent bounded preview; null clears without losing finals. */
    public synchronized void partial(String text) {
        String value = text == null ? "" : text;
        boolean cropped = value.length() > MAX_PARTIAL_CHARS;
        String next = recent(value, MAX_PARTIAL_CHARS);
        if (!partial.equals(next) || partialTruncated != cropped) {
            partial = next;
            partialTruncated = cropped;
            revision++;
        }
    }

    /** Append chronologically, including genuinely repeated spoken sentences. */
    public synchronized void finalized(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String value = text.trim();
        if (value.length() > MAX_FINAL_CHARS) finalsTruncated = true;
        value = recent(value, MAX_FINAL_CHARS);
        finals.addLast(value);
        finalChars += value.length();
        // Account for the blank-line separators in the rendered limit as well.
        while (finals.size() > MAX_SEGMENTS
                || finalChars + (finals.size() - 1) * 2 > MAX_FINAL_CHARS) {
            finalChars -= finals.removeFirst().length();
            finalsTruncated = true;
        }
        revision++;
        // The service clears its endpoint preview; a final alone must not erase
        // a newer utterance's partial if another producer publishes one first.
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sessionId, partial, String.join("\n\n", finals),
                revision, finalsTruncated || partialTruncated);
    }

    /** Do not split a UTF-16 surrogate pair at the retained suffix boundary. */
    private static String recent(String value, int max) {
        int start = Math.max(0, value.length() - max);
        if (start > 0 && Character.isLowSurrogate(value.charAt(start))
                && Character.isHighSurrogate(value.charAt(start - 1))) start++;
        return value.substring(start);
    }
}
