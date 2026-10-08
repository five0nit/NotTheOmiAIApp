package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Synthetic-only Android SQLite/Keystore coverage for the bounded home history API. */
@SuppressWarnings("deprecation")
public final class RecentRecordingsTest extends AndroidTestCase {
    private Recordings store;
    private String databaseName;
    private String alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        databaseName = "recent-recordings-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.recent." + token;
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

    public void testNewestCreationOrderSurvivesRenameAndReopen() throws Exception {
        String first = saved("first", "saved");
        String second = saved("second", "interrupted");
        String third = saved("third", "completed");
        store.rename(first, "Renamed oldest");
        store.closeForTest();
        store = new Recordings(getContext(), databaseName, alias);
        List<Recordings.Session> recent = store.recent(3);
        assertEquals(3, recent.size());
        assertEquals(third, recent.get(0).id);
        assertEquals(second, recent.get(1).id);
        assertEquals(first, recent.get(2).id);
        assertEquals("interrupted", recent.get(1).status);
        assertEquals("Renamed oldest", recent.get(2).title);
        assertEquals(store.find(first).createdAt, recent.get(2).createdAt);
    }

    public void testLimitClampsAndSkipsActiveWithoutDecryptingOlderArchive() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 14; i++) ids.add(saved("synthetic " + i, "saved"));
        String active = store.create().id;
        store.appendText(active, "Not a saved session yet");
        // Corruption outside the selected window must not force an archive scan;
        // an active transcript must not be decrypted just to exclude its session.
        replaceManifest(ids.get(0), new byte[8]);
        replaceEnvelope(active, "text", 0, new byte[8]);
        assertTrue(store.recent(0).isEmpty());
        assertTrue(store.recent(Integer.MIN_VALUE).isEmpty());
        assertEquals(ids.get(13), store.recent(1).get(0).id);
        List<Recordings.Session> recent = store.recent(Integer.MAX_VALUE);
        assertEquals(12, recent.size());
        for (int i = 0; i < recent.size(); i++) {
            assertEquals(ids.get(13 - i), recent.get(i).id);
            assertFalse(active.equals(recent.get(i).id));
        }
    }

    public void testActiveIsExcludedUntilFinishedIncludingEmptyTranscript() throws Exception {
        assertTrue(store.recent(12).isEmpty());
        String id = store.create().id;
        assertTrue(store.recent(12).isEmpty());
        store.finish(id, "saved");
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals(id, recent.id);
        assertEquals("", recent.text);
        assertFalse(recent.truncated);
        assertEquals(0L, recent.bytes);
    }

    public void testMultilineUnicodePreviewMetadataAndCiphertextAreUnchanged() throws Exception {
        String id = store.create().id;
        String first = "Synthetic café 東京 🧪\nsecond line";
        String second = "\nΣ Straße\n";
        store.appendText(id, first);
        store.appendText(id, second);
        store.appendAudio(id, new byte[640], 640);
        store.rename(id, "Synthetic title");
        store.finish(id, "saved");
        byte[] manifest = manifest(id);
        byte[] text0 = envelope(id, "text", 0);
        byte[] text1 = envelope(id, "text", 1);
        byte[] audio = envelope(id, "audio", 0);
        long nonces = nonceCount();
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals(first + "\n" + second, recent.text);
        assertEquals("Synthetic title", recent.title);
        assertEquals("saved", recent.status);
        assertEquals(640L, recent.bytes);
        assertEquals(20L, recent.durationMs);
        assertFalse(recent.truncated);
        assertTrue(Arrays.equals(manifest, manifest(id)));
        assertTrue(Arrays.equals(text0, envelope(id, "text", 0)));
        assertTrue(Arrays.equals(text1, envelope(id, "text", 1)));
        assertTrue(Arrays.equals(audio, envelope(id, "audio", 0)));
        assertEquals(nonces, nonceCount());
        assertFalse(Arrays.equals(first.getBytes(StandardCharsets.UTF_8), text0));
        assertEquals(recent.text, store.find(id).text);
    }

    public void testLastFourChunksOnlyAndFullFindSearchExportRemainIntact() throws Exception {
        String id = store.create().id;
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String text = "segment " + i + "\nline " + i;
            store.appendText(id, text);
            if (i != 0) full.append('\n');
            full.append(text);
        }
        store.finish(id, "saved");
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals("segment 2\nline 2\nsegment 3\nline 3\nsegment 4\nline 4\nsegment 5\nline 5",
                recent.text);
        assertTrue(recent.truncated);
        Recordings.Session complete = store.find(id);
        assertEquals(full.toString(), complete.text);
        assertFalse(complete.truncated);
        assertEquals(id, store.list("segment 0").get(0).id);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.exportText(id, output);
        assertEquals(full.toString(), output.toString("UTF-8"));
    }

    public void testCharacterCapAcrossChunksPreservesNewestMultilineTail() throws Exception {
        String id = store.create().id;
        String first = repeat('a', 400);
        String second = repeat('b', 400) + "\nnewest";
        store.appendText(id, first);
        store.appendText(id, second);
        store.finish(id, "saved");
        String full = first + "\n" + second;
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals(full.substring(full.length() - 600), recent.text);
        assertEquals(600, recent.text.length());
        assertTrue(recent.truncated);
        assertEquals(full, store.find(id).text);
    }

    public void testExactCapsDoNotClaimTruncation() throws Exception {
        String id = saved(repeat('x', 600), "saved");
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals(id, recent.id);
        assertEquals(600, recent.text.length());
        assertFalse(recent.truncated);
        String four = store.create().id;
        for (int i = 0; i < 4; i++) store.appendText(four, "line " + i);
        store.finish(four, "saved");
        recent = store.recent(1).get(0);
        assertEquals("line 0\nline 1\nline 2\nline 3", recent.text);
        assertFalse(recent.truncated);
    }

    public void testClippedSupplementaryCharacterIsNotSplit() throws Exception {
        String text = "prefix🧪" + repeat('z', 599);
        String id = saved(text, "saved");
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals(repeat('z', 599), recent.text);
        assertTrue(recent.truncated);
        assertEquals(text, store.find(id).text);
    }

    public void testPreviewDoesNotReadAudioOrOmittedTextChunks() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[640], 640);
        for (int i = 0; i < 5; i++) store.appendText(id, "line " + i);
        store.finish(id, "saved");
        replaceEnvelope(id, "audio", 0, new byte[8]);
        replaceEnvelope(id, "text", 0, new byte[8]);
        Recordings.Session recent = store.recent(1).get(0);
        assertEquals("line 1\nline 2\nline 3\nline 4", recent.text);
        assertEquals(640L, recent.bytes);
        assertTrue(recent.truncated);
        expectCorrupt(() -> store.find(id));
        expectCorrupt(() -> store.exportWav(id, new ByteArrayOutputStream()));
    }

    public void testSelectedTextTamperIsObservableAndOriginalIsKept() throws Exception {
        String id = saved("Synthetic authenticated preview", "saved");
        byte[] damaged = envelope(id, "text", 0);
        damaged[damaged.length - 1] ^= 1;
        replaceEnvelope(id, "text", 0, damaged);
        expectCorrupt(() -> store.recent(1));
        assertTrue(Arrays.equals(damaged, envelope(id, "text", 0)));
    }

    public void testPreviewRetainsCrossSessionAadBinding() throws Exception {
        String first = saved("first", "saved");
        String second = saved("other", "saved");
        replaceEnvelope(second, "text", 0, envelope(first, "text", 0));
        expectCorrupt(() -> store.recent(1));
        assertEquals("first", store.find(first).text);
    }

    public void testSelectedManifestCorruptionAndMissingTailAreObservable() throws Exception {
        String id = saved("tail", "saved");
        byte[] original = manifest(id);
        replaceManifest(id, new byte[8]);
        // Non-positive limits perform no metadata read even in a damaged store.
        assertTrue(store.recent(0).isEmpty());
        expectCorrupt(() -> store.recent(1));
        replaceManifest(id, original);
        try (SQLiteDatabase database = raw()) {
            assertEquals(1, database.delete("chunks", "session_id=? AND kind='text'",
                    new String[]{id}));
        }
        expectCorrupt(() -> store.recent(1));
        assertTrue(Arrays.equals(original, manifest(id)));
    }

    private String saved(String text, String status) throws Exception {
        String id = store.create().id;
        store.appendText(id, text);
        store.finish(id, status);
        return id;
    }

    private SQLiteDatabase raw() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(databaseName).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }

    private byte[] manifest(String id) {
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT envelope FROM sessions WHERE id=?", new String[]{id})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getBlob(0);
        }
    }

    private void replaceManifest(String id, byte[] envelope) {
        try (SQLiteDatabase database = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, database.update("sessions", values, "id=?", new String[]{id}));
        }
    }

    private byte[] envelope(String id, String kind, long sequence) {
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT envelope FROM chunks WHERE session_id=? AND kind=? AND sequence=?",
                new String[]{id, kind, Long.toString(sequence)})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getBlob(0);
        }
    }

    private void replaceEnvelope(String id, String kind, long sequence, byte[] envelope) {
        try (SQLiteDatabase database = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, database.update("chunks", values,
                    "session_id=? AND kind=? AND sequence=?",
                    new String[]{id, kind, Long.toString(sequence)}));
        }
    }

    private long nonceCount() {
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) FROM nonces", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private interface Throwing { void run() throws Exception; }

    private static void expectCorrupt(Throwing action) throws Exception {
        try {
            action.run();
            fail("Selected corruption must reach the caller");
        } catch (Recordings.CorruptRecordingException expected) {
            assertTrue(expected.getMessage().contains("integrity check failed"));
            assertTrue(expected.getMessage().contains("nothing was deleted"));
        }
    }
}
