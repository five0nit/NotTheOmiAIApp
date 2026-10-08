package app.nottheomi.ai;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded FIFO: PCM and controls share one ordering boundary; terminal cannot be lost. */
final class OmiPcmQueue {
    static final int MAX_BYTES = 64000; // Two seconds of PCM16 mono at 16 kHz.
    static final int MAX_EVENTS = 128;
    static final int MAX_BATCH_BYTES = 6400; // 200 ms; amortizes Keystore/SQLite commits.
    static final class Event {
        final byte[] pcm;
        final String marker;
        final boolean terminal;
        Event(byte[] pcm, String marker, boolean terminal) {
            this.pcm = pcm; this.marker = marker; this.terminal = terminal;
        }
    }
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private Event terminal;

    synchronized boolean offer(short[] samples) {
        if (closed) return false;
        // OmiBle's decoder emits <=1920 samples. Reject malformed/unbounded input.
        if (samples == null || samples.length == 0 || samples.length > 1920
                || events.size() >= MAX_EVENTS || bytes + samples.length * 2 > MAX_BYTES) return false;
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            pcm[i * 2] = (byte) samples[i];
            pcm[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        events.addLast(new Event(pcm, null, false));
        bytes += pcm.length;
        notifyAll();
        return true;
    }

    synchronized boolean bookmark(String marker) {
        if (closed || events.size() >= MAX_EVENTS) return false;
        events.addLast(new Event(null, marker, false));
        notifyAll();
        return true;
    }

    synchronized void close(String marker) {
        if (closed) return;
        closed = true;
        // Separate reserved slot keeps the boundary after every accepted event,
        // including a full queue. No later PCM can splice onto the prefix.
        terminal = new Event(null, marker, true);
        notifyAll();
    }

    synchronized Event poll(long timeoutMs) throws InterruptedException {
        if (events.isEmpty() && terminal == null) wait(timeoutMs);
        if (!events.isEmpty()) {
            Event event = events.removeFirst();
            if (event.pcm != null) bytes -= event.pcm.length;
            return event;
        }
        Event result = terminal;
        terminal = null;
        return result;
    }

    /** Batch adjacent audio before a durable commit, never across a control boundary.
     * Waiting retains the original queue limits; Stop/gap wakes and flushes the tail.
     */
    synchronized Event pollBatch(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            int length = 0, count = 0;
            boolean boundary = closed;
            for (Event event : events) {
                if (event.pcm == null || length + event.pcm.length > MAX_BATCH_BYTES) {
                    boundary = true;
                    break;
                }
                length += event.pcm.length;
                count++;
            }
            long remaining = deadline - System.nanoTime();
            if (boundary || length == MAX_BATCH_BYTES || remaining <= 0) {
                if (count <= 1) return events.isEmpty() && terminal == null ? null : poll(0);
                byte[] pcm = new byte[length];
                int offset = 0;
                for (int i = 0; i < count; i++) {
                    byte[] frame = events.removeFirst().pcm;
                    System.arraycopy(frame, 0, pcm, offset, frame.length);
                    offset += frame.length;
                    Arrays.fill(frame, (byte) 0);
                }
                bytes -= length;
                return new Event(pcm, null, false);
            }
            wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        }
    }

    synchronized void clear() {
        for (Event event : events) if (event.pcm != null) Arrays.fill(event.pcm, (byte) 0);
        events.clear(); bytes = 0; terminal = null; closed = true;
    }
}
