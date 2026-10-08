package app.nottheomi.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Real Android SQLite/AndroidKeyStore preservation tests; no production store or model.
 * Fresh synthetic-only database/alias per test. Reopen tests cover durable checkpoints,
 * not physical power-loss guarantees. The independent v1 fixture omits all hybrid fields.
 */
@SuppressWarnings("deprecation")
public final class HybridRecordingsTest extends AndroidTestCase {
    private static final String DRAFT = "Synthetic original café 東京 🧪";
    private Recordings store;
    private String databaseName, alias;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String token = UUID.randomUUID().toString();
        databaseName = "hybrid-recordings-test-" + token + ".db";
        alias = "app.nottheomi.ai.instrumentation.hybrid." + token;
        store = new Recordings(getContext(), databaseName, alias);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (store != null) store.closeForTest();
            if (databaseName != null) getContext().deleteDatabase(databaseName);
            if (alias != null) keys().deleteEntry(alias);
        } finally { super.tearDown(); }
    }

    public void testFinishQueuesOnlyStoppedNonemptyAudioAndIsIdempotent() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.appendText(id, DRAFT);
        assertNull(store.refinement(id));
        assertTrue(store.pendingRefinements().isEmpty());
        expect(IllegalStateException.class, () -> store.retryRefinement(id));
        store.finish(id, "saved");
        byte[] manifest = sessionEnvelope(id), job = refinementEnvelope(id);
        long nonces = count("nonces");
        store.finish(id, "interrupted");
        assertBytes(manifest, sessionEnvelope(id));
        assertBytes(job, refinementEnvelope(id));
        assertEquals(nonces, count("nonces"));
        assertJob(id, "pending", 0, 640);
        assertEquals(id, store.pendingRefinements().get(0).id);
        assertEquals("saved", store.find(id).status);
        String empty = store.create().id;
        store.appendText(empty, "Text-only draft");
        store.finish(empty, "saved");
        assertNull(store.refinement(empty));
        assertEquals("none", store.find(empty).transcriptState);
        expect(IllegalStateException.class, () -> store.retryRefinement(empty));
        assertEquals(1, store.pendingRefinements().size());
    }

    public void testFinishQueueAndManifestRollbackTogetherOnSqlFailure() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        store.appendText(id, DRAFT);
        List<String> originals = originalRows();
        long nonces = count("nonces");
        sql("CREATE TRIGGER reject_finish BEFORE UPDATE ON sessions "
                + "BEGIN SELECT RAISE(ABORT,'synthetic finish failure'); END");
        try { expect(SQLException.class, () -> store.finish(id, "saved")); }
        finally { sql("DROP TRIGGER reject_finish"); }
        assertEquals(originals, originalRows());
        assertEquals(nonces, count("nonces"));
        assertEquals(0L, count("refinements"));
        assertEquals("recording", store.find(id).status);
        assertNull(store.refinement(id));
        store.finish(id, "saved");
        reopen();
        assertJob(id, "pending", 0, 640);
        assertEquals(DRAFT, store.find(id).text);
    }

    public void testRestartRecoveryQueuesNewInterruptedAudioExactlyOnce() throws Exception {
        String saved = saved(320, "Earlier saved draft");
        String id = store.create().id;
        store.appendAudio(id, pcm(642), 642);
        store.appendText(id, DRAFT);
        reopen();
        assertNull(store.refinement(id));
        store.recoverInterrupted();
        assertEquals("interrupted", store.find(id).status);
        assertJob(id, "pending", 0, 642);
        assertJob(saved, "pending", 0, 320);
        byte[] manifest = sessionEnvelope(id), job = refinementEnvelope(id);
        store.recoverInterrupted();
        assertBytes(manifest, sessionEnvelope(id));
        assertBytes(job, refinementEnvelope(id));
        assertBytes(pcm(642), readAudio(id));
        assertEquals(2, store.pendingRefinements().size());
        String empty = store.create().id;
        reopen();
        store.recoverInterrupted();
        assertEquals("interrupted", store.find(empty).status);
        assertNull(store.refinement(empty));
    }

    public void testCheckpointFailureRetryAndRestartNeverPublishPartialText() throws Exception {
        String id = saved(640, DRAFT);
        List<String> originals = originalRows();
        store.commitRefinementBatch(id, 0, 320, "Refined first half");
        assertEquals(DRAFT, store.find(id).text);
        assertEquals(DRAFT, exportedText(id));
        assertEquals(DRAFT, store.recent(1).get(0).text);
        store.failRefinement(id);
        reopen();
        assertJob(id, "failed", 320, 640);
        assertEquals("failed", store.find(id).transcriptState);
        assertTrue(store.pendingRefinements().isEmpty());
        expect(IllegalStateException.class,
                () -> store.commitRefinementBatch(id, 320, 640, "Must not write while failed"));
        byte[] first = refinedEnvelope(id, 0);
        store.retryRefinement(id);
        assertJob(id, "pending", 320, 640);
        assertBytes(first, refinedEnvelope(id, 0));
        reopen();
        store.commitRefinementBatch(id, 320, 640, "Refined second half");
        assertEquals(DRAFT, store.find(id).text);
        store.completeRefinement(id);
        reopen();
        assertJob(id, "complete", 640, 640);
        assertEquals("Refined first half\nRefined second half", store.find(id).text);
        assertEquals(DRAFT, store.find(id).liveText);
        assertEquals(store.find(id).text, exportedText(id));
        assertEquals(originals, originalRows());
        byte[] complete = refinementEnvelope(id);
        long nonces = count("nonces");
        store.completeRefinement(id);
        store.retryRefinement(id);
        store.failRefinement(id);
        assertBytes(complete, refinementEnvelope(id));
        assertEquals(nonces, count("nonces"));
    }

    public void testRefinementPreservesOriginalCiphertextAnnotationsAudioAndWav() throws Exception {
        String bookmark = "[Omi button bookmark]";
        String gap = "[Omi audio gap — audio missing; recovery attempted; earlier audio retained]";
        String incomplete = "[Offline transcript incomplete — encrypted audio retained]";
        String resume = "[Omi audio resumed after a gap — separate recording segment; missing audio not reconstructed]";
        String draft = DRAFT + "\n" + bookmark + "\n" + gap + "\n" + incomplete + "\n" + bookmark;
        String id = saved(Recordings.MAX_CHUNK_BYTES + 642, draft);
        store.rename(id, "Private original title");
        String next = saved(320, resume);
        List<String> originals = originalRows();
        Recordings.Session before = store.find(id);
        byte[] wav = exportedWav(id), nextWav = exportedWav(next);
        store.commitRefinementBatch(id, 0, before.bytes, "Whisper corrected words");
        store.completeRefinement(id);
        reopen();
        Recordings.Session after = store.find(id);
        assertEquals(draft, after.liveText);
        assertEquals("Whisper corrected words\n\n[Recording annotations]\n" + bookmark + "\n"
                + gap + "\n" + incomplete + "\n" + bookmark, after.text);
        assertEquals(after.text, exportedText(id));
        assertEquals(before.status, after.status);
        assertEquals(before.title, after.title);
        assertEquals(before.createdAt, after.createdAt);
        assertEquals(before.bytes, after.bytes);
        assertEquals(before.durationMs, after.durationMs);
        assertEquals(originals, originalRows());
        assertBytes(wav, exportedWav(id));
        assertBytes(nextWav, exportedWav(next));
        assertBytes(pcm((int) before.bytes), readAudio(id));
        assertEquals(resume, store.find(next).liveText);
        assertEquals(next, store.recent(2).get(0).id);
        assertEquals(2, store.list(null).size());
        assertEquals(id, store.list("corrected words").get(0).id);
    }

    public void testRefinementPayloadsAreEncryptedAndNoncesRemainUnique() throws Exception {
        String secret = "SYNTHETIC-WHISPER-SECRET-c3cc952e-東京";
        String id = saved(640, DRAFT);
        store.commitRefinementBatch(id, 0, 320, secret);
        store.commitRefinementBatch(id, 320, 640, secret);
        store.completeRefinement(id);
        assertEquals(secret + "\n" + secret, store.find(id).text);
        Set<String> seen = new HashSet<>();
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT envelope FROM sessions UNION ALL SELECT envelope FROM chunks "
                        + "UNION ALL SELECT envelope FROM refinements "
                        + "UNION ALL SELECT envelope FROM refinement_chunks", null)) {
            while (cursor.moveToNext()) {
                byte[] envelope = cursor.getBlob(0);
                assertTrue(envelope.length >= 28);
                assertTrue("Nonce reused between original/refined domains",
                        seen.add(Arrays.toString(Arrays.copyOfRange(envelope, 0, 12))));
            }
        }
        assertTrue(count("nonces") > seen.size());
        File path = getContext().getDatabasePath(databaseName);
        for (String suffix : new String[]{"", "-wal", "-journal", "-shm"}) {
            File file = new File(path.getPath() + suffix);
            if (!file.exists()) continue;
            byte[] bytes = fileBytes(file);
            assertEquals(-1, indexOf(bytes, secret.getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, DRAFT.getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, "\"offsetBytes\"".getBytes(StandardCharsets.UTF_8)));
            assertEquals(-1, indexOf(bytes, "\"state\":\"complete\"".getBytes(StandardCharsets.UTF_8)));
        }
        reopen();
        assertJob(id, "complete", 640, 640);
    }

    public void testBatchTextCheckpointAndNonceReservationsRollbackAtomically() throws Exception {
        String id = saved(960, DRAFT);
        store.commitRefinementBatch(id, 0, 320, "First committed batch");
        byte[] job = refinementEnvelope(id), first = refinedEnvelope(id, 0);
        long nonces = count("nonces");
        sql("CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON refinements "
                + "BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END");
        try { expect(SQLException.class,
                () -> store.commitRefinementBatch(id, 320, 640, "Rolled-back second batch")); }
        finally { sql("DROP TRIGGER reject_checkpoint"); }
        reopen();
        assertJob(id, "pending", 320, 960);
        assertBytes(job, refinementEnvelope(id));
        assertBytes(first, refinedEnvelope(id, 0));
        assertEquals(1L, count("refinement_chunks"));
        assertEquals(nonces, count("nonces"));
        store.commitRefinementBatch(id, 320, 960, "Second committed batch");
        store.completeRefinement(id);
        assertEquals("First committed batch\nSecond committed batch", store.find(id).text);
    }

    public void testFailedRetryAndCompletionUpdatesAreTransactional() throws Exception {
        String id = saved(640, DRAFT);
        store.commitRefinementBatch(id, 0, 640, "Complete candidate");
        byte[] pending = refinementEnvelope(id);
        sql("CREATE TRIGGER reject_state BEFORE UPDATE ON refinements "
                + "BEGIN SELECT RAISE(ABORT,'synthetic state failure'); END");
        try {
            expect(SQLException.class, () -> store.completeRefinement(id));
            expect(SQLException.class, () -> store.failRefinement(id));
        } finally { sql("DROP TRIGGER reject_state"); }
        assertBytes(pending, refinementEnvelope(id));
        assertEquals(DRAFT, store.find(id).text);
        store.failRefinement(id);
        byte[] failed = refinementEnvelope(id);
        long nonces = count("nonces");
        sql("CREATE TRIGGER reject_retry BEFORE UPDATE ON refinements "
                + "BEGIN SELECT RAISE(ABORT,'synthetic retry failure'); END");
        try { expect(SQLException.class, () -> store.retryRefinement(id)); }
        finally { sql("DROP TRIGGER reject_retry"); }
        reopen();
        assertBytes(failed, refinementEnvelope(id));
        assertEquals(nonces, count("nonces"));
        assertJob(id, "failed", 640, 640);
        store.retryRefinement(id);
        store.completeRefinement(id);
        assertEquals("Complete candidate", store.find(id).text);
    }

    public void testInvalidStaleAndTerminalCommitsCannotChangeCheckpoint() throws Exception {
        String id = saved(640, DRAFT);
        byte[] initial = refinementEnvelope(id);
        long nonces = count("nonces");
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, -2, 2, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 0, 0, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 0, 1, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 1, 2, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 0, 642, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 0, Long.MAX_VALUE - 1, "x"));
        expect(IllegalArgumentException.class, () -> store.commitRefinementBatch(id, 0, 320, null));
        expect(IllegalArgumentException.class,
                () -> store.commitRefinementBatch(id, 0, 320, repeat('x', 1024 * 1024 + 1)));
        expect(IllegalStateException.class, () -> store.commitRefinementBatch(id, 2, 320, "x"));
        assertBytes(initial, refinementEnvelope(id));
        assertEquals(nonces, count("nonces"));
        assertEquals(0L, count("refinement_chunks"));
        store.commitRefinementBatch(id, 0, 320, "First");
        byte[] advanced = refinementEnvelope(id);
        expect(IllegalStateException.class, () -> store.commitRefinementBatch(id, 0, 640, "Stale"));
        assertBytes(advanced, refinementEnvelope(id));
        assertEquals(1L, count("refinement_chunks"));
        store.commitRefinementBatch(id, 320, 640, "Second");
        store.completeRefinement(id);
        byte[] complete = refinementEnvelope(id);
        expect(IllegalStateException.class, () -> store.commitRefinementBatch(id, 640, 642, "Late"));
        assertBytes(complete, refinementEnvelope(id));
        assertEquals("First\nSecond", store.find(id).text);
    }

    public void testExactTextLimitAcceptedAndUtf8ByteLimitRejected() throws Exception {
        String id = saved(640, DRAFT);
        expect(IllegalArgumentException.class,
                () -> store.commitRefinementBatch(id, 0, 320, repeat('é', 524289)));
        String exact = repeat('x', 1024 * 1024);
        store.commitRefinementBatch(id, 0, 640, exact);
        store.completeRefinement(id);
        assertEquals(exact, store.find(id).text);
        assertEquals(DRAFT, store.find(id).liveText);
    }

    public void testCompletionRequiresExactCoverageAndKeepsAllBlankDraft() throws Exception {
        String id = saved(642, DRAFT);
        expect(IllegalStateException.class, () -> store.completeRefinement(id));
        store.commitRefinementBatch(id, 0, 640, "Almost complete");
        expect(IllegalStateException.class, () -> store.completeRefinement(id));
        assertEquals(DRAFT, store.find(id).text);
        // Silent final samples count as processed without inventing a text segment.
        store.commitRefinementBatch(id, 640, 642, " \n\t ");
        assertEquals(1L, count("refinement_chunks"));
        store.completeRefinement(id);
        assertJob(id, "complete", 642, 642);
        assertEquals("Almost complete", store.find(id).text);
        String blank = saved(640, DRAFT);
        store.commitRefinementBatch(blank, 0, 640, " \n\t ");
        store.completeRefinement(blank);
        assertJob(blank, "failed", 640, 640);
        assertEquals(DRAFT, store.find(blank).text);
        assertEquals(DRAFT, exportedText(blank));
        List<String> originals = originalRows();
        store.retryRefinement(blank);
        reopen();
        assertJob(blank, "pending", 0, 640);
        store.commitRefinementBatch(blank, 0, 640, "Speech recognised on explicit retry");
        store.completeRefinement(blank);
        assertEquals("Speech recognised on explicit retry", store.find(blank).text);
        assertEquals(originals, originalRows());
    }

    public void testMissingAudioTailPreventsCommitAndCompletion() throws Exception {
        String id = saved(Recordings.MAX_CHUNK_BYTES + 2, DRAFT);
        store.commitRefinementBatch(id, 0, Recordings.MAX_CHUNK_BYTES + 2, "Candidate");
        byte[] job = refinementEnvelope(id);
        try (SQLiteDatabase database = raw()) {
            assertEquals(1, database.delete("chunks", "session_id=? AND kind='audio' AND sequence=1",
                    new String[]{id}));
        }
        expect(Recordings.CorruptRecordingException.class, () -> store.completeRefinement(id));
        assertBytes(job, refinementEnvelope(id));
        expect(Recordings.CorruptRecordingException.class,
                () -> store.commitRefinementBatch(id, Recordings.MAX_CHUNK_BYTES + 2,
                        Recordings.MAX_CHUNK_BYTES + 4, "Invalid continuation"));
        assertEquals(1L, count("sessions"));
    }

    public void testCorruptRefinementManifestCannotHideOrRewriteOriginals() throws Exception {
        String id = saved(640, DRAFT);
        List<String> originals = originalRows();
        byte[] bad = refinementEnvelope(id);
        bad[bad.length - 1] ^= 1;
        replaceRefinement(id, bad);
        assertOriginalFallback(id, DRAFT);
        expect(Recordings.CorruptRecordingException.class, () -> store.refinement(id));
        expect(Recordings.CorruptRecordingException.class, () -> store.retryRefinement(id));
        expect(Recordings.CorruptRecordingException.class,
                () -> store.commitRefinementBatch(id, 0, 640, "Must not repair corruption"));
        assertTrue(store.pendingRefinements().isEmpty());
        assertEquals(originals, originalRows());
        assertBytes(bad, refinementEnvelope(id));
        store.rename(id, "Original still usable");
        assertEquals("Original still usable", store.find(id).title);
        store.delete(id);
        assertNull(store.find(id));
        assertEquals(0L, count("refinements"));
    }

    public void testCorruptCompletedTextFallsBackBeforeAnyExportOutput() throws Exception {
        String id = saved(640, DRAFT);
        store.commitRefinementBatch(id, 0, 320, "Valid prefix must not leak into fallback");
        store.commitRefinementBatch(id, 320, 640, "Damaged suffix");
        store.completeRefinement(id);
        List<String> originals = originalRows();
        byte[] bad = refinedEnvelope(id, 1);
        bad[bad.length - 1] ^= 1;
        replaceRefinedChunk(id, 1, bad);
        assertOriginalFallback(id, DRAFT);
        assertEquals(originals, originalRows());
        assertBytes(bad, refinedEnvelope(id, 1));
    }

    public void testMissingRefinementManifestAndTailKeepOriginalsAccessible() throws Exception {
        String id = saved(640, DRAFT);
        store.commitRefinementBatch(id, 0, 640, "Refined output");
        store.completeRefinement(id);
        byte[] manifest = refinementEnvelope(id);
        try (SQLiteDatabase database = raw()) {
            assertEquals(1, database.delete("refinements", "session_id=?", new String[]{id}));
        }
        assertOriginalFallback(id, DRAFT);
        expect(Recordings.CorruptRecordingException.class, () -> store.refinement(id));
        try (SQLiteDatabase database = raw()) {
            ContentValues values = new ContentValues();
            values.put("session_id", id);
            values.put("envelope", manifest);
            database.insertOrThrow("refinements", null, values);
            assertEquals(1, database.delete("refinement_chunks", "session_id=?", new String[]{id}));
        }
        assertOriginalFallback(id, DRAFT);
        expect(Recordings.CorruptRecordingException.class, () -> store.refinement(id));
    }

    public void testRefinementSessionSequenceAndOriginalDomainBindings() throws Exception {
        String first = saved(640, DRAFT), second = saved(640, "Other original");
        store.commitRefinementBatch(first, 0, 320, "First refined batch");
        store.commitRefinementBatch(first, 320, 640, "Second refined batch");
        byte[] firstChunk = refinedEnvelope(first, 0), secondChunk = refinedEnvelope(first, 1);
        replaceRefinedChunk(first, 0, secondChunk);
        replaceRefinedChunk(first, 1, firstChunk);
        expect(Recordings.CorruptRecordingException.class, () -> store.completeRefinement(first));
        replaceRefinedChunk(first, 0, firstChunk);
        replaceRefinedChunk(first, 1, secondChunk);
        store.commitRefinementBatch(second, 0, 640, "Other refined batch");
        byte[] other = refinedEnvelope(second, 0);
        replaceRefinedChunk(second, 0, firstChunk);
        expect(Recordings.CorruptRecordingException.class, () -> store.completeRefinement(second));
        replaceRefinedChunk(second, 0, originalChunk(second, "text", 0));
        expect(Recordings.CorruptRecordingException.class, () -> store.completeRefinement(second));
        replaceRefinedChunk(second, 0, other);
        byte[] secondManifest = refinementEnvelope(second);
        replaceRefinement(second, refinementEnvelope(first));
        expect(Recordings.CorruptRecordingException.class, () -> store.refinement(second));
        assertOriginalFallback(second, "Other original");
        replaceRefinement(second, secondManifest);
        store.completeRefinement(first);
        store.completeRefinement(second);
        assertEquals("First refined batch\nSecond refined batch", store.find(first).text);
        assertEquals("Other refined batch", store.find(second).text);
    }

    public void testCorruptOriginalIsNotDisguisedByDerivativeFallback() throws Exception {
        String id = saved(640, DRAFT);
        replaceRefinement(id, new byte[8]);
        try (SQLiteDatabase database = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", new byte[8]);
            assertEquals(1, database.update("chunks", values, "session_id=? AND kind='text'",
                    new String[]{id}));
        }
        expect(Recordings.CorruptRecordingException.class, () -> store.find(id));
        expect(Recordings.CorruptRecordingException.class, () -> store.recent(1));
        expect(Recordings.CorruptRecordingException.class, () -> exportedText(id));
        assertBytes(pcm(640), readAudio(id));
        assertEquals(1L, count("sessions"));
        store.delete(id); // Failed text export must release its reader lease too.
        assertNull(store.find(id));
    }

    public void testPendingQueueSkipsCorruptionAndBoundsOldestFirst() throws Exception {
        String damaged = saved(640, "Damaged derivative, intact original");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 14; i++) ids.add(saved(2, "Draft " + i));
        replaceRefinement(damaged, new byte[8]);
        List<Recordings.Refinement> pending = store.pendingRefinements();
        assertEquals(12, pending.size());
        for (int i = 0; i < pending.size(); i++) assertEquals(ids.get(i), pending.get(i).id);
        store.failRefinement(ids.get(0));
        pending = store.pendingRefinements();
        assertEquals(12, pending.size());
        assertEquals(ids.get(1), pending.get(0).id);
        assertEquals(ids.get(12), pending.get(11).id);
        assertEquals("corrupt", store.find(damaged).transcriptState);
    }

    public void testCompletedRecentPreviewRemainsBoundedAndOriginalDraftUnchanged() throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(640), 640);
        for (int i = 0; i < 6; i++) store.appendText(id, "Live segment " + i);
        store.finish(id, "saved");
        String fullDraft = store.find(id).liveText;
        for (int i = 0; i < 5; i++) {
            store.commitRefinementBatch(id, i * 128L, (i + 1) * 128L,
                    "Refined segment " + i + " " + repeat('x', 200));
        }
        store.completeRefinement(id);
        Recordings.Session preview = store.recent(1).get(0);
        assertEquals("complete", preview.transcriptState);
        assertTrue(preview.truncated);
        assertTrue(preview.text.length() <= 600);
        assertEquals("Live segment 2\nLive segment 3\nLive segment 4\nLive segment 5", preview.liveText);
        assertEquals(fullDraft, store.find(id).liveText);
        // Omitted refinement chunks are not scanned by recent(), matching original bounds.
        replaceRefinedChunk(id, 0, new byte[8]);
        assertEquals(preview.text, store.recent(1).get(0).text);
        assertEquals("corrupt", store.find(id).transcriptState);
        assertEquals(fullDraft, exportedText(id));
    }

    public void testPcmReaderLeaseAllowsCheckpointButRefusesDeletion() throws Exception {
        String id = saved(640, DRAFT);
        store.forEachPcm(id, bytes -> {
            assertBytes(pcm(640), bytes);
            store.commitRefinementBatch(id, 0, 640, "Refined inside PCM callback");
            store.completeRefinement(id);
            expect(IllegalStateException.class, () -> store.delete(id));
        });
        long nonces = count("nonces");
        store.delete(id);
        assertNull(store.find(id));
        assertNull(store.refinement(id));
        assertTrue(store.pendingRefinements().isEmpty());
        assertEquals(0L, count("refinements"));
        assertEquals(0L, count("refinement_chunks"));
        assertEquals(0L, count("chunks"));
        assertEquals(nonces, count("nonces"));
        expect(IOException.class, () -> store.commitRefinementBatch(id, 0, 640, "Late worker"));
        expect(IOException.class, () -> store.completeRefinement(id));
        store.failRefinement(id); // A deleted work item is a safe no-op, not resurrection.
        assertEquals(0L, count("sessions"));
    }

    public void testTextExportSelectionLeaseAndCallbacksDoNotHoldStoreLock() throws Exception {
        String id = saved(640, DRAFT);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream blocked = new OutputStream() {
            private boolean waited;
            @Override public void write(int value) throws IOException {
                if (!waited) {
                    waited = true;
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                }
                bytes.write(value);
            }
        };
        Future<?> export = pool.submit(() -> {
            try { store.exportText(id, blocked); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> controls = pool.submit(() -> {
                try {
                    expect(IllegalStateException.class, () -> store.delete(id));
                    store.commitRefinementBatch(id, 0, 640, "Completed during draft export");
                    store.completeRefinement(id);
                    store.rename(id, "Rename during export");
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            controls.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            try { export.get(5, TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
        }
        assertEquals(DRAFT, bytes.toString("UTF-8"));
        assertEquals("Completed during draft export", exportedText(id));
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testCompletedExportFailureReleasesReaderWithoutChangingOriginals() throws Exception {
        String id = saved(640, DRAFT);
        store.commitRefinementBatch(id, 0, 640, "Refined text");
        store.completeRefinement(id);
        List<String> originals = originalRows();
        expect(IOException.class, () -> store.exportText(id, new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new IOException("Synthetic destination failure");
            }
        }));
        assertEquals(originals, originalRows());
        assertEquals("Refined text", store.find(id).text);
        store.delete(id);
        assertNull(store.find(id));
    }

    public void testV1MigrationIsAdditiveAndNeverQueuesLegacySessionsAutomatically() throws Exception {
        legacyDatabase();
        String saved = insertLegacySession("saved", DRAFT, pcm(640));
        String active = insertLegacySession("recording", "Legacy interrupted draft", pcm(320));
        List<String> originals = originalRows();
        long nonces = count("nonces");
        store = new Recordings(getContext(), databaseName, alias);
        try (SQLiteDatabase database = raw()) { assertEquals(3, database.getVersion()); }
        assertEquals(originals, originalRows());
        assertEquals(nonces, count("nonces"));
        assertEquals(0L, count("refinements"));
        assertEquals(0L, count("refinement_chunks"));
        assertEquals("none", store.find(saved).transcriptState);
        assertEquals(DRAFT, store.find(saved).text);
        assertBytes(pcm(640), readAudio(saved));
        byte[] wav = exportedWav(saved);
        store.recoverInterrupted();
        assertEquals("interrupted", store.find(active).status);
        assertEquals("none", store.find(active).transcriptState);
        assertNull(store.refinement(active));
        assertTrue(store.pendingRefinements().isEmpty());
        assertBytes(pcm(320), readAudio(active));
        assertEquals(960L, store.totalBytes());
        store.retryRefinement(saved); // Legacy opt-in is explicit and does not alter payloads.
        assertJob(saved, "pending", 0, 640);
        store.commitRefinementBatch(saved, 0, 640, "Legacy explicit refinement");
        store.completeRefinement(saved);
        reopen();
        assertEquals(DRAFT, store.find(saved).liveText);
        assertEquals("Legacy explicit refinement", store.find(saved).text);
        assertBytes(wav, exportedWav(saved));
        assertNull(store.refinement(active));
        String fresh = saved(2, "New recording after migration");
        assertJob(fresh, "pending", 0, 2);
    }

    public void testV2ShapeIndexMigrationPreservesAllEncryptedRowsAndCheckpoints() throws Exception {
        String saved = saved(640, DRAFT);
        store.commitRefinementBatch(saved, 0, 320, "Synthetic checkpoint");
        String active = store.create().id;
        store.appendAudio(active, pcm(320), 320);
        store.appendText(active, "Synthetic live draft [Omi gap: 1]");
        List<String> before = allStoredRows();
        store.closeForTest(); store = null;
        try (SQLiteDatabase database = raw()) {
            database.execSQL("DROP INDEX chunks_shape");
            database.setVersion(2);
        }
        store = new Recordings(getContext(), databaseName, alias);
        try (SQLiteDatabase database = raw()) { assertEquals(3, database.getVersion()); }
        assertEquals(before, allStoredRows());
        assertBytes(pcm(640), readAudio(saved));
        assertBytes(pcm(320), readAudio(active));
        assertEquals(DRAFT, store.find(saved).liveText);
        assertEquals("Synthetic live draft [Omi gap: 1]", store.find(active).text);
        assertEquals("recording", store.find(active).status);
        assertJob(saved, "pending", 320, 640);
        assertEquals(960L, store.totalBytes());
        // The new index must reflect external mutations, never mask a warm-cache failure.
        sql("UPDATE chunks SET plain_length=2 WHERE session_id='" + saved + "' AND kind='audio'");
        expect(Recordings.CorruptRecordingException.class, () -> store.totalBytes());
    }

    public void testV2IndexMigrationFailureRollsBackWithoutChangingRowsOrVersion() throws Exception {
        saved(640, DRAFT);
        List<String> before = allStoredRows();
        store.closeForTest(); store = null;
        try (SQLiteDatabase database = raw()) {
            database.execSQL("DROP INDEX chunks_shape");
            database.execSQL("CREATE INDEX chunks_shape ON chunks(kind)");
            database.setVersion(2);
        }
        expect(SQLException.class, () -> new Recordings(getContext(), databaseName, alias));
        try (SQLiteDatabase database = raw()) {
            assertEquals(2, database.getVersion());
            database.execSQL("DROP INDEX chunks_shape");
        }
        assertEquals(before, allStoredRows());
        store = new Recordings(getContext(), databaseName, alias);
        assertEquals(before, allStoredRows());
        assertEquals(640L, store.totalBytes());
    }

    private List<String> allStoredRows() {
        List<String> result = originalRows();
        try (SQLiteDatabase database = raw()) {
            for (String query : new String[]{
                    "SELECT rowid,hex(nonce) FROM nonces ORDER BY rowid",
                    "SELECT rowid,session_id,hex(envelope) FROM refinements ORDER BY rowid",
                    "SELECT rowid,session_id,sequence,plain_length,hex(envelope) FROM refinement_chunks ORDER BY rowid"}) {
                try (Cursor cursor = database.rawQuery(query, null)) {
                    while (cursor.moveToNext()) {
                        for (int i = 0; i < cursor.getColumnCount(); i++) result.add(cursor.getString(i));
                    }
                }
            }
        }
        return result;
    }

    public void testV1ExplicitQueueFailureRollsBackEncryptedOptIn() throws Exception {
        legacyDatabase();
        String id = insertLegacySession("saved", DRAFT, pcm(640));
        store = new Recordings(getContext(), databaseName, alias);
        List<String> originals = originalRows();
        long nonces = count("nonces");
        sql("CREATE TRIGGER reject_legacy_optin BEFORE UPDATE ON sessions "
                + "BEGIN SELECT RAISE(ABORT,'synthetic legacy opt-in failure'); END");
        try { expect(SQLException.class, () -> store.retryRefinement(id)); }
        finally { sql("DROP TRIGGER reject_legacy_optin"); }
        reopen();
        assertEquals(originals, originalRows());
        assertEquals(nonces, count("nonces"));
        assertNull(store.refinement(id));
        assertTrue(store.pendingRefinements().isEmpty());
        store.retryRefinement(id);
        assertJob(id, "pending", 0, 640);
    }

    private String saved(int length, String text) throws Exception {
        String id = store.create().id;
        store.appendAudio(id, pcm(length), length);
        store.appendText(id, text);
        store.finish(id, "saved");
        return id;
    }

    private void assertJob(String id, String state, long offset, long total) throws Exception {
        Recordings.Refinement entry = store.refinement(id);
        assertNotNull(entry);
        assertEquals(id, entry.id);
        assertEquals(state, entry.state);
        assertEquals(offset, entry.offsetBytes);
        assertEquals(total, entry.totalBytes);
    }

    private void assertOriginalFallback(String id, String text) throws Exception {
        Recordings.Session session = store.find(id);
        assertEquals("corrupt", session.transcriptState);
        assertEquals(text, session.text);
        assertEquals(text, session.liveText);
        assertEquals(text, exportedText(id));
        assertEquals("corrupt", store.recent(1).get(0).transcriptState);
        assertEquals(id, store.list(text).get(0).id);
        assertBytes(pcm((int) session.bytes), readAudio(id));
        byte[] wav = exportedWav(id);
        assertBytes(pcm((int) session.bytes), Arrays.copyOfRange(wav, 44, wav.length));
    }

    private void reopen() throws Exception {
        store.closeForTest();
        store = null;
        store = new Recordings(getContext(), databaseName, alias);
    }

    private byte[] readAudio(String id) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.forEachPcm(id, bytes::write);
        return bytes.toByteArray();
    }

    private byte[] exportedWav(String id) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.exportWav(id, bytes);
        return bytes.toByteArray();
    }

    private String exportedText(String id) throws Exception {
        TrackingOutput bytes = new TrackingOutput();
        store.exportText(id, bytes);
        assertFalse("Caller owns export output", bytes.closed);
        return bytes.toString("UTF-8");
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private SQLiteDatabase raw() {
        return SQLiteDatabase.openDatabase(getContext().getDatabasePath(databaseName).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
    }

    private void sql(String command) {
        try (SQLiteDatabase database = raw()) { database.execSQL(command); }
    }

    private long count(String table) {
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) FROM " + table, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private byte[] queryEnvelope(String query, String... arguments) {
        try (SQLiteDatabase database = raw(); Cursor cursor = database.rawQuery(query, arguments)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getBlob(0);
        }
    }

    private byte[] sessionEnvelope(String id) {
        return queryEnvelope("SELECT envelope FROM sessions WHERE id=?", id);
    }

    private byte[] refinementEnvelope(String id) {
        return queryEnvelope("SELECT envelope FROM refinements WHERE session_id=?", id);
    }

    private byte[] refinedEnvelope(String id, long sequence) {
        return queryEnvelope("SELECT envelope FROM refinement_chunks WHERE session_id=? AND sequence=?",
                id, Long.toString(sequence));
    }

    private byte[] originalChunk(String id, String kind, long sequence) {
        return queryEnvelope("SELECT envelope FROM chunks WHERE session_id=? AND kind=? AND sequence=?",
                id, kind, Long.toString(sequence));
    }

    private void replaceRefinement(String id, byte[] envelope) {
        replaceEnvelope("refinements", "session_id=?", new String[]{id}, envelope);
    }

    private void replaceRefinedChunk(String id, long sequence, byte[] envelope) {
        replaceEnvelope("refinement_chunks", "session_id=? AND sequence=?",
                new String[]{id, Long.toString(sequence)}, envelope);
    }

    private void replaceEnvelope(String table, String where, String[] args, byte[] envelope) {
        try (SQLiteDatabase database = raw()) {
            ContentValues values = new ContentValues();
            values.put("envelope", envelope);
            assertEquals(1, database.update(table, values, where, args));
        }
    }

    /** Compare exact encrypted original rows, identities, sequence/length fields and row order. */
    private List<String> originalRows() {
        List<String> result = new ArrayList<>();
        try (SQLiteDatabase database = raw()) {
            for (String query : new String[]{
                    "SELECT rowid,id,hex(envelope) FROM sessions ORDER BY rowid",
                    "SELECT rowid,session_id,kind,sequence,plain_length,hex(envelope) FROM chunks ORDER BY rowid"}) {
                try (Cursor cursor = database.rawQuery(query, null)) {
                    while (cursor.moveToNext()) {
                        for (int i = 0; i < cursor.getColumnCount(); i++) result.add(cursor.getString(i));
                    }
                }
            }
        }
        return result;
    }

    private void legacyDatabase() throws Exception {
        store.closeForTest();
        store = null;
        assertTrue(getContext().deleteDatabase(databaseName));
        try (SQLiteDatabase database = getContext().openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,envelope BLOB NOT NULL)");
            database.execSQL("CREATE TABLE chunks(session_id TEXT NOT NULL REFERENCES sessions(id) "
                    + "ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN ('audio','text')),"
                    + "sequence INTEGER NOT NULL CHECK(sequence>=0),plain_length INTEGER NOT NULL "
                    + "CHECK(plain_length>0),envelope BLOB NOT NULL,PRIMARY KEY(session_id,kind,sequence))");
            database.execSQL("CREATE TABLE nonces(nonce BLOB PRIMARY KEY NOT NULL)");
            database.setVersion(1);
        }
    }

    private String insertLegacySession(String status, String text, byte[] pcm) throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject manifest = new JSONObject().put("version", 1).put("title", "Legacy title")
                .put("status", status).put("createdAt", 123456789L).put("bytes", pcm.length)
                .put("audioCount", 1).put("textCount", 1);
        try (SQLiteDatabase database = raw()) {
            database.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("envelope", legacyEncrypt(database, id, "meta", 0,
                        manifest.toString().getBytes(StandardCharsets.UTF_8)));
                database.insertOrThrow("sessions", null, values);
                legacyChunk(database, id, "audio", pcm);
                legacyChunk(database, id, "text", text.getBytes(StandardCharsets.UTF_8));
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
        }
        return id;
    }

    private void legacyChunk(SQLiteDatabase database, String id, String kind, byte[] plain) throws Exception {
        ContentValues values = new ContentValues();
        values.put("session_id", id);
        values.put("kind", kind);
        values.put("sequence", 0);
        values.put("plain_length", plain.length);
        values.put("envelope", legacyEncrypt(database, id, kind, 0, plain));
        database.insertOrThrow("chunks", null, values);
    }

    /** Independent v1 on-disk encoder, deliberately not reflection into production helpers. */
    private byte[] legacyEncrypt(SQLiteDatabase database, String id, String kind, long sequence,
                                 byte[] plain) throws Exception {
        SecretKey key = (SecretKey) keys().getKey(alias, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = cipher.getIV();
        assertEquals(12, nonce.length);
        ContentValues reservation = new ContentValues();
        reservation.put("nonce", nonce);
        database.insertOrThrow("nonces", null, reservation);
        ByteArrayOutputStream aad = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(aad)) {
            output.writeUTF("NotTheOmiAIApp/recordings/v1/PCM16LE/16000/mono");
            output.writeUTF(id);
            output.writeUTF(kind);
            output.writeLong(sequence);
        }
        cipher.updateAAD(aad.toByteArray());
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    private static KeyStore keys() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        return keys;
    }

    private interface Throwing { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, Throwing action) throws Exception {
        try { action.run(); }
        catch (Exception failure) {
            if (!type.isInstance(failure)) throw failure;
            assertNotNull(failure.getMessage());
            return;
        }
        fail("Expected " + type.getSimpleName());
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        assertTrue("Byte-for-byte preservation failed", Arrays.equals(expected, actual));
    }

    private static byte[] pcm(int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) result[i] = (byte) (i * 31 + 17);
        return result;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static byte[] fileBytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }
}
