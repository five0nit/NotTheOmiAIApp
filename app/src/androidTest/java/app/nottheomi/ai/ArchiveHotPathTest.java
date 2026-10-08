package app.nottheomi.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;

/** Real encrypted SQLite cache invariants. Synthetic data; not physical endurance proof. */
@SuppressWarnings("deprecation")
public final class ArchiveHotPathTest extends AndroidTestCase {
    private Recordings store;
    private String database, alias;
    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        database = "archive-hot-path-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.hotpath." + token;
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
    private SQLiteDatabase owned() throws Exception {
        Field field = Recordings.class.getDeclaredField("db"); field.setAccessible(true);
        return (SQLiteDatabase) field.get(store);
    }
    private SQLiteDatabase external() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(database).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }
    private String history() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        store.appendText(id, "Synthetic history text");
        store.finish(id, "saved");
        return id;
    }
    private void rejectedAppend(String id) throws Exception {
        try { store.appendAudio(id, new byte[640], 640); fail("Corrupt history must block append"); }
        catch (Recordings.CorruptRecordingException expected) { }
    }
    private long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst()); return cursor.getLong(0);
        }
    }
    public void testGrowingAudioAndTextAvoidRepeatedHistoricalScans() throws Exception {
        for (int h = 0; h < 48; h++) history();
        String id = store.create().id;
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        // Grow the active recording as well as retaining many populated sessions.
        for (int i = 0; i < 1024; i++) {
            byte[] pcm = new byte[320]; Arrays.fill(pcm, (byte) i);
            store.appendAudio(id, pcm, pcm.length); expected.write(pcm);
        }
        long scans = store.quotaFullScanCountForTest();
        for (int i = 0; i < 100; i++) {
            byte[] pcm = new byte[640]; Arrays.fill(pcm, (byte) (i * 7));
            store.appendAudio(id, pcm, pcm.length); expected.write(pcm);
            if (i % 5 == 0) store.appendText(id, "Synthetic live sentence " + i);
            store.appendAudio(id, new byte[0], 0);
            assertEquals(48L * 640 + expected.size(), store.totalBytes());
        }
        assertEquals("Trusted appends, empty requests and quota reads must not rescan history",
                scans, store.quotaFullScanCountForTest());
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        store.forEachPcm(id, pcm -> actual.write(pcm));
        assertTrue(Arrays.equals(expected.toByteArray(), actual.toByteArray()));
    }
    public void testSameConnectionMutationInvalidatesWarmQuota() throws Exception {
        String saved = history(), id = store.create().id;
        store.totalBytes();
        owned().execSQL("UPDATE chunks SET plain_length=2 WHERE session_id=? AND kind='audio'",
                new Object[]{saved});
        rejectedAppend(id);
        assertEquals(0L, store.find(id).bytes);
    }
    public void testExternalCommitAfterWarmQuotaCannotBypassAdmission() throws Exception {
        String saved = history(), id = store.create().id;
        store.totalBytes();
        try (SQLiteDatabase db = external()) {
            db.execSQL("DELETE FROM chunks WHERE session_id=? AND kind='text'", new Object[]{saved});
        }
        rejectedAppend(id);
        assertEquals(0L, store.find(id).bytes);
    }
    public void testRolledBackOwnWritesDoNotPoisonLaterQuota() throws Exception {
        String saved = history(), id = store.create().id;
        store.totalBytes();
        SQLiteDatabase db = owned();
        db.beginTransaction();
        try { db.execSQL("DELETE FROM chunks WHERE session_id=?", new Object[]{saved}); }
        finally { db.endTransaction(); }
        store.appendAudio(id, new byte[640], 640);
        assertEquals(1280L, store.totalBytes());
        assertEquals(640L, store.find(saved).bytes);
    }
    public void testNestedTransactionNeverPublishesUncommittedQuota() throws Exception {
        String id = store.create().id;
        store.totalBytes();
        SQLiteDatabase db = owned();
        db.beginTransaction();
        try {
            try { store.appendAudio(id, new byte[640], 640); fail("Caller transaction must be rejected"); }
            catch (IllegalStateException expected) { }
        } finally { db.endTransaction(); }
        assertEquals(0L, store.totalBytes());
        store.appendAudio(id, new byte[640], 640);
        assertEquals(640L, store.totalBytes());
    }
    public void testConnectionSentinelLossForcesRevalidation() throws Exception {
        history(); String id = store.create().id;
        store.totalBytes(); long scans = store.quotaFullScanCountForTest();
        Field field = Recordings.class.getDeclaredField("QUOTA_CONNECTION"); field.setAccessible(true);
        String table = (String) field.get(null);
        owned().execSQL("DROP TABLE temp." + table);
        store.appendAudio(id, new byte[640], 640);
        assertTrue(store.quotaFullScanCountForTest() > scans);
        assertEquals(1280L, store.totalBytes());
    }
    public void testTemporaryShadowCannotSubstituteArchive() throws Exception {
        history(); String id = store.create().id; store.totalBytes();
        owned().execSQL("CREATE TEMP TABLE chunks AS SELECT * FROM main.chunks WHERE 0");
        rejectedAppend(id);
        owned().execSQL("DROP TABLE temp.chunks");
        assertEquals(640L, store.totalBytes());
    }
    public void testTriggerMutationRollsBackWholeChunk() throws Exception {
        String saved = history(), id = store.create().id;
        store.totalBytes();
        SQLiteDatabase db = owned();
        db.execSQL("CREATE TRIGGER corrupt_history AFTER INSERT ON chunks WHEN NEW.kind='audio' "
                + "BEGIN UPDATE chunks SET plain_length=2 WHERE session_id='" + saved + "' AND kind='audio'; END");
        try { rejectedAppend(id); }
        finally { db.execSQL("DROP TRIGGER corrupt_history"); }
        assertEquals(0L, store.find(id).bytes);
        assertEquals(640L, store.totalBytes());
        store.appendAudio(id, new byte[640], 640);
        assertEquals(1280L, store.totalBytes());
    }
    public void testTriggerIgnoreCannotReplaceChunkWithSameChangeCount() throws Exception {
        String saved = history(), id = store.create().id;
        store.totalBytes();
        SQLiteDatabase db = owned();
        db.execSQL("CREATE TRIGGER replace_chunk BEFORE INSERT ON chunks WHEN NEW.kind='audio' "
                + "BEGIN UPDATE chunks SET plain_length=2 WHERE session_id='" + saved + "' AND kind='audio'; "
                + "SELECT RAISE(IGNORE); END");
        try {
            try { store.appendAudio(id, new byte[640], 640); fail("Ignored insert must not publish quota"); }
            catch (Recordings.CorruptRecordingException | android.database.SQLException expected) { }
        } finally { db.execSQL("DROP TRIGGER replace_chunk"); }
        assertEquals(0L, store.find(id).bytes);
        assertEquals(640L, store.totalBytes());
    }
    public void testEmptyAndMultiChunkRequestsReopenWithExactOrder() throws Exception {
        history(); String id = store.create().id;
        byte[] pcm = new byte[Recordings.MAX_CHUNK_BYTES * 3 + 642];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 31);
        store.appendAudio(id, new byte[0], 0);
        store.appendAudio(id, pcm, pcm.length);
        store.closeForTest(); store = new Recordings(getContext(), database, alias);
        assertEquals(640L + pcm.length, store.totalBytes());
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        store.forEachPcm(id, chunk -> actual.write(chunk));
        assertTrue(Arrays.equals(pcm, actual.toByteArray()));
        assertEquals(4L, scalar(owned(), "SELECT COUNT(*) FROM chunks WHERE session_id='" + id + "' AND kind='audio'"));
    }
}
