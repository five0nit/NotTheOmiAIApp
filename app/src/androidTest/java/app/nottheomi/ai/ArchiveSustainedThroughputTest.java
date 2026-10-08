package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.util.Log;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Real archive/queue, disposable synthetic data only. Not wearable acceptance. */
@SuppressWarnings("deprecation")
public final class ArchiveSustainedThroughputTest extends AndroidTestCase {
    private Recordings store;
    private PowerManager.WakeLock wakeLock;
    private String database, alias;
    @Override protected void setUp() throws Exception {
        super.setUp();
        wakeLock = getContext().getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NotTheOmiAIApp:synthetic-sustained-probe");
        wakeLock.setReferenceCounted(false); wakeLock.acquire(900000);
        String token = UUID.randomUUID().toString();
        database = "archive-sustained-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.sustained." + token;
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
    // Seed real valid encrypted chunks without quadratic fixture setup via public append.
    private void seed(int history) throws Exception {
        Field f = Recordings.class.getDeclaredField("db"); f.setAccessible(true);
        SQLiteDatabase db = (SQLiteDatabase) f.get(store);
        Method encrypt = Recordings.class.getDeclaredMethod("encrypt", String.class,
                String.class, long.class, byte[].class); encrypt.setAccessible(true);
        for (int h = 0; h < history; h++) {
            String id = "synthetic-populated-" + h;
            int audioCount = 300, textCount = 30;
            byte[] audio = new byte[6400];
            byte[] text = "Synthetic fixture phrase. Not a user recording.\n".getBytes(StandardCharsets.UTF_8);
            JSONObject meta = new JSONObject().put("version", 1).put("title", "Synthetic fixture")
                    .put("status", "saved").put("createdAt", h + 1).put("bytes", audioCount * 6400L)
                    .put("audioCount", audioCount).put("textCount", textCount)
                    .put("autoRefine", false).put("refinementQueued", false);
            db.beginTransaction();
            try {
                ContentValues manifest = new ContentValues(); manifest.put("id", id);
                manifest.put("envelope", (byte[]) encrypt.invoke(store, id, "meta", 0L,
                        meta.toString().getBytes(StandardCharsets.UTF_8)));
                db.insertOrThrow("sessions", null, manifest);
                for (String kind : new String[]{"audio", "text"}) {
                    byte[] plain = "audio".equals(kind) ? audio : text;
                    int count = "audio".equals(kind) ? audioCount : textCount;
                    for (int n = 0; n < count; n++) {
                        ContentValues v = new ContentValues(); v.put("session_id", id); v.put("kind", kind);
                        v.put("sequence", n); v.put("plain_length", plain.length);
                        v.put("envelope", (byte[]) encrypt.invoke(store, id, kind, (long) n, plain));
                        db.insertOrThrow("chunks", null, v);
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        assertEquals(history * 300L * 6400L, store.totalBytes());
    }
    public void testSustainedPopulatedHistory() throws Exception { probe(false); }
    public void testLibraryReadDuringCapture() throws Exception { probe(true); }

    private void probe(boolean browse) throws Exception {
        final int history = 12, frames = 6000, frameSamples = 160, intervalMs = 10;
        seed(history);
        String id = store.create().id;
        OmiPcmQueue queue = new OmiPcmQueue();
        ArchiveProbeTrace historyTrace = new ArchiveProbeTrace(queue);
        AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong browseMs = new AtomicLong(-1), maxProducerLateMs = new AtomicLong();
        AtomicLong appendStarted = new AtomicLong();
        Thread consumer = Thread.currentThread();
        Thread producer = new Thread(() -> {
            try {
                short[] samples = new short[frameSamples];
                long start = SystemClock.elapsedRealtime();
                for (int i = 0; i < frames && !Thread.currentThread().isInterrupted(); i++) {
                    long due = start + i * (long) intervalMs;
                    long wait = due - SystemClock.elapsedRealtime();
                    if (wait > 0) Thread.sleep(wait);
                    long late = Math.max(0, SystemClock.elapsedRealtime() - due);
                    maxProducerLateMs.accumulateAndGet(late, Math::max);
                    Arrays.fill(samples, (short) (i & 0x7fff));
                    boolean admitted = queue.offer(samples);
                    historyTrace.producer(i, late, admitted);
                    if (!admitted) {
                        rejected.incrementAndGet();
                        Field events = OmiPcmQueue.class.getDeclaredField("events");
                        Field queuedBytes = OmiPcmQueue.class.getDeclaredField("bytes");
                        events.setAccessible(true); queuedBytes.setAccessible(true);
                        synchronized (queue) {
                            Log.i("OmiArchiveTrace", "rejected_at_ms=" + (SystemClock.elapsedRealtime() - start)
                                    + " frame=" + i + " queued_events=" + ((java.util.Deque<?>) events.get(queue)).size()
                                    + " queued_bytes=" + queuedBytes.getInt(queue)
                                    + " append_age_ms=" + (SystemClock.elapsedRealtime() - appendStarted.get())
                                    + " consumer=" + consumer.getState());
                        }
                        for (StackTraceElement frame : consumer.getStackTrace()) {
                            Log.i("OmiArchiveTrace", frame.toString());
                        }
                        break;
                    }
                    accepted.incrementAndGet();
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { queue.close(null); }
        }, "synthetic-sustained-pcm");
        Thread reader = new Thread(() -> {
            try {
                Thread.sleep(5000);
                long begin = SystemClock.elapsedRealtime();
                assertEquals(history + 1, store.list("").size());
                browseMs.set(SystemClock.elapsedRealtime() - begin);
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "synthetic-library-reader");
        List<Long> commits = new ArrayList<>();
        long bytes = 0, start = SystemClock.elapsedRealtime();
        producer.start(); if (browse) reader.start();
        try {
            while (true) {
                OmiPcmQueue.Event event = queue.pollBatch(200);
                if (event == null) continue;
                if (event.terminal) break;
                assertNotNull(event.pcm);
                long begin = SystemClock.elapsedRealtime();
                appendStarted.set(begin);
                Recordings.AppendTrace trace = new Recordings.AppendTrace();
                store.appendAudio(id, event.pcm, event.pcm.length, trace);
                long duration = SystemClock.elapsedRealtime() - begin;
                commits.add(duration);
                historyTrace.append(begin, duration, event.pcm.length, trace);
                bytes += event.pcm.length;
                Arrays.fill(event.pcm, (byte) 0);
            }
        } finally {
            producer.interrupt(); producer.join(10000);
            if (browse) reader.join(120000);
            historyTrace.dump("populated=1 browse=" + (browse ? 1 : 0));
            assertFalse("producer exited", producer.isAlive());
            assertFalse("reader exited", reader.isAlive());
        }
        store.finish(id, "saved");
        assertNull(failure.get());
        assertEquals(accepted.get() * frameSamples * 2L, bytes);
        assertEquals(bytes, store.find(id).bytes);
        assertEquals("saved", store.find(id).status);
        final long[] verifiedSamples = {0};
        store.forEachPcm(id, pcm -> {
            assertEquals("Whole PCM16 samples", 0, pcm.length & 1);
            for (int offset = 0; offset < pcm.length; offset += 2) {
                int actual = (pcm[offset] & 255) | ((pcm[offset + 1] & 255) << 8);
                int expected = (int) ((verifiedSamples[0] / frameSamples) & 0x7fff);
                assertEquals("Exact accepted PCM order/value", expected, actual);
                verifiedSamples[0]++;
            }
        });
        assertEquals("All retained PCM decrypted byte-exact", bytes, verifiedSamples[0] * 2);
        Collections.sort(commits);
        long p95 = commits.get(Math.min(commits.size() - 1, (int) (commits.size() * .95)));
        Log.i("OmiArchiveProbe", "history=" + history + " populated=1 browse=" + (browse ? 1 : 0)
                + " accepted=" + accepted.get() + " rejected=" + rejected.get() + " bytes=" + bytes
                + " elapsed_ms=" + (SystemClock.elapsedRealtime() - start) + " commits=" + commits.size()
                + " commit_p95_ms=" + p95 + " commit_max_ms=" + commits.get(commits.size() - 1)
                + " browse_ms=" + browseMs.get() + " producer_max_late_ms=" + maxProducerLateMs.get());
        assertEquals("Every sustained PCM frame admitted", frames, accepted.get());
        assertEquals("No sustained ingress rejection", 0, rejected.get());
    }
}
