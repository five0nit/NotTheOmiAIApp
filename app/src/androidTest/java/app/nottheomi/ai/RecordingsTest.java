package app.nottheomi.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Device/emulator tests: actual AndroidKeyStore AES-GCM and Android SQLite, no mocks.
 * Each test uses a fresh synthetic-only database and alias. No production data is touched.
 */
@SuppressWarnings("deprecation")
public final class RecordingsTest extends AndroidTestCase {
    private Recordings store;
    private String databaseName;
    private String alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        databaseName = "recordings-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation." + token;
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

    public void testRoundTripAcrossReopenUsesRealKeystore() throws Exception {
        String id = store.create().id;
        byte[] audio = pcm(Recordings.MAX_CHUNK_BYTES * 2 + 138);
        store.appendAudio(id, audio, audio.length);
        store.appendText(id, "Synthetic café 東京 🧪");
        store.appendText(id, "Second synthetic sentence.");
        store.rename(id, "Synthetic private title");
        store.finish(id, "completed");
        reopen();
        Recordings.Session session = store.find(id);
        assertEquals("Synthetic private title", session.title);
        assertEquals("Synthetic café 東京 🧪\nSecond synthetic sentence.", session.text);
        assertEquals("completed", session.status);
        assertEquals(audio.length, session.bytes);
        assertEquals(audio.length * 1000L / 32000L, session.durationMs);
        assertEquals(audio.length, store.totalBytes());
        assertTrue(session.createdAt > 0);
        assertTrue(Arrays.equals(audio, readAudio(id)));
        assertEquals(1, store.list("").size());
        try (SQLiteDatabase database = raw()) {
            assertEquals(3L, scalar(database,
                    "SELECT COUNT(*) FROM chunks WHERE kind='audio'"));
        }
    }

    public void testRecoverySegmentsExportWithoutJoiningMissingAudio() throws Exception {
        byte[] before = pcm(640), after = new byte[320];
        Arrays.fill(after, (byte) 37);
        String first = store.create().id;
        store.appendAudio(first, before, before.length);
        store.appendText(first, "[Omi audio gap — missing audio not reconstructed]");
        store.finish(first, "saved");
        String second = store.create().id;
        assertFalse(first.equals(second));
        store.appendText(second, "[Omi audio resumed after a gap — separate recording segment]");
        store.appendAudio(second, after, after.length);
        store.finish(second, "saved");
        reopen();
        assertEquals(2, store.list("").size());
        assertTrue(Arrays.equals(before, readAudio(first)));
        assertTrue(Arrays.equals(after, readAudio(second)));
        assertEquals(before.length * 1000L / 32000L, store.find(first).durationMs);
        assertEquals(after.length * 1000L / 32000L, store.find(second).durationMs);
        assertTrue(store.find(first).text.contains("missing audio not reconstructed"));
        assertTrue(store.find(second).text.contains("separate recording segment"));
        ByteArrayOutputStream firstWav = new ByteArrayOutputStream();
        ByteArrayOutputStream secondWav = new ByteArrayOutputStream();
        store.exportWav(first, firstWav);
        store.exportWav(second, secondWav);
        assertEquals(44 + before.length, firstWav.size());
        assertEquals(44 + after.length, secondWav.size());
        assertTrue(Arrays.equals(before, Arrays.copyOfRange(firstWav.toByteArray(), 44, firstWav.size())));
        assertTrue(Arrays.equals(after, Arrays.copyOfRange(secondWav.toByteArray(), 44, secondWav.size())));
    }

    public void testNoPlaintextInSqliteOrJournal() throws Exception {
        String id = store.create().id;
        String secret = "SYNTHETIC-PRIVATE-TEXT-MARKER-84fe7345-東京";
        String title = "SYNTHETIC-PRIVATE-TITLE-MARKER-315c606b";
        byte[] marker = "SYNTHETIC_PCM_BYTES_NOT_ON_DISK_82".getBytes(StandardCharsets.UTF_8);
        byte[] audio = new byte[2048];
        for (int i = 0; i < audio.length; i++) audio[i] = marker[i % marker.length];
        store.appendText(id, secret);
        store.rename(id, title);
        store.appendAudio(id, audio, audio.length);
        store.finish(id, "completed");
        File path = getContext().getDatabasePath(databaseName);
        for (String suffix : new String[]{"", "-wal", "-journal", "-shm"}) {
            File file = new File(path.getPath() + suffix);
            if (!file.exists()) continue;
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                bytes = output.toByteArray();
            }
            assertEquals(-1, indexOf(bytes, secret.getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, title.getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, marker));
        }
    }

    public void testNoncesUniqueAndNeverReclaimedOnDeletion() throws Exception {
        String id = store.create().id;
        for (int i = 0; i < 6; i++) {
            store.appendAudio(id, pcm(512), 512);
            store.appendText(id, "Same synthetic text");
        }
        store.rename(id, "Changed");
        store.finish(id, "completed");
        Set<String> nonces = new HashSet<>();
        long nonceCount;
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT envelope FROM sessions UNION ALL SELECT envelope FROM chunks", null)) {
            while (cursor.moveToNext()) {
                byte[] envelope = cursor.getBlob(0);
                String nonce = Arrays.toString(Arrays.copyOfRange(envelope, 0, 12));
                assertTrue("Nonce reused between encrypted records", nonces.add(nonce));
            }
            nonceCount = scalar(database, "SELECT COUNT(*) FROM nonces");
            assertTrue("Rewritten manifests also reserve fresh nonces", nonceCount > nonces.size());
        }
        store.delete(id);
        try (SQLiteDatabase database = raw()) {
            assertEquals(nonceCount, scalar(database, "SELECT COUNT(*) FROM nonces"));
            assertEquals(0L, scalar(database, "SELECT COUNT(*) FROM chunks"));
        }
    }

    public void testCiphertextTamperFailsWithoutDeletion() throws Exception {
        String id = completedAudio();
        byte[] bytes = envelope(id, "audio", 0);
        bytes[bytes.length - 1] ^= 1;
        replaceEnvelope(id, "audio", 0, bytes);
        expectCorrupt(() -> readAudio(id));
        assertEquals(1, store.list("").size());
        assertEquals(640L, store.totalBytes());
        try (SQLiteDatabase database = raw()) {
            assertEquals(1L, scalar(database, "SELECT COUNT(*) FROM chunks"));
        }
    }

    public void testSequenceSwapFailsAuthentication() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.appendAudio(id, pcm(640), 640);
        store.finish(id, "completed");
        byte[] first = envelope(id, "audio", 0);
        byte[] second = envelope(id, "audio", 1);
        replaceEnvelope(id, "audio", 0, second);
        replaceEnvelope(id, "audio", 1, first);
        expectCorrupt(() -> readAudio(id));
    }

    public void testCrossSessionAudioSubstitutionFailsAuthentication() throws Exception {
        String first = completedAudio();
        String second = completedAudio();
        replaceEnvelope(second, "audio", 0, envelope(first, "audio", 0));
        assertEquals(640, readAudio(first).length);
        expectCorrupt(() -> readAudio(second));
    }

    public void testTranscriptOrderBinding() throws Exception {
        String id = store.create().id;
        store.appendText(id, "one");
        store.appendText(id, "two");
        store.finish(id, "completed");
        byte[] first = envelope(id, "text", 0);
        byte[] second = envelope(id, "text", 1);
        replaceEnvelope(id, "text", 0, second);
        replaceEnvelope(id, "text", 1, first);
        expectCorrupt(() -> store.find(id));
        expectCorrupt(() -> store.exportText(id, new ByteArrayOutputStream()));
    }

    public void testCrossSessionTranscriptBinding() throws Exception {
        String first = store.create().id;
        store.appendText(first, "one");
        store.finish(first, "completed");
        String second = store.create().id;
        store.appendText(second, "two");
        store.finish(second, "completed");
        replaceEnvelope(second, "text", 0, envelope(first, "text", 0));
        expectCorrupt(() -> store.find(second));
    }

    public void testAudioTranscriptDomainBinding() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, new byte[]{1, 2, 3, 4}, 4);
        store.appendText(id, "abcd");
        store.finish(id, "completed");
        replaceEnvelope(id, "text", 0, envelope(id, "audio", 0));
        expectCorrupt(() -> store.find(id));
    }

    public void testManifestSessionBinding() throws Exception {
        String first = completedAudio();
        String second = completedAudio();
        try (SQLiteDatabase database = raw()) {
            byte[] envelope;
            try (Cursor cursor = database.rawQuery("SELECT envelope FROM sessions WHERE id=?",
                    new String[]{first})) {
                assertTrue(cursor.moveToFirst());
                envelope = cursor.getBlob(0);
            }
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, database.update("sessions", values, "id=?", new String[]{second}));
        }
        expectCorrupt(() -> store.find(second));
    }

    public void testMissingFinalChunkDetectedByAuthenticatedManifest() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.appendAudio(id, pcm(640), 640);
        store.finish(id, "completed");
        try (SQLiteDatabase database = raw()) {
            assertEquals(1, database.delete("chunks", "session_id=? AND sequence=1",
                    new String[]{id}));
        }
        expectCorrupt(() -> store.find(id));
        expectCorrupt(() -> store.exportWav(id, new ByteArrayOutputStream()));
        store.delete(id); // Only explicit user deletion may remove damaged inactive data.
        assertNull(store.find(id));
    }

    public void testMissingTranscriptTailDetected() throws Exception {
        String id = store.create().id;
        store.appendText(id, "one");
        store.appendText(id, "two");
        store.finish(id, "completed");
        try (SQLiteDatabase database = raw()) {
            database.delete("chunks", "session_id=? AND kind='text' AND sequence=1",
                    new String[]{id});
        }
        expectCorrupt(() -> store.exportText(id, new ByteArrayOutputStream()));
    }

    public void testMalformedEnvelopeIsMeaningfulCorruption() throws Exception {
        String id = completedAudio();
        replaceEnvelope(id, "audio", 0, new byte[8]);
        expectCorrupt(() -> readAudio(id));
    }

    public void testMissingKeyRefusesToResetExistingStore() throws Exception {
        String id = completedAudio();
        store.closeForTest();
        store = null;
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        keys.deleteEntry(alias);
        try {
            store = new Recordings(getContext(), databaseName, alias);
            fail("Store must not silently generate a replacement key");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("key is unavailable"));
        }
        assertFalse(keys.containsAlias(alias));
        try (SQLiteDatabase database = raw()) {
            assertEquals(1L, scalar(database, "SELECT COUNT(*) FROM sessions"));
            assertEquals(1L, scalar(database, "SELECT COUNT(*) FROM chunks"));
            try (Cursor cursor = database.rawQuery("SELECT id FROM sessions", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(id, cursor.getString(0));
            }
        }
    }

    public void testInterruptedRecoveryPreservesCommittedAudioAndText() throws Exception {
        String complete = completedAudio();
        String interrupted = store.create().id;
        byte[] prefix = pcm(1400);
        store.appendAudio(interrupted, prefix, prefix.length);
        store.appendText(interrupted, "Committed synthetic prefix");
        reopen(); // Simulates store/process reinitialisation, not a physical power-loss claim.
        assertEquals("recording", store.find(interrupted).status);
        store.recoverInterrupted();
        assertEquals("interrupted", store.find(interrupted).status);
        assertEquals("Committed synthetic prefix", store.find(interrupted).text);
        assertTrue(Arrays.equals(prefix, readAudio(interrupted)));
        assertEquals("completed", store.find(complete).status);
        assertEquals(2040L, store.totalBytes());
        store.recoverInterrupted();
        assertEquals(2, store.list("").size());
        String next = store.create().id;
        store.finish(next, "completed");
    }

    public void testFailedChunkTransactionRollsBackBothManifestAndAudio() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        try (SQLiteDatabase database = raw()) {
            database.execSQL("CREATE TRIGGER reject_manifest BEFORE UPDATE ON sessions "
                    + "BEGIN SELECT RAISE(ABORT,'synthetic injected failure'); END");
        }
        try {
            store.appendAudio(id, pcm(1000), 1000);
            fail("Injected database failure did not reach caller");
        } catch (android.database.SQLException expected) {
            // Keep the real engine failure; do not replace production persistence with a fake.
        } finally {
            try (SQLiteDatabase database = raw()) {
                database.execSQL("DROP TRIGGER reject_manifest");
            }
        }
        reopen();
        store.recoverInterrupted();
        assertEquals(640L, store.find(id).bytes);
        assertEquals(640, readAudio(id).length);
        try (SQLiteDatabase database = raw()) {
            assertEquals(1L, scalar(database, "SELECT COUNT(*) FROM chunks"));
        }
    }

    public void testWavHeaderAndPayloadAreExact() throws Exception {
        String id = store.create().id;
        byte[] audio = pcm(Recordings.MAX_CHUNK_BYTES + 642);
        store.appendAudio(id, audio, audio.length);
        store.finish(id, "completed");
        TrackingOutput output = new TrackingOutput();
        store.exportWav(id, output);
        assertFalse("Caller owns output lifetime", output.closed);
        byte[] wav = output.toByteArray();
        assertEquals(audio.length + 44, wav.length);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", ascii(wav, 0, 4));
        assertEquals(36L + audio.length, Integer.toUnsignedLong(header.getInt(4)));
        assertEquals("WAVE", ascii(wav, 8, 4));
        assertEquals("fmt ", ascii(wav, 12, 4));
        assertEquals(16, header.getInt(16));
        assertEquals(1, header.getShort(20));
        assertEquals(1, header.getShort(22));
        assertEquals(16000, header.getInt(24));
        assertEquals(32000, header.getInt(28));
        assertEquals(2, header.getShort(32));
        assertEquals(16, header.getShort(34));
        assertEquals("data", ascii(wav, 36, 4));
        assertEquals(audio.length, header.getInt(40));
        assertTrue(Arrays.equals(audio, Arrays.copyOfRange(wav, 44, wav.length)));
        assertTrue("WAV must be streamed in bounded writes",
                output.maximumWrite <= Recordings.MAX_CHUNK_BYTES);
    }

    public void testTextExportIsUtf8AndDoesNotCloseCallerOutput() throws Exception {
        String id = store.create().id;
        store.appendText(id, "Café 東京 🧪");
        store.appendText(id, "Σ Straße");
        TrackingOutput output = new TrackingOutput();
        store.exportText(id, output);
        assertEquals("Café 東京 🧪\nΣ Straße", output.toString("UTF-8"));
        assertFalse(output.closed);
        store.finish(id, "completed");
    }

    public void testEmptyExportsAreValid() throws Exception {
        String id = store.create().id;
        store.finish(id, "completed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.exportText(id, output);
        assertEquals(0, output.size());
        store.exportWav(id, output);
        assertEquals(44, output.size());
        ByteBuffer header = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36, header.getInt(4));
        assertEquals(0, header.getInt(40));
    }

    public void testUnicodeSearchAndRename() throws Exception {
        String id = store.create().id;
        store.rename(id, "CAFÉ 東京");
        store.appendText(id, "Straße Ελληνικά σς 議事録");
        store.finish(id, "completed");
        assertEquals(id, store.list("cafe\u0301").get(0).id);
        assertEquals(id, store.list("東京").get(0).id);
        assertEquals(id, store.list("STRASSE").get(0).id);
        assertEquals(id, store.list("ΣΣ").get(0).id);
        assertEquals(id, store.list("議事").get(0).id);
        assertEquals(0, store.list("absent synthetic query").size());
        store.rename(id, "Renamed");
        assertEquals(0, store.list("café").size());
        assertEquals(id, store.list("RENAMED").get(0).id);
        reopen();
        assertEquals("Renamed", store.find(id).title);
    }

    public void testActiveDeletionAndConcurrentCreationAreRefused() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        expectIllegalState(() -> store.delete(id));
        expectIllegalState(() -> store.create());
        assertEquals("recording", store.find(id).status);
        assertEquals(640L, store.totalBytes());
        store.finish(id, "completed");
        store.delete(id);
        assertNull(store.find(id));
        assertEquals(0L, store.totalBytes());
    }

    public void testCompletedRecordingsRetainedUntilExplicitPerSessionDelete() throws Exception {
        String first = completedAudio();
        String second = completedAudio();
        reopen();
        store.recoverInterrupted();
        assertEquals(2, store.list(null).size());
        store.delete(first);
        assertNull(store.find(first));
        assertNotNull(store.find(second));
        assertEquals(640L, store.totalBytes());
        assertEquals(640, readAudio(second).length);
    }

    public void testFinishIsIdempotentAndAppendAfterStopFails() throws Exception {
        String id = store.create().id;
        store.finish(id, "interrupted");
        store.finish(id, "completed");
        assertEquals("interrupted", store.find(id).status);
        expectIllegalState(() -> store.appendAudio(id, pcm(640), 640));
        expectIllegalState(() -> store.appendText(id, "synthetic"));
    }

    public void testSavedStatusUsedByCaptureIntegrationIsSupported() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.finish(id, "saved");
        reopen();
        store.recoverInterrupted();
        assertEquals("saved", store.find(id).status);
        assertEquals(640, readAudio(id).length);
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testInvalidPcmRejectedWithoutWrites() throws Exception {
        String id = store.create().id;
        expectInvalid(() -> store.appendAudio(id, new byte[5], 5));
        expectInvalid(() -> store.appendAudio(id, new byte[4], 6));
        expectInvalid(() -> store.appendAudio(id, new byte[4], -2));
        expectInvalid(() -> store.appendAudio(id, null, 0));
        assertEquals(0L, store.find(id).bytes);
        store.appendAudio(id, new byte[]{1, 2, 99, 99}, 2);
        assertTrue(Arrays.equals(new byte[]{1, 2}, readAudio(id)));
        store.finish(id, "completed");
    }

    public void testQuotaPredicateAtExactBoundariesAndOverflow() {
        long reserve = Recordings.FREE_SPACE_RESERVE_BYTES + 1024L * 1024L;
        assertEquals(2147483648L, Recordings.MAX_AUDIO_BYTES);
        assertEquals(134217728L, Recordings.FREE_SPACE_RESERVE_BYTES);
        assertTrue(Recordings.capacityAllows(0, 32000, reserve + 32000));
        assertFalse(Recordings.capacityAllows(0, 32000, reserve + 31999));
        assertTrue(Recordings.capacityAllows(Recordings.MAX_AUDIO_BYTES - 2, 2, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(Recordings.MAX_AUDIO_BYTES, 2, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(-1, 2, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(0, -1, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(Long.MAX_VALUE, 2, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(0, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(Recordings.capacityAllows(0, 0, reserve - 1));
        assertTrue(Recordings.capacityAllows(0, 0, reserve));
    }

    public void testCallbackDoesNotHoldStoreMonitorAndDeletionLeaseWorks() throws Exception {
        String id = completedAudio();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> streaming = pool.submit(() -> {
            try {
                store.forEachPcm(id, bytes -> {
                    callbackEntered.countDown();
                    if (!allowCallbackReturn.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Synthetic callback timed out");
                    }
                });
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            Future<?> controls = pool.submit(() -> {
                try {
                    assertEquals(640L, store.totalBytes());
                    store.rename(id, "Renamed during callback");
                    expectIllegalState(() -> store.delete(id));
                    String next = store.create().id;
                    store.appendAudio(next, pcm(640), 640);
                    store.finish(next, "completed");
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            controls.get(5, TimeUnit.SECONDS);
        } finally {
            allowCallbackReturn.countDown();
            try { streaming.get(5, TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
        }
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testCallbackFailureReleasesDeletionLease() throws Exception {
        String id = completedAudio();
        try {
            store.forEachPcm(id, bytes -> { throw new IOException("Synthetic consumer failure"); });
            fail("Callback exception must propagate");
        } catch (IOException expected) {
            assertEquals("Synthetic consumer failure", expected.getMessage());
        }
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testExportFailureReleasesLeaseAndKeepsOriginal() throws Exception {
        String id = completedAudio();
        try {
            store.exportWav(id, new OutputStream() {
                @Override public void write(int value) throws IOException {
                    throw new IOException("Synthetic destination failure");
                }
            });
            fail("Destination error must propagate");
        } catch (IOException expected) {
            assertEquals("Synthetic destination failure", expected.getMessage());
        }
        assertEquals(640, readAudio(id).length);
        store.delete(id);
    }

    public void testStreamingSnapshotExcludesLaterAppends() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        AtomicInteger delivered = new AtomicInteger();
        store.forEachPcm(id, bytes -> {
            delivered.addAndGet(bytes.length);
            store.appendAudio(id, pcm(640), 640);
        });
        assertEquals(640, delivered.get());
        assertEquals(1280L, store.find(id).bytes);
        store.finish(id, "completed");
    }

    private String completedAudio() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.finish(id, "completed");
        return id;
    }

    private void reopen() throws Exception {
        store.closeForTest();
        store = new Recordings(getContext(), databaseName, alias);
    }

    private byte[] readAudio(String id) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.forEachPcm(id, bytes::write);
        return bytes.toByteArray();
    }

    private SQLiteDatabase raw() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(databaseName).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
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

    private static long scalar(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private interface Throwing { void run() throws Exception; }

    private static void expectCorrupt(Throwing action) throws Exception {
        try {
            action.run();
            fail("Tampering must not produce accepted plaintext");
        } catch (Recordings.CorruptRecordingException expected) {
            assertTrue(expected.getMessage().contains("integrity check failed"));
            assertTrue(expected.getMessage().contains("nothing was deleted"));
        }
    }

    private static void expectIllegalState(Throwing action) throws Exception {
        try { action.run(); fail("Expected invalid-state refusal"); }
        catch (IllegalStateException expected) { assertNotNull(expected.getMessage()); }
    }

    private static void expectInvalid(Throwing action) throws Exception {
        try { action.run(); fail("Expected invalid-input refusal"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    private static byte[] pcm(int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) result[i] = (byte) ((i * 31 + 17) & 0xff);
        return result;
    }

    private static String ascii(byte[] bytes, int start, int length) {
        return new String(bytes, start, length, StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        int maximumWrite;
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            maximumWrite = Math.max(maximumWrite, length);
            super.write(bytes, offset, length);
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
