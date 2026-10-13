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
    /** Payload-free state at the failed offer, not a later sample of the queue.
     * Reason precedence matches offer's original short-circuit checks.
     */
    static final class Rejection {
        static final int CLOSED = 1, NULL_PCM = 2, EMPTY_PCM = 3, OVERSIZE_PCM = 4,
                EVENT_CAPACITY = 5, BYTE_CAPACITY = 6, PAUSED = 7;
        final int reason, queuedBytes, queuedEvents, inputSamples;
        final boolean closed;
        Rejection(int reason, int queuedBytes, int queuedEvents, int inputSamples, boolean closed) {
            this.reason = reason; this.queuedBytes = queuedBytes; this.queuedEvents = queuedEvents;
            this.inputSamples = inputSamples; this.closed = closed;
        }
        String diagnostics() {
            return "rejection_reason=" + reason + " queued_bytes=" + queuedBytes
                    + " queued_events=" + queuedEvents + " input_samples=" + inputSamples
                    + " queue_closed=" + (closed ? 1 : 0);
        }
        String stopReason() {
            switch (reason) {
                case CLOSED: return "Omi audio queue closed; earlier audio retained";
                case NULL_PCM: return "Omi invalid PCM (null); earlier audio retained";
                case EMPTY_PCM: return "Omi invalid PCM (empty); earlier audio retained";
                case OVERSIZE_PCM: return "Omi invalid PCM (oversize); earlier audio retained";
                case EVENT_CAPACITY: return "Omi audio queue overflow (event capacity); earlier audio retained";
                case BYTE_CAPACITY: return "Omi audio queue overflow (byte capacity); earlier audio retained";
                default: throw new AssertionError("Unknown queue rejection code");
            }
        }
    }
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private Event terminal;
    // One reserved, payload-free fence AFTER the accepted prefix, BEFORE Stop.
    // No PCM/control may enter while it is pending, even as the prefix drains.
    private Event recovery;
    private boolean recoveryTaken;
    private Rejection lastRejection;

    synchronized Rejection lastRejection() { return lastRejection; }

    private int reject(int reason, short[] samples) {
        // Called only inside offer's queue monitor. No PCM, marker or ID retained.
        lastRejection = new Rejection(reason, bytes, events.size(), samples == null ? -1 : samples.length, closed);
        return reason;
    }

    synchronized boolean offer(short[] samples) {
        return offerResult(samples) == 0;
    }

    /** Atomically reject, snapshot and fence a capacity loss; no consumer race.
     * A null marker acknowledges an already ordered gap without another reset.
     * The fence still follows every accepted event and requires consumer acknowledgement.
     */
    synchronized int offerRecovering(short[] samples, String marker) {
        int result = offerResult(samples);
        if (result == Rejection.EVENT_CAPACITY || result == Rejection.BYTE_CAPACITY) reserveRecovery(marker);
        return result;
    }

    private int offerResult(short[] samples) {
        if (closed) return reject(Rejection.CLOSED, samples);
        // OmiBle's decoder emits <=1920 samples. Reject malformed/unbounded input.
        if (samples == null) return reject(Rejection.NULL_PCM, samples);
        if (samples.length == 0) return reject(Rejection.EMPTY_PCM, samples);
        if (samples.length > 1920) return reject(Rejection.OVERSIZE_PCM, samples);
        // Validation remains terminal in the service, even while ingress is paused.
        // Expected drops must not replace the original capacity diagnostic.
        if (recovery != null) return Rejection.PAUSED;
        if (events.size() >= MAX_EVENTS) return reject(Rejection.EVENT_CAPACITY, samples);
        if (bytes + samples.length * 2 > MAX_BYTES) return reject(Rejection.BYTE_CAPACITY, samples);
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            pcm[i * 2] = (byte) samples[i];
            pcm[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        events.addLast(new Event(pcm, null, false));
        bytes += pcm.length;
        notifyAll();
        return 0;
    }

    synchronized boolean bookmark(String marker) {
        if (closed || recovery != null || events.size() >= MAX_EVENTS) return false;
        events.addLast(new Event(null, marker, false));
        notifyAll();
        return true;
    }

    /** Ordinary radio gaps retain their FIFO behavior; a full FIFO uses the reserve. */
    synchronized boolean recover(String marker) {
        if (closed) return false;
        if (recovery != null) return true; // Same missing-audio episode, no new event.
        if (!bookmark(marker)) reserveRecovery(marker);
        return true;
    }

    private void reserveRecovery(String marker) {
        if (recovery == null) recovery = new Event(null, marker, false);
        notifyAll();
    }

    synchronized boolean paused() { return recovery != null; }

    /** The sole archive consumer acknowledges only after installing its gap boundary. */
    synchronized boolean completeRecovery(Event event) {
        if (event != recovery || !recoveryTaken) return false;
        recovery = null; recoveryTaken = false;
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
        if (events.isEmpty() && recovery == null && terminal == null) wait(timeoutMs);
        if (!events.isEmpty()) {
            Event event = events.removeFirst();
            if (event.pcm != null) bytes -= event.pcm.length;
            return event;
        }
        if (recovery != null) {
            if (recoveryTaken) return null;
            recoveryTaken = true;
            return recovery;
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
            boolean boundary = closed || recovery != null;
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
                if (count <= 1) return events.isEmpty() && recovery == null && terminal == null ? null : poll(0);
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
        events.clear(); bytes = 0; terminal = null; recovery = null; recoveryTaken = false; closed = true;
    }
}
