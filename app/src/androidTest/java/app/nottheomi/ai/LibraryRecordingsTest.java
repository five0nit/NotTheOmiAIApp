package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.test.AndroidTestCase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic-only production SQLite/AndroidKeyStore coverage; never opens the singleton. */
@SuppressWarnings("deprecation")
public final class LibraryRecordingsTest extends AndroidTestCase {
    private Recordings store;
    private String databaseName, alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        databaseName = "library-recordings-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.library." + token;
        store = new Recordings(getContext(), databaseName, alias);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (store != null) store.closeForTest();
            getContext().deleteDatabase(databaseName);
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            keys.deleteEntry(alias);
        } finally { super.tearDown(); }
    }

    public void testCanceledTranscriptReadsStopBeforeIoAndLeaveNoReaderLease() throws Exception {
        String id = saved("Cancellation fixture", "saved");
        try {
            Thread.currentThread().interrupt();
            try { store.find(id); fail("Interrupted full read must cancel"); }
            catch (CancellationException expected) { }
        } finally { Thread.interrupted(); }
        Method transcript = Recordings.class.getDeclaredMethod("transcript", String.class, long.class, boolean.class);
        transcript.setAccessible(true);
        try {
            Thread.currentThread().interrupt();
            try { transcript.invoke(store, "missing", 1L, false); fail("Chunk loop must check interruption before IO"); }
            catch (java.lang.reflect.InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof CancellationException);
            }
        } finally { Thread.interrupted(); }
        assertEquals("Cancellation fixture", store.find(id).text);
        store.delete(id); // No leaked lease from the canceled find.
        assertNull(store.find(id));
    }

    public void testEmptyActiveAndFinishedEmptySessionsAreDistinct() throws Exception {
        assertCatalogSize(0, 0);
        String id = store.create().id;
        assertCatalogSize(0, 0);
        assertNull(store.libraryPreview(id));
        assertNull(store.libraryPreview("absent-synthetic-id"));
        store.finish(id, "saved");
        Recordings.LibraryEntry entry = catalog().entries.get(0);
        assertEquals(id, entry.id);
        assertEquals("saved", entry.status);
        assertEquals(0L, entry.durationMs);
        Recordings.Session preview = store.libraryPreview(id);
        assertEquals("", preview.text);
        assertFalse(preview.truncated);
    }

    public void testActiveTextIsNeverReadOrCountedAsUnavailable() throws Exception {
        String saved = saved("Synthetic saved text", "saved");
        String active = store.create().id;
        store.appendText(active, "Synthetic unfinished text");
        replaceChunk(active, "text", 0, new byte[8]);
        List<String> before = encryptedSnapshot();
        Recordings.LibraryCatalog catalog = catalog();
        assertEquals(1, catalog.entries.size());
        assertEquals(saved, catalog.entries.get(0).id);
        assertEquals(0, catalog.unavailableCount);
        assertNull(store.libraryPreview(active));
        assertEquals(before, encryptedSnapshot());
    }

    public void testTimestampOrderTieBreakAndCalendarDayAcrossDst() throws Exception {
        // Creation order deliberately differs from authenticated wall-clock order.
        String late = saved("Late", "saved");
        String early = saved("Early", "interrupted");
        String tied = saved("Tied", "saved");
        String midnight = saved("Midnight", "saved");
        long beforeMidnight = Instant.parse("2026-10-03T13:59:59.999Z").toEpochMilli();
        long startOfDay = Instant.parse("2026-10-03T14:00:00Z").toEpochMilli();
        long afterDstJump = Instant.parse("2026-10-03T16:00:00Z").toEpochMilli();
        setAuthenticatedTimestamp(late, afterDstJump);
        setAuthenticatedTimestamp(early, beforeMidnight);
        setAuthenticatedTimestamp(tied, afterDstJump);
        setAuthenticatedTimestamp(midnight, startOfDay);
        List<String> tiedIds = new ArrayList<>(Arrays.asList(late, tied));
        java.util.Collections.sort(tiedIds);
        Recordings.LibraryCatalog catalog = catalog();
        assertEquals(4, catalog.entries.size());
        assertEquals(tiedIds.get(0), catalog.entries.get(0).id);
        assertEquals(tiedIds.get(1), catalog.entries.get(1).id);
        assertEquals(midnight, catalog.entries.get(2).id);
        assertEquals(early, catalog.entries.get(3).id);
        assertEquals(afterDstJump, catalog.entries.get(0).createdAt);
        assertEquals(startOfDay, catalog.entries.get(2).createdAt);
        assertEquals(beforeMidnight, catalog.entries.get(3).createdAt);
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        assertEquals(LocalDate.of(2026, 10, 4), day(catalog.entries.get(0), sydney));
        assertEquals(LocalDate.of(2026, 10, 4), day(catalog.entries.get(2), sydney));
        assertEquals(LocalDate.of(2026, 10, 3), day(catalog.entries.get(3), sydney));
        assertEquals(LocalDate.of(2026, 10, 3), day(catalog.entries.get(0), ZoneId.of("UTC")));
        assertEquals("interrupted", catalog.entries.get(3).status);
        // Calendar labels remain the caller's timezone decision, not a persisted day index.
        assertEquals(catalog.entries.get(0).createdAt, store.libraryPreview(tiedIds.get(0)).createdAt);
    }

    public void testCatalogIsNotRecentWindowAndSnapshotsAreImmutableAndFresh() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 14; i++) ids.add(saved("Synthetic row " + i, "saved"));
        Recordings.LibraryCatalog first = catalog();
        assertEquals(14, first.entries.size());
        try { first.entries.clear(); fail("Catalog list must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { first.entries.set(0, first.entries.get(0)); fail("Elements cannot be replaced"); }
        catch (UnsupportedOperationException expected) { }
        String id = ids.get(0);
        store.rename(id, "Changed synthetic title");
        assertEquals("Recording", entry(first, id).title);
        assertEquals("Changed synthetic title", entry(catalog(), id).title);
        store.delete(id);
        assertEquals(14, first.entries.size());
        assertCatalogSize(13, 0);
        store.closeForTest();
        store = new Recordings(getContext(), databaseName, alias);
        assertCatalogSize(13, 0);
        assertNull(store.libraryPreview(id));
    }

    public void testCatalogAndRefinedPreviewAreReadOnlyIncludingNoncesAndSchema() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        store.appendText(id, "Synthetic draft café 東京 🧪");
        store.rename(id, "Synthetic private title");
        store.finish(id, "saved");
        store.commitRefinementBatch(id, 0, 640, "Synthetic refined transcript");
        store.completeRefinement(id);
        List<String> before = encryptedSnapshot();
        long scans = store.quotaFullScanCountForTest();
        Recordings.LibraryEntry entry = catalog().entries.get(0);
        assertEquals(id, entry.id);
        assertEquals("Synthetic private title", entry.title);
        assertEquals("saved", entry.status);
        assertEquals(20L, entry.durationMs);
        Recordings.Session preview = store.libraryPreview(id);
        assertEquals(entry.createdAt, preview.createdAt);
        assertEquals("Synthetic refined transcript", preview.text);
        assertEquals("Synthetic draft café 東京 🧪", preview.liveText);
        assertEquals("complete", preview.transcriptState);
        assertFalse(preview.truncated);
        assertEquals(scans, store.quotaFullScanCountForTest());
        assertEquals(before, encryptedSnapshot());
        store.closeForTest();
        store = new Recordings(getContext(), databaseName, alias);
        assertEquals(entry.createdAt, catalog().entries.get(0).createdAt);
        assertEquals(preview.text, store.libraryPreview(id).text);
        assertEquals(before, encryptedSnapshot());
    }

    public void testCorruptManifestsAreCountedWithoutHidingHealthyRowsOrRepairing() throws Exception {
        String healthy = saved("Healthy", "saved");
        String malformed = saved("Malformed", "saved");
        String tampered = saved("Tampered", "saved");
        String swapped = saved("Swapped", "saved");
        byte[] original = manifest(malformed);
        replaceManifest(malformed, new byte[8]);
        byte[] damaged = manifest(tampered);
        damaged[damaged.length - 1] ^= 1;
        replaceManifest(tampered, damaged);
        replaceManifest(swapped, manifest(healthy));
        List<String> before = encryptedSnapshot();
        Recordings.LibraryCatalog catalog = catalog();
        assertEquals(1, catalog.entries.size());
        assertEquals(healthy, catalog.entries.get(0).id);
        assertEquals(3, catalog.unavailableCount);
        expectCorrupt(() -> store.libraryPreview(malformed));
        expectCorrupt(() -> store.libraryPreview(tampered));
        expectCorrupt(() -> store.libraryPreview(swapped));
        assertEquals("Healthy", store.libraryPreview(healthy).text);
        assertEquals(before, encryptedSnapshot());
        // An explicit fixture restoration must be reauthenticated, not hidden by a cache.
        replaceManifest(malformed, original);
        assertCatalogSize(2, 2);
    }

    public void testCatalogSkipsPayloadAuthenticationShapeChecksAndQuotaScans() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        for (int i = 0; i < 5; i++) store.appendText(id, "Synthetic line " + i);
        store.finish(id, "saved");
        try (SQLiteDatabase db = raw()) {
            assertEquals(1, db.delete("chunks", "session_id=? AND kind='text' AND sequence=0",
                    new String[]{id}));
            db.execSQL("UPDATE chunks SET plain_length=2 WHERE session_id=? AND kind='audio'",
                    new Object[]{id});
        }
        replaceChunk(id, "audio", 0, new byte[8]);
        long scans = store.quotaFullScanCountForTest();
        List<String> before = encryptedSnapshot();
        assertCatalogSize(1, 0);
        assertEquals(20L, catalog().entries.get(0).durationMs);
        Recordings.Session preview = store.libraryPreview(id);
        assertEquals("Synthetic line 1\nSynthetic line 2\nSynthetic line 3\nSynthetic line 4", preview.text);
        assertTrue(preview.truncated);
        assertEquals(before, encryptedSnapshot());
        assertEquals(scans, store.quotaFullScanCountForTest());
        expectCorrupt(() -> store.find(id));
        expectCorrupt(() -> store.exportWav(id, new ByteArrayOutputStream()));
        // Even selected text is not read by the metadata-only catalog.
        replaceChunk(id, "text", 4, new byte[8]);
        assertCatalogSize(1, 0);
        expectCorrupt(() -> store.libraryPreview(id));
        assertEquals(scans, store.quotaFullScanCountForTest());
    }

    public void testPreviewIgnoresOmittedCiphertextButFullReadStillAuthenticatesIt() throws Exception {
        String id = store.create().id;
        for (int i = 0; i < 5; i++) store.appendText(id, "Line " + i);
        store.finish(id, "saved");
        byte[] damaged = chunk(id, "text", 0);
        damaged[damaged.length - 1] ^= 1;
        replaceChunk(id, "text", 0, damaged);
        List<String> before = encryptedSnapshot();
        assertCatalogSize(1, 0);
        assertEquals("Line 1\nLine 2\nLine 3\nLine 4", store.libraryPreview(id).text);
        expectCorrupt(() -> store.find(id));
        assertEquals(before, encryptedSnapshot());
    }

    public void testPreviewFourChunkAndCharacterBoundsKeepFullFindAvailable() throws Exception {
        String id = store.create().id;
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String text = "Synthetic chunk " + i;
            store.appendText(id, text);
            if (i != 0) full.append('\n');
            full.append(text);
        }
        store.finish(id, "saved");
        Recordings.Session preview = store.libraryPreview(id);
        assertEquals("Synthetic chunk 2\nSynthetic chunk 3\nSynthetic chunk 4\nSynthetic chunk 5", preview.text);
        assertTrue(preview.truncated);
        assertEquals(full.toString(), store.find(id).text);
        String exact = saved(repeat('x', 600), "saved");
        assertEquals(600, store.libraryPreview(exact).text.length());
        assertFalse(store.libraryPreview(exact).truncated);
        String clipped = saved("prefix🧪" + repeat('z', 599), "saved");
        preview = store.libraryPreview(clipped);
        assertEquals(repeat('z', 599), preview.text);
        assertEquals(preview.text, preview.liveText);
        assertTrue(preview.truncated);
        assertFalse(Character.isLowSurrogate(preview.text.charAt(0)));
    }

    public void testPreviewSelectedTextAadFailureAndMissingTailReleaseReaderLease() throws Exception {
        String first = saved("First synthetic text", "saved");
        String second = saved("Second synthetic text", "saved");
        replaceChunk(second, "text", 0, chunk(first, "text", 0));
        List<String> before = encryptedSnapshot();
        expectCorrupt(() -> store.libraryPreview(second));
        assertCatalogSize(2, 0);
        assertEquals(before, encryptedSnapshot());
        assertTrue(((java.util.Map<?, ?>) field("readers")).isEmpty());
        try (SQLiteDatabase db = raw()) {
            assertEquals(1, db.delete("chunks", "session_id=? AND kind='text'", new String[]{second}));
        }
        expectCorrupt(() -> store.libraryPreview(second));
        assertTrue(((java.util.Map<?, ?>) field("readers")).isEmpty());
        assertEquals("First synthetic text", store.libraryPreview(first).text);
        // Only this explicit user-style deletion removes the damaged payload/manifest.
        store.delete(second);
        assertNull(store.libraryPreview(second));
    }

    public void testCorruptDerivativeDoesNotHideMetadataOrAuthenticatedDraft() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        store.appendText(id, "Synthetic original draft");
        store.finish(id, "saved");
        store.commitRefinementBatch(id, 0, 640, "Synthetic refined result");
        store.completeRefinement(id);
        try (SQLiteDatabase db = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", new byte[8]);
            assertEquals(1, db.update("refinement_chunks", values, "session_id=?", new String[]{id}));
        }
        List<String> before = encryptedSnapshot();
        assertCatalogSize(1, 0);
        Recordings.Session preview = store.libraryPreview(id);
        assertEquals("Synthetic original draft", preview.text);
        assertEquals("corrupt", preview.transcriptState);
        assertEquals(before, encryptedSnapshot());
    }

    public void testCancellationBeforeDiscoveryDuringIdsAndBetweenMetadataRows() throws Exception {
        expectCancelled(() -> store.libraryCatalog(() -> true));
        saved("One", "saved");
        saved("Two", "saved");
        List<String> before = encryptedSnapshot();
        AtomicInteger discoveryChecks = new AtomicInteger();
        expectCancelled(() -> store.libraryCatalog(() -> discoveryChecks.incrementAndGet() == 2));
        assertEquals(2, discoveryChecks.get());
        Object lock = field("lock");
        AtomicInteger unlockedChecks = new AtomicInteger();
        expectCancelled(() -> store.libraryCatalog(() -> !Thread.holdsLock(lock)
                && unlockedChecks.incrementAndGet() == 3));
        assertEquals(3, unlockedChecks.get());
        assertEquals(before, encryptedSnapshot());
        assertTrue(((java.util.Map<?, ?>) field("readers")).isEmpty());
        assertCatalogSize(2, 0);
    }

    public void testCaptureCanAppendBetweenCatalogRowsAndCancelledCallDoesNotPoisonStore()
            throws Exception {
        for (int i = 0; i < 3; i++) saved("Synthetic archive " + i, "saved");
        String active = store.create().id;
        Object lock = field("lock");
        CountDownLatch betweenRows = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger unlockedChecks = new AtomicInteger();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                store.libraryCatalog(() -> {
                    if (!Thread.holdsLock(lock) && unlockedChecks.incrementAndGet() == 3) {
                        betweenRows.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Reader release timed out");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                        return true;
                    }
                    return false;
                });
                readerFailure.set(new AssertionError("Catalog must cancel"));
            } catch (CancellationException expected) { }
            catch (Throwable error) { readerFailure.set(error); }
        }, "synthetic-library-reader");
        Thread writer = new Thread(() -> {
            try { store.appendAudio(active, new byte[640], 640); }
            catch (Throwable error) { writerFailure.set(error); }
        }, "synthetic-library-capture");
        boolean writerFinishedWhilePaused = false;
        reader.start();
        try {
            assertTrue("Observed metadata boundary outside writer lock", betweenRows.await(10, TimeUnit.SECONDS));
            writer.start();
            writer.join(10000);
            writerFinishedWhilePaused = !writer.isAlive();
        } finally {
            release.countDown();
            reader.join(15000);
            if (writer.getState() != Thread.State.NEW) writer.join(15000);
        }
        assertTrue("Capture not locked out by full library scan", writerFinishedWhilePaused);
        assertFalse(reader.isAlive());
        assertFalse(writer.isAlive());
        assertNull(readerFailure.get());
        assertNull(writerFailure.get());
        assertEquals(640L, store.find(active).bytes);
        assertCatalogSize(3, 0);
    }

    public void testDeletedSnapshotCandidateIsSkippedWithoutUnavailableCount() throws Exception {
        String deleted = saved("Delete candidate", "saved");
        String kept = saved("Kept candidate", "saved");
        Object lock = field("lock");
        AtomicInteger unlockedChecks = new AtomicInteger();
        Recordings.LibraryCatalog catalog = store.libraryCatalog(() -> {
            if (!Thread.holdsLock(lock) && unlockedChecks.incrementAndGet() == 2) {
                try { store.delete(deleted); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            return false;
        });
        assertEquals(0, catalog.unavailableCount);
        assertEquals(1, catalog.entries.size());
        assertEquals(kept, catalog.entries.get(0).id);
    }

    public void testDatabaseWideFailuresPropagateBeforeAndAfterIdSnapshot() throws Exception {
        String id = saved("Intact synthetic original", "saved");
        byte[] original = manifest(id);
        SQLiteDatabase owned = (SQLiteDatabase) field("db");
        owned.execSQL("ALTER TABLE sessions RENAME TO unavailable_sessions");
        try { expectSqlFailure(() -> catalog()); }
        finally { owned.execSQL("ALTER TABLE unavailable_sessions RENAME TO sessions"); }
        Object lock = field("lock");
        AtomicInteger unlockedChecks = new AtomicInteger();
        try {
            expectSqlFailure(() -> store.libraryCatalog(() -> {
                if (!Thread.holdsLock(lock) && unlockedChecks.incrementAndGet() == 2) {
                    owned.execSQL("ALTER TABLE sessions RENAME TO unavailable_sessions");
                }
                return false;
            }));
        } finally { owned.execSQL("ALTER TABLE unavailable_sessions RENAME TO sessions"); }
        assertTrue(Arrays.equals(original, manifest(id)));
        assertCatalogSize(1, 0);
        assertEquals("Intact synthetic original", store.libraryPreview(id).text);
    }

    public void testBoundedSyntheticCatalogPreviewTimingObservation() throws Exception {
        // A diagnostic observation, not a device-speed threshold or a benchmark claim.
        final int sessions = 8, chunks = 8;
        List<String> ids = new ArrayList<>();
        for (int session = 0; session < sessions; session++) {
            String id = store.create().id;
            ids.add(id);
            store.appendAudio(id, new byte[640], 640);
            for (int chunk = 0; chunk < chunks; chunk++) {
                store.appendText(id, "Synthetic session " + session + " chunk " + chunk);
            }
            store.finish(id, "saved");
        }
        long scans = store.quotaFullScanCountForTest();
        long started = android.os.SystemClock.elapsedRealtimeNanos();
        List<Recordings.Session> full = store.list("");
        long fullNanos = android.os.SystemClock.elapsedRealtimeNanos() - started;
        started = android.os.SystemClock.elapsedRealtimeNanos();
        Recordings.LibraryCatalog catalog = catalog();
        List<Recordings.Session> previews = new ArrayList<>();
        for (int i = 0; i < Math.min(12, catalog.entries.size()); i++) {
            previews.add(store.libraryPreview(catalog.entries.get(i).id));
        }
        long catalogPreviewNanos = android.os.SystemClock.elapsedRealtimeNanos() - started;
        assertEquals(sessions, full.size());
        assertEquals(sessions, catalog.entries.size());
        assertEquals(sessions, previews.size());
        assertEquals(0, catalog.unavailableCount);
        assertEquals(scans, store.quotaFullScanCountForTest());
        for (Recordings.Session preview : previews) {
            int session = ids.indexOf(preview.id);
            assertTrue(session >= 0);
            StringBuilder expected = new StringBuilder();
            for (int chunk = chunks - 4; chunk < chunks; chunk++) {
                if (expected.length() != 0) expected.append('\n');
                expected.append("Synthetic session ").append(session).append(" chunk ").append(chunk);
            }
            assertEquals(expected.toString(), preview.text);
            assertTrue(preview.truncated);
            assertTrue(preview.text.length() <= 600);
            assertFalse(preview.text.contains("chunk 0"));
        }
        for (Recordings.Session session : full) {
            assertTrue(session.text.contains("chunk 0"));
            assertFalse(session.truncated);
        }
        android.util.Log.i("LibraryRecordingsTest", "library_benchmark sessions=" + sessions
                + " text_chunks=" + (sessions * chunks) + " preview_limit=12 full_list_ms="
                + (fullNanos / 1_000_000L) + " catalog_preview_ms="
                + (catalogPreviewNanos / 1_000_000L));
    }

    private Recordings.LibraryCatalog catalog() throws Exception {
        return store.libraryCatalog(() -> false);
    }

    private void assertCatalogSize(int count, int unavailable) throws Exception {
        Recordings.LibraryCatalog catalog = catalog();
        assertEquals(count, catalog.entries.size());
        assertEquals(unavailable, catalog.unavailableCount);
    }

    private static Recordings.LibraryEntry entry(Recordings.LibraryCatalog catalog, String id) {
        for (Recordings.LibraryEntry entry : catalog.entries) if (id.equals(entry.id)) return entry;
        throw new AssertionError("Missing synthetic catalog entry");
    }

    private static LocalDate day(Recordings.LibraryEntry entry, ZoneId zone) {
        return Instant.ofEpochMilli(entry.createdAt).atZone(zone).toLocalDate();
    }

    private String saved(String text, String status) throws Exception {
        String id = store.create().id;
        store.appendText(id, text);
        store.finish(id, status);
        return id;
    }

    private Object field(String name) throws Exception {
        Field field = Recordings.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(store);
    }

    /** Deterministic timestamps use the production cipher/AAD and real Keystore key. */
    private void setAuthenticatedTimestamp(String id, long timestamp) throws Exception {
        Method decrypt = Recordings.class.getDeclaredMethod("decrypt", String.class, String.class,
                long.class, byte[].class, int.class);
        Method encrypt = Recordings.class.getDeclaredMethod("encrypt", String.class, String.class,
                long.class, byte[].class);
        decrypt.setAccessible(true);
        encrypt.setAccessible(true);
        byte[] plain = (byte[]) decrypt.invoke(store, id, "meta", 0L, manifest(id), 8192);
        byte[] updated = null;
        try {
            JSONObject metadata = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            metadata.put("createdAt", timestamp);
            updated = metadata.toString().getBytes(StandardCharsets.UTF_8);
            SQLiteDatabase owned = (SQLiteDatabase) field("db");
            owned.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put("envelope", (byte[]) encrypt.invoke(store, id, "meta", 0L, updated));
                assertEquals(1, owned.update("sessions", values, "id=?", new String[]{id}));
                owned.setTransactionSuccessful();
            } finally { owned.endTransaction(); }
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (updated != null) Arrays.fill(updated, (byte) 0);
        }
    }

    private SQLiteDatabase raw() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(databaseName).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }

    private byte[] manifest(String id) {
        try (SQLiteDatabase db = raw(); Cursor cursor = db.rawQuery(
                "SELECT envelope FROM sessions WHERE id=?", new String[]{id})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getBlob(0);
        }
    }

    private void replaceManifest(String id, byte[] envelope) {
        try (SQLiteDatabase db = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, db.update("sessions", values, "id=?", new String[]{id}));
        }
    }

    private byte[] chunk(String id, String kind, long sequence) {
        try (SQLiteDatabase db = raw(); Cursor cursor = db.rawQuery(
                "SELECT envelope FROM chunks WHERE session_id=? AND kind=? AND sequence=?",
                new String[]{id, kind, Long.toString(sequence)})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getBlob(0);
        }
    }

    private void replaceChunk(String id, String kind, long sequence, byte[] envelope) {
        try (SQLiteDatabase db = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, db.update("chunks", values,
                    "session_id=? AND kind=? AND sequence=?",
                    new String[]{id, kind, Long.toString(sequence)}));
        }
    }

    /** Exact logical ciphertext/nonces/schema bytes, not WAL or filesystem timestamps. */
    private List<String> encryptedSnapshot() {
        String[] queries = {
                "SELECT id,hex(envelope) FROM sessions ORDER BY id",
                "SELECT session_id,kind,sequence,plain_length,hex(envelope) FROM chunks ORDER BY session_id,kind,sequence",
                "SELECT session_id,hex(envelope) FROM refinements ORDER BY session_id",
                "SELECT session_id,sequence,plain_length,hex(envelope) FROM refinement_chunks ORDER BY session_id,sequence",
                "SELECT hex(nonce) FROM nonces ORDER BY nonce",
                "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name",
                "PRAGMA user_version"
        };
        List<String> snapshot = new ArrayList<>();
        try (SQLiteDatabase db = raw()) {
            for (String query : queries) {
                snapshot.add(query);
                try (Cursor cursor = db.rawQuery(query, null)) {
                    while (cursor.moveToNext()) {
                        for (int column = 0; column < cursor.getColumnCount(); column++) {
                            snapshot.add(cursor.getString(column));
                        }
                    }
                }
            }
        }
        return snapshot;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private interface Throwing { void run() throws Exception; }

    private static void expectCorrupt(Throwing action) throws Exception {
        try { action.run(); fail("Selected corruption must reach caller"); }
        catch (Recordings.CorruptRecordingException expected) {
            assertTrue(expected.getMessage().contains("nothing was deleted"));
        }
    }

    private static void expectCancelled(Throwing action) throws Exception {
        try { action.run(); fail("Cancellation must not return a partial catalog"); }
        catch (CancellationException expected) { }
    }

    private static void expectSqlFailure(Throwing action) throws Exception {
        try { action.run(); fail("Database-wide errors must not become unavailable rows"); }
        catch (SQLiteException expected) { }
    }
}
