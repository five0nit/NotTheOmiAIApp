package app.nottheomi.ai;

import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded numeric-only history; emitted after synthetic ingestion, never in production. */
final class ArchiveProbeTrace {
    private final OmiPcmQueue queue;
    private final Field events, bytes;
    private final Deque<String> recent = new ArrayDeque<>();
    private final long start = SystemClock.elapsedRealtime();
    private boolean stalled;

    ArchiveProbeTrace(OmiPcmQueue queue) throws Exception {
        this.queue = queue;
        events = OmiPcmQueue.class.getDeclaredField("events");
        bytes = OmiPcmQueue.class.getDeclaredField("bytes");
        events.setAccessible(true); bytes.setAccessible(true);
    }
    synchronized void producer(int frame, long lateMs, boolean admitted) {
        if (admitted && frame % 20 != 0 && lateMs < 50) return;
        stalled |= !admitted;
        record("producer frame=" + frame + " late_ms=" + lateMs + " admitted=" + (admitted ? 1 : 0));
    }
    synchronized void append(long begin, long duration, int length, Recordings.AppendTrace trace) {
        stalled |= duration > 100;
        record("append start_ms=" + (begin - start) + " duration_ms=" + duration
                + " batch_bytes=" + length + " " + trace);
    }
    private void record(String message) {
        try {
            synchronized (queue) {
                if (recent.size() == 128) recent.removeFirst();
                recent.addLast("at_ms=" + (SystemClock.elapsedRealtime() - start)
                        + " events=" + ((Deque<?>) events.get(queue)).size()
                        + " bytes=" + bytes.getInt(queue) + " " + message);
            }
        } catch (IllegalAccessException error) { throw new AssertionError(error); }
    }
    synchronized void dump(String scope) {
        if (stalled) for (String entry : recent) Log.i("OmiArchiveTrace", scope + " " + entry);
    }
}
