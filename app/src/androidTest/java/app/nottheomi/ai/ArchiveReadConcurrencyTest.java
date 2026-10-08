package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real encrypted-store reader leases and immutable-prefix snapshots; synthetic only. */
@SuppressWarnings("deprecation")
public final class ArchiveReadConcurrencyTest extends AndroidTestCase {
    private Recordings store;
    private String database, alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        database = "archive-read-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.read." + token;
        store = new Recordings(getContext(), database, alias);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (store != null) store.closeForTest();
            getContext().deleteDatabase(database);
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null); keys.deleteEntry(alias);
        } finally { super.tearDown(); }
    }

    private Object field(String name) throws Exception {
        Field field = Recordings.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(store);
    }

    public void testSavedReadLeaseAllowsWriterAndPreventsDelete() throws Exception { concurrent(false); }
    public void testActiveReadKeepsCapturedPrefixDuringAppend() throws Exception { concurrent(true); }

    private void concurrent(boolean active) throws Exception {
        String id = store.create().id;
        StringBuilder expected = new StringBuilder();
        for (int n = 0; n < 96; n++) {
            String text = "Synthetic immutable phrase " + n;
            if (n != 0) expected.append('\n');
            expected.append(text);
            store.appendText(id, text);
        }
        store.appendAudio(id, new byte[640], 640);
        if (!active) store.finish(id, "saved");
        String writerId = active ? id : store.create().id;
        Object lock = field("lock");
        Map<?, ?> readers = (Map<?, ?>) field("readers");
        AtomicReference<Recordings.Session> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try { result.set(store.find(id)); }
            catch (Throwable error) { failure.set(error); }
        }, "synthetic-full-transcript-reader");
        boolean observed = false;
        reader.start();
        try {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (reader.isAlive() && SystemClock.elapsedRealtime() < deadline) {
                synchronized (lock) {
                    if (readers.containsKey(id)) {
                        observed = true;
                        if (!active) {
                            try { store.delete(id); fail("Reader lease must prevent deletion"); }
                            catch (IllegalStateException expectedFailure) { }
                        }
                        try { store.closeForTest(); fail("Reader lease must prevent close"); }
                        catch (IllegalStateException expectedFailure) { }
                        // This finishes while the reader's lease remains held: no archive-wide
                        // transcript lock, no extra chunks folded into the captured snapshot.
                        store.appendAudio(writerId, new byte[640], 640);
                        store.appendText(writerId, "Later synthetic phrase");
                        assertTrue(readers.containsKey(id));
                        break;
                    }
                }
                Thread.sleep(1);
            }
        } finally { reader.join(15000); }
        assertFalse("Reader finished", reader.isAlive());
        assertNull(failure.get());
        assertTrue("Observed a leased read outside writer lock", observed);
        assertEquals(expected.toString(), result.get().liveText);
        assertEquals(640L, result.get().bytes);
        synchronized (lock) { assertTrue(readers.isEmpty()); }
        store.finish(writerId, "saved");
        assertEquals(active ? 1280L : 640L, store.find(writerId).bytes);
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testFullReadFailureReleasesLease() throws Exception { corrupt(false); }
    public void testPreviewFailureReleasesLease() throws Exception { corrupt(true); }

    private void corrupt(boolean brief) throws Exception {
        String id = store.create().id;
        store.appendText(id, "Synthetic original");
        store.finish(id, "saved");
        ContentValues values = new ContentValues(); values.put("envelope", new byte[28]);
        ((SQLiteDatabase) field("db")).update("chunks", values,
                "session_id=? AND kind='text'", new String[]{id});
        try {
            if (brief) store.recent(1); else store.find(id);
            fail("Original corruption must propagate");
        } catch (Recordings.CorruptRecordingException expected) { }
        synchronized (field("lock")) { assertTrue(((Map<?, ?>) field("readers")).isEmpty()); }
        store.delete(id);
        assertNull(store.find(id));
    }
}
