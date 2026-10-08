package app.nottheomi.ai;

import android.os.SystemClock;
import android.os.PowerManager;
import android.test.AndroidTestCase;
import android.util.Log;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic PCM only, real SQLite + AndroidKeyStore. Never opens the user's store.
 * Measures existing archive/queue throughput, not BLE or physical background acceptance.
 */
@SuppressWarnings("deprecation")
public final class ArchiveThroughputTest extends AndroidTestCase {
    private Recordings store;
    private PowerManager.WakeLock wakeLock;
    private String database, alias;
    @Override protected void setUp() throws Exception {
        super.setUp();
        // Match the real capture service's CPU lock. Without it, elapsedRealtime-paced
        // synthetic audio catches up in a false burst after device sleep.
        wakeLock = getContext().getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NotTheOmiAIApp:synthetic-archive-probe");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(120000);
        String token = UUID.randomUUID().toString();
        database = "archive-throughput-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.throughput." + token;
        store = new Recordings(getContext(), database, alias);
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (store != null) store.closeForTest();
            if (database != null) getContext().deleteDatabase(database);
            if (alias != null) {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null); keys.deleteEntry(alias);
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            super.tearDown();
        }
    }
    public void testPacedEmptyHistory() throws Exception { probe(0); }
    public void testPacedTwelveHistory() throws Exception { probe(12); }
    public void testPacedFortyEightHistory() throws Exception { probe(48); }

    private void probe(int history) throws Exception {
        for (int i = 0; i < history; i++) store.finish(store.create().id, "saved");
        String id = store.create().id;
        OmiPcmQueue queue = new OmiPcmQueue();
        ArchiveProbeTrace historyTrace = new ArchiveProbeTrace(queue);
        AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                short[] samples = new short[320]; // 20ms at 16kHz, synthetic silence.
                long start = SystemClock.elapsedRealtime();
                for (int i = 0; i < 300 && !Thread.currentThread().isInterrupted(); i++) {
                    long wait = start + i * 20L - SystemClock.elapsedRealtime();
                    if (wait > 0) Thread.sleep(wait);
                    long late = Math.max(0, SystemClock.elapsedRealtime() - (start + i * 20L));
                    boolean admitted = queue.offer(samples);
                    historyTrace.producer(i, late, admitted);
                    if (!admitted) { rejected.incrementAndGet(); break; }
                    accepted.incrementAndGet();
                }
            } catch (Throwable failure) { producerFailure.set(failure); }
            finally { queue.close(null); }
        }, "synthetic-paced-pcm");
        List<Long> commits = new ArrayList<>();
        long bytes = 0, start = SystemClock.elapsedRealtime();
        producer.start();
        try {
            while (true) {
                OmiPcmQueue.Event event = queue.pollBatch(200);
                if (event == null) continue;
                if (event.terminal) break;
                assertNotNull(event.pcm);
                long begin = SystemClock.elapsedRealtime();
                Recordings.AppendTrace trace = new Recordings.AppendTrace();
                store.appendAudio(id, event.pcm, event.pcm.length, trace);
                long duration = SystemClock.elapsedRealtime() - begin;
                commits.add(duration);
                historyTrace.append(begin, duration, event.pcm.length, trace);
                bytes += event.pcm.length;
            }
        } finally {
            producer.interrupt(); producer.join(10000);
            historyTrace.dump("populated=0 history=" + history);
            assertFalse("synthetic producer exited", producer.isAlive());
        }
        assertNull(producerFailure.get());
        store.finish(id, "saved");
        assertEquals(accepted.get() * 640L, bytes);
        assertEquals(bytes, store.find(id).bytes);
        Collections.sort(commits);
        long elapsed = SystemClock.elapsedRealtime() - start;
        long p95 = commits.get(Math.min(commits.size() - 1, (int) (commits.size() * .95)));
        Log.i("OmiArchiveProbe", "history=" + history + " accepted=" + accepted.get()
                + " rejected=" + rejected.get() + " bytes=" + bytes + " elapsed_ms=" + elapsed
                + " commits=" + commits.size() + " commit_p95_ms=" + p95
                + " commit_max_ms=" + commits.get(commits.size() - 1));
        assertEquals("Every paced PCM frame admitted", 300, accepted.get());
        assertEquals("No ingress rejection with retained history", 0, rejected.get());
    }
}
