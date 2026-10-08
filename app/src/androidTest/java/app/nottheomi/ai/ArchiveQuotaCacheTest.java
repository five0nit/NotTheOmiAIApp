package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;

/** Real SQLite/Keystore regressions for authenticated quota memoization; synthetic only. */
@SuppressWarnings("deprecation")
public final class ArchiveQuotaCacheTest extends AndroidTestCase {
    private String database, alias;
    private Recordings store;
    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        database = "archive-quota-cache-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.quota." + token;
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
    private SQLiteDatabase raw() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(database).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }
    private Map<?, ?> cache() throws Exception {
        Field field = Recordings.class.getDeclaredField("quotaCache");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(store);
    }
    private byte[] envelope(String id) {
        try (SQLiteDatabase db = raw(); Cursor cursor = db.rawQuery(
                "SELECT envelope FROM sessions WHERE id=?", new String[]{id})) {
            assertTrue(cursor.moveToFirst()); return cursor.getBlob(0);
        }
    }
    private void replaceEnvelope(String id, byte[] bytes) {
        try (SQLiteDatabase db = raw()) {
            ContentValues values = new ContentValues(); values.put("envelope", bytes);
            assertEquals(1, db.update("sessions", values, "id=?", new String[]{id}));
        }
    }
    private void expectCorrupt() throws Exception {
        try { store.totalBytes(); fail("Changed unauthenticated quota must not hit cache"); }
        catch (Recordings.CorruptRecordingException expected) { }
    }
    private String shapeQueryPlan(SQLiteDatabase db, String id, String kind) {
        StringBuilder plan = new StringBuilder();
        try (Cursor cursor = db.rawQuery("EXPLAIN QUERY PLAN SELECT COUNT(*),"
                + "COALESCE(SUM(plain_length),0),COALESCE(MIN(sequence),0),"
                + "COALESCE(MAX(sequence),-1) FROM chunks WHERE session_id=? AND kind=?",
                new String[]{id, kind})) {
            while (cursor.moveToNext()) plan.append(cursor.getString(3));
        }
        return plan.toString();
    }
    public void testShapeChecksUseCoveringIndexWithoutReadingPayloadRows() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        store.appendText(id, "Synthetic index fixture");
        try (SQLiteDatabase db = raw()) {
            for (String kind : new String[]{"audio", "text"}) {
                String plan = shapeQueryPlan(db, id, kind);
                assertTrue("Shape query must avoid payload-table reads: " + plan,
                        plan.contains("COVERING INDEX chunks_shape"));
            }
            // Negative control: the old primary index does not cover plain_length.
            db.execSQL("DROP INDEX chunks_shape");
            assertFalse(shapeQueryPlan(db, id, "audio").contains("COVERING INDEX"));
        }
        // Losing the optimization must not disable integrity checks.
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase db = raw()) { db.execSQL("UPDATE chunks SET plain_length=2 WHERE kind='audio'"); }
        expectCorrupt();
    }
    public void testUnchangedQuotaReusesSnapshotWhileAudioAdvanceRefreshes() throws Exception {
        String id = store.create().id;
        assertEquals(0L, store.totalBytes());
        Object before = cache().get(id);
        assertNotNull(before);
        assertEquals(0L, store.totalBytes());
        assertSame(before, cache().get(id));
        store.appendAudio(id, new byte[640], 640);
        assertEquals(640L, store.totalBytes());
        assertNotSame(before, cache().get(id));
        before = cache().get(id);
        store.rename(id, "Synthetic rename");
        assertEquals(640L, store.totalBytes());
        assertNotSame(before, cache().get(id));
    }
    public void testCorruptHistoricalManifestCannotBypassWarmQuota() throws Exception {
        String saved = store.create().id;
        store.appendAudio(saved, new byte[640], 640); store.finish(saved, "saved");
        String active = store.create().id;
        assertEquals(640L, store.totalBytes());
        byte[] original = envelope(saved), corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        replaceEnvelope(saved, corrupt);
        expectCorrupt();
        try { store.appendAudio(active, new byte[640], 640); fail("Must block corrupt quota"); }
        catch (Recordings.CorruptRecordingException expected) { }
        assertEquals(0L, store.find(active).bytes);
        replaceEnvelope(saved, original);
        assertEquals(640L, store.totalBytes());
        store.appendAudio(active, new byte[640], 640);
        assertEquals(1280L, store.totalBytes());
    }
    public void testCrossSessionCiphertextSubstitutionStillFailsAuthentication() throws Exception {
        String first = store.create().id; store.finish(first, "saved");
        String second = store.create().id; store.finish(second, "saved");
        assertEquals(0L, store.totalBytes());
        replaceEnvelope(second, envelope(first));
        expectCorrupt();
    }
    public void testOtherStoreWritesAndDeletesInvalidateWithoutNotifications() throws Exception {
        String id = store.create().id;
        assertEquals(0L, store.totalBytes());
        Recordings other = new Recordings(getContext(), database, alias);
        try {
            other.appendAudio(id, new byte[640], 640);
            assertEquals(640L, store.totalBytes());
            other.finish(id, "saved");
            assertEquals(640L, store.totalBytes());
            other.delete(id);
            assertEquals(0L, store.totalBytes());
            assertFalse(cache().containsKey(id));
            String next = other.create().id;
            other.appendAudio(next, new byte[1280], 1280);
            assertEquals(1280L, store.totalBytes());
        } finally { other.closeForTest(); }
    }
    public void testTransactionRollbackDoesNotIncreaseWarmQuota() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase db = raw()) {
            db.execSQL("CREATE TRIGGER reject_cached_manifest BEFORE UPDATE ON sessions "
                    + "BEGIN SELECT RAISE(ABORT,'synthetic quota rollback'); END");
        }
        try { store.appendAudio(id, new byte[1280], 1280); fail("Injected failure required"); }
        catch (android.database.SQLException expected) { }
        finally { try (SQLiteDatabase db = raw()) { db.execSQL("DROP TRIGGER reject_cached_manifest"); } }
        assertEquals(640L, store.totalBytes());
        assertEquals(640L, store.find(id).bytes);
        store.appendAudio(id, new byte[1280], 1280);
        assertEquals(1920L, store.totalBytes());
    }
    public void testUnauthenticatedPlainLengthsNeverReduceQuota() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase db = raw()) { db.execSQL("UPDATE chunks SET plain_length=2"); }
        expectCorrupt();
    }
    public void testWarmQuotaDetectsHistoricalAudioDeletion() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640); store.finish(id, "saved");
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase db = raw()) { db.delete("chunks", "session_id=?", new String[]{id}); }
        expectCorrupt();
    }
    public void testWarmQuotaDetectsTextSequenceMutation() throws Exception {
        String id = store.create().id;
        store.appendText(id, "Synthetic fixture");
        assertEquals(0L, store.totalBytes());
        try (SQLiteDatabase db = raw()) {
            db.execSQL("UPDATE chunks SET sequence=sequence+1 WHERE kind='text'");
        }
        expectCorrupt();
    }
    private void insertEmptyFixtures(int from, int to) throws Exception {
        Field databaseField = Recordings.class.getDeclaredField("db");
        databaseField.setAccessible(true);
        SQLiteDatabase owned = (SQLiteDatabase) databaseField.get(store);
        java.lang.reflect.Method encrypt = Recordings.class.getDeclaredMethod("encrypt",
                String.class, String.class, long.class, byte[].class);
        encrypt.setAccessible(true);
        byte[] plain = ("{\"version\":1,\"title\":\"Synthetic\",\"status\":\"saved\","
                + "\"createdAt\":1,\"bytes\":0,\"audioCount\":0,\"textCount\":0}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        owned.beginTransaction();
        try {
            for (int i = from; i < to; i++) {
                String id = "synthetic-quota-boundary-" + i;
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("envelope", (byte[]) encrypt.invoke(store, id, "meta", 0L, plain));
                owned.insertOrThrow("sessions", null, values);
            }
            owned.setTransactionSuccessful();
        } finally { owned.endTransaction(); }
    }
    public void testRealEncryptedFixturesAtAndAcrossCacheCap() throws Exception {
        Field limitField = Recordings.class.getDeclaredField("MAX_QUOTA_CACHE_ENTRIES");
        limitField.setAccessible(true);
        int limit = limitField.getInt(null);
        insertEmptyFixtures(0, limit - 1);
        assertEquals(0L, store.totalBytes()); assertEquals(limit - 1, cache().size());
        String resident = "synthetic-quota-boundary-0";
        Object snapshot = cache().get(resident);
        insertEmptyFixtures(limit - 1, limit);
        assertEquals(0L, store.totalBytes()); assertEquals(limit, cache().size());
        insertEmptyFixtures(limit, limit + 2);
        String overflow = "synthetic-quota-boundary-" + limit;
        for (int i = 0; i < 3; i++) {
            assertEquals(0L, store.totalBytes()); assertEquals(limit, cache().size());
            assertSame(snapshot, cache().get(resident)); assertFalse(cache().containsKey(overflow));
        }
        for (String id : new String[]{resident, overflow}) {
            byte[] original = envelope(id), corrupt = original.clone();
            corrupt[corrupt.length - 1] ^= 1;
            replaceEnvelope(id, corrupt); expectCorrupt(); replaceEnvelope(id, original);
            assertEquals(0L, store.totalBytes());
        }
    }
    public void testAllAppendSizesValidateWarmHistoryBeforeWriting() throws Exception {
        String saved = store.create().id;
        store.appendAudio(saved, new byte[640], 640); store.finish(saved, "saved");
        String active = store.create().id;
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase db = raw()) {
            db.execSQL("UPDATE chunks SET plain_length=2 WHERE session_id=?", new Object[]{saved});
        }
        for (int length : new int[]{0, 6400, Recordings.MAX_CHUNK_BYTES, Recordings.MAX_CHUNK_BYTES + 2}) {
            try {
                store.appendAudio(active, new byte[length], length);
                fail("Every request size must validate history before writing: " + length);
            } catch (Recordings.CorruptRecordingException expected) { }
            assertEquals(0L, store.find(active).bytes);
        }
    }
    public void testSingleChunkBoundaryPreservesExactBytesAndReopen() throws Exception {
        String id = store.create().id;
        byte[] exact = new byte[Recordings.MAX_CHUNK_BYTES];
        for (int i = 0; i < exact.length; i++) exact[i] = (byte) (i * 31);
        store.appendAudio(id, exact, exact.length);
        store.appendAudio(id, new byte[0], 0);
        store.closeForTest(); store = new Recordings(getContext(), database, alias);
        assertEquals((long) exact.length, store.totalBytes());
        java.io.ByteArrayOutputStream actual = new java.io.ByteArrayOutputStream();
        store.forEachPcm(id, pcm -> actual.write(pcm));
        assertTrue(java.util.Arrays.equals(exact, actual.toByteArray()));
    }
    public void testMultipleChunksAndReopenPreserveAuthenticatedQuota() throws Exception {
        String id = store.create().id;
        int length = Recordings.MAX_CHUNK_BYTES * 3 + 642;
        store.appendAudio(id, new byte[length], length);
        assertEquals((long) length, store.totalBytes());
        store.closeForTest(); store = new Recordings(getContext(), database, alias);
        assertEquals((long) length, store.totalBytes());
        store.appendAudio(id, new byte[640], 640);
        assertEquals(length + 640L, store.totalBytes());
    }
}
