package app.nottheomi.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Phone-private, authenticated, append-only recording chunks. No plaintext files or logs.
 * SQLite FULL-synchronous transactions commit each <= one-second audio chunk together with
 * its authenticated manifest. Only explicit delete removes recordings. Exports deliberately
 * stream plaintext to an output chosen by the user; the caller owns/closes that output.
 */
public final class Recordings {
    public static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long FREE_SPACE_RESERVE_BYTES = 128L * 1024 * 1024;
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_CHUNK_BYTES = SAMPLE_RATE * 2;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 8192;
    private static final int MAX_RECENT_SESSIONS = 12;
    private static final int MAX_PREVIEW_CHUNKS = 4;
    private static final int MAX_PREVIEW_CHARS = 600;
    private static final long TRANSACTION_HEADROOM = 1024 * 1024;
    private static final int MAX_QUOTA_CACHE_ENTRIES = 4096;
    private static final String ACTIVE = "recording";
    private static final String PENDING = "pending", FAILED = "failed", COMPLETE = "complete";
    private static final String CORRUPT = "corrupt";
    private static final String ANNOTATIONS_HEADER = "\n\n[Recording annotations]\n";
    private static final Pattern ANNOTATION = Pattern.compile(
            "\\[(?:Omi\\b|Offline transcript incomplete\\b)[^\\]\\r\\n]*\\]");
    private static Recordings instance;

    private final Object lock = new Object();
    private final StoreDatabase helper;
    private final SQLiteDatabase db;
    private final SecretKey key;
    private final String storagePath;
    private final Map<String, Integer> readers = new HashMap<>();
    // Only authenticated byte counts plus ciphertext fingerprints, never plaintext titles/text.
    // The bounded per-manifest cache also accelerates the conservative full-scan fallback.
    private final Map<String, QuotaSnapshot> quotaCache = new HashMap<>();
    // Valid only for this exact physical SQLite connection + mutation stamp. Never publish
    // a candidate until endTransaction succeeds; never sample its stamp after releasing the
    // writer transaction (another connection could commit in that gap).
    private QuotaState quotaState;
    private long quotaFullScanCount;
    private static final String QUOTA_CONNECTION = "recordings_quota_connection";

    public static synchronized Recordings get(Context context) {
        if (instance == null) {
            try {
                instance = new Recordings(context.getApplicationContext(),
                        "private-recordings.db", "app.nottheomi.ai.recordings.v1");
            } catch (Exception e) {
                throw new IllegalStateException("Private recording store could not open. "
                        + "Existing recordings were not deleted or reset.", e);
            }
        }
        return instance;
    }

    /** Isolated databases/key aliases allow real SQLite/Keystore instrumentation tests. */
    Recordings(Context context, String databaseName, String keyAlias) throws Exception {
        helper = new StoreDatabase(context, databaseName);
        db = helper.getWritableDatabase();
        storagePath = context.getDatabasePath(databaseName).getParent();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(keyAlias)) {
                if (scalar("SELECT COUNT(*) FROM sessions", null) != 0
                        || scalar("SELECT COUNT(*) FROM chunks", null) != 0
                        || scalar("SELECT COUNT(*) FROM nonces", null) != 0
                        || scalar("SELECT COUNT(*) FROM refinements", null) != 0
                        || scalar("SELECT COUNT(*) FROM refinement_chunks", null) != 0) {
                    throw new IOException("Recording encryption key is unavailable. "
                            + "Encrypted originals were kept; do not clear app data.");
                }
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).setRandomizedEncryptionRequired(true).build());
                generator.generateKey();
            }
            key = (SecretKey) store.getKey(keyAlias, null);
            if (key == null) throw new IOException("Recording encryption key is unavailable.");
        } catch (Exception failure) {
            helper.close();
            throw failure;
        }
    }

    /** text selects completed refinement, liveText always retains the original draft.
     * transcriptState: none, pending, failed, complete, or corrupt (originals still usable).
     * recent() bounds both text fields; find()/list() return full text.
     */
    public static final class Session {
        public final String id, title, text, status, transcriptState, liveText;
        public final long createdAt, durationMs, bytes;
        public final boolean truncated;
        private Session(Metadata metadata, String transcript) {
            this(metadata, transcript, transcript, "none", false);
        }
        private Session(Metadata metadata, String transcript, String draft, String state,
                        boolean previewTruncated) {
            id = metadata.id;
            title = metadata.title;
            status = metadata.status;
            text = transcript;
            liveText = draft;
            transcriptState = state;
            createdAt = metadata.createdAt;
            bytes = metadata.bytes;
            durationMs = bytes * 1000L / (SAMPLE_RATE * 2L);
            truncated = previewTruncated;
        }
    }

    /** Authenticated durable checkpoint. States are pending, failed, or complete. */
    public static final class Refinement {
        public final String id, state;
        public final long offsetBytes, totalBytes;
        private Refinement(RefinementMetadata metadata) {
            id = metadata.id;
            state = metadata.state;
            offsetBytes = metadata.offsetBytes;
            totalBytes = metadata.totalBytes;
        }
    }

    public interface PcmConsumer { void accept(byte[] pcm) throws Exception; }

    public static final class CorruptRecordingException extends IOException {
        private static final long serialVersionUID = 1L;
        CorruptRecordingException(String detail) {
            super("Recording integrity check failed (" + detail
                    + "). Encrypted originals were kept; nothing was deleted.");
        }
        CorruptRecordingException(String detail, Throwable cause) {
            this(detail);
            initCause(cause);
        }
    }

    public static final class StorageLimitException extends IOException {
        private static final long serialVersionUID = 1L;
        StorageLimitException(String message) { super(message); }
    }

    public Session create() throws Exception {
        synchronized (lock) {
            for (Metadata item : allMetadata()) {
                if (ACTIVE.equals(item.status)) {
                    throw new IllegalStateException("A recording is already active.");
                }
            }
            checkCapacity(totalBytesLocked(), 0);
            Metadata metadata = new Metadata();
            metadata.id = UUID.randomUUID().toString();
            metadata.createdAt = System.currentTimeMillis();
            metadata.title = "Recording";
            metadata.status = ACTIVE;
            metadata.autoRefine = true;
            transaction(() -> saveMetadata(metadata, true));
            return new Session(metadata, "");
        }
    }

    // Optional numeric-only instrumentation; production callers allocate no trace.
    static final class AppendTrace {
        static final int LOCK = 0, METADATA = 1, QUOTA = 2, BEGIN = 3, AUDIO = 4, MANIFEST = 5, END = 6;
        private static final String[] NAMES = {"lock", "metadata", "quota", "begin", "audio", "manifest", "end"};
        final long[] elapsedNs = new long[NAMES.length], cpuNs = new long[NAMES.length];
        private long elapsedStart, cpuStart;
        void start() {
            elapsedStart = System.nanoTime();
            cpuStart = android.os.Debug.threadCpuTimeNanos();
        }
        void mark(int phase) {
            long cpu = android.os.Debug.threadCpuTimeNanos(), elapsed = System.nanoTime();
            elapsedNs[phase] += elapsed - elapsedStart;
            cpuNs[phase] += cpu - cpuStart;
            elapsedStart = elapsed; cpuStart = cpu;
        }
        @Override public String toString() {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < NAMES.length; i++) {
                if (i != 0) result.append(' ');
                result.append(NAMES[i]).append("_us=").append(elapsedNs[i] / 1000)
                        .append(' ').append(NAMES[i]).append("_cpu_us=").append(cpuNs[i] / 1000);
            }
            return result.toString();
        }
    }

    public void appendAudio(String id, byte[] pcm, int length) throws Exception {
        appendAudio(id, pcm, length, null);
    }

    void appendAudio(String id, byte[] pcm, int length, AppendTrace trace) throws Exception {
        if (pcm == null || length < 0 || length > pcm.length || (length & 1) != 0) {
            throw new IllegalArgumentException("Audio must be complete PCM16 samples.");
        }
        if (trace != null) trace.start();
        synchronized (lock) {
            if (trace != null) trace.mark(AppendTrace.LOCK);
            // Each chunk retains its FULL-durable commit. The first transaction checks
            // the whole request before any prefix is committed, including empty calls.
            // Recheck the remaining request and reload the manifest in every transaction:
            // another store may append text/audio or finish between these commits.
            int offset = 0;
            do {
                int chunkLength = Math.min(MAX_CHUNK_BYTES, length - offset);
                byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + chunkLength);
                appendChunkTransaction(id, "audio", chunk, length - offset, trace);
                offset += chunkLength;
            } while (offset < length);
        }
    }

    public void appendText(String id, String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("Transcript cannot be null.");
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Transcript segment exceeds 1 MiB.");
        }
        synchronized (lock) {
            if (encoded.length == 0) {
                requireActive(loadMetadata(id, false));
                return;
            }
            // Live transcript writes share the trusted append path so a final sentence
            // does not force the next PCM batch to rescan an all-day archive.
            appendChunkTransaction(id, "text", encoded, 0, null);
        }
    }

    public void finish(String id, String status) throws Exception {
        if (status == null || status.trim().isEmpty() || status.length() > 256
                || ACTIVE.equals(status)) {
            throw new IllegalArgumentException("A non-active completion status is required.");
        }
        synchronized (lock) {
            Metadata metadata = loadMetadata(id);
            // Repeated Stop callbacks must not rewrite an earlier meaningful outcome.
            if (!ACTIVE.equals(metadata.status)) return;
            metadata.status = status;
            transaction(() -> {
                if (metadata.autoRefine && metadata.bytes > 0) enqueueRefinement(metadata);
                saveMetadata(metadata, false);
            });
        }
    }

    /** Call only when capture is known idle after application/process startup. */
    public void recoverInterrupted() throws Exception {
        synchronized (lock) {
            List<Metadata> items = allMetadata();
            transaction(() -> {
                for (Metadata metadata : items) {
                    if (ACTIVE.equals(metadata.status)) {
                        metadata.status = "interrupted";
                        if (metadata.autoRefine && metadata.bytes > 0) enqueueRefinement(metadata);
                        saveMetadata(metadata, false);
                    }
                }
            });
        }
    }

    /** Oldest pending jobs first; returns at most 12, without exposing state in SQL.
     * Damaged entries are kept but skipped so one bad derivative cannot starve other jobs.
     * refinement(id) remains strict and reports the integrity error for that entry.
     */
    public List<Refinement> pendingRefinements() throws Exception {
        synchronized (lock) {
            List<Refinement> result = new ArrayList<>();
            try (Cursor cursor = db.rawQuery(
                    "SELECT session_id FROM refinements ORDER BY rowid", null)) {
                while (result.size() < MAX_RECENT_SESSIONS && cursor.moveToNext()) {
                    try {
                        RefinementMetadata item = loadRefinement(loadMetadata(cursor.getString(0)), true);
                        if (item != null && PENDING.equals(item.state)) result.add(new Refinement(item));
                    } catch (CorruptRecordingException damaged) {
                        // Do not repair, delete, or change an unauthenticated checkpoint.
                    }
                }
            }
            return result;
        }
    }

    public Refinement refinement(String id) throws Exception {
        synchronized (lock) {
            if (!exists(id)) return null;
            RefinementMetadata item = loadRefinement(loadMetadata(id), true);
            return item == null ? null : new Refinement(item);
        }
    }

    /** Compare-and-set an even PCM byte offset and optional text in one transaction. */
    public void commitRefinementBatch(String id, long expectedOffset, long nextOffset, String text)
            throws Exception {
        if (text == null || expectedOffset < 0 || nextOffset <= expectedOffset
                || (expectedOffset & 1) != 0 || (nextOffset & 1) != 0) {
            throw new IllegalArgumentException("An advancing PCM16 checkpoint and text are required.");
        }
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Transcript segment exceeds 1 MiB.");
        }
        try {
            synchronized (lock) {
                checkFreeSpace(encoded.length);
                transaction(() -> {
                    Metadata metadata = loadMetadata(id);
                    requireRefinable(metadata);
                    RefinementMetadata item = loadRefinement(metadata, true);
                    if (item == null || !PENDING.equals(item.state)
                            || item.offsetBytes != expectedOffset) {
                        throw new IllegalStateException("Refinement checkpoint is no longer pending/current.");
                    }
                    if (nextOffset > item.totalBytes) {
                        throw new IllegalArgumentException("Checkpoint exceeds saved audio.");
                    }
                    if (!text.trim().isEmpty()) {
                        ContentValues values = new ContentValues();
                        values.put("session_id", id);
                        values.put("sequence", item.textCount);
                        values.put("plain_length", encoded.length);
                        values.put("envelope", encrypt(id, "refinement-text", item.textCount, encoded));
                        db.insertOrThrow("refinement_chunks", null, values);
                        item.textCount++;
                        item.textBytes += encoded.length;
                    }
                    item.offsetBytes = nextOffset;
                    saveRefinement(item, false);
                });
            }
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    /** Publish only a fully processed, authenticated, nonblank transcript; otherwise keep draft. */
    public void completeRefinement(String id) throws Exception {
        synchronized (lock) {
            transaction(() -> {
                Metadata metadata = loadMetadata(id);
                requireRefinable(metadata);
                RefinementMetadata item = loadRefinement(metadata, true);
                if (item != null && COMPLETE.equals(item.state)) return;
                if (item == null || !PENDING.equals(item.state)
                        || item.offsetBytes != item.totalBytes) {
                    throw new IllegalStateException("Refinement has not processed all saved audio.");
                }
                boolean nonblank = false;
                for (long sequence = 0; sequence < item.textCount; sequence++) {
                    if (!readText(id, sequence, true).trim().isEmpty()) nonblank = true;
                }
                item.state = nonblank ? COMPLETE : FAILED;
                checkFreeSpace(0);
                saveRefinement(item, false);
            });
        }
    }

    public void failRefinement(String id) throws Exception {
        synchronized (lock) {
            transaction(() -> {
                if (!exists(id)) return;
                RefinementMetadata item = loadRefinement(loadMetadata(id), true);
                if (item != null && PENDING.equals(item.state)) {
                    item.state = FAILED;
                    checkFreeSpace(0);
                    saveRefinement(item, false);
                }
            });
        }
    }

    /** Explicit legacy opt-in; retries retain nonempty checkpoints and never reset completed
     * work. A failed pass with no text starts at zero so silence can be decoded again.
     */
    public void retryRefinement(String id) throws Exception {
        synchronized (lock) {
            transaction(() -> {
                Metadata metadata = loadMetadata(id);
                requireRefinable(metadata);
                RefinementMetadata item = loadRefinement(metadata, true);
                if (item == null) {
                    checkFreeSpace(0);
                    enqueueRefinement(metadata);
                    saveMetadata(metadata, false);
                } else if (FAILED.equals(item.state)) {
                    item.state = PENDING;
                    if (item.textCount == 0) item.offsetBytes = 0;
                    checkFreeSpace(0);
                    saveRefinement(item, false);
                }
            });
        }
    }

    private void requireRefinable(Metadata metadata) {
        if (ACTIVE.equals(metadata.status) || metadata.bytes == 0) {
            throw new IllegalStateException("Refinement requires stopped, nonempty saved audio.");
        }
    }

    private void enqueueRefinement(Metadata metadata) throws Exception {
        requireRefinable(metadata);
        // Stop/recovery must still persist their small final manifests after capture hits
        // the free-space reserve. Explicit retry checks space before entering this helper.
        RefinementMetadata item = new RefinementMetadata();
        item.id = metadata.id;
        item.state = PENDING;
        item.totalBytes = metadata.bytes;
        saveRefinement(item, true);
        metadata.refinementQueued = true;
    }

    public List<Session> list(String query) throws Exception {
        List<String> ids = new ArrayList<>();
        synchronized (lock) {
            try (Cursor cursor = db.rawQuery("SELECT id FROM sessions", null)) {
                while (cursor.moveToNext()) ids.add(cursor.getString(0));
            }
        }
        String needle = fold(query == null ? "" : query);
        List<Session> result = new ArrayList<>();
        // Each row is an authenticated prefix snapshot. Do not lock the writer while
        // decrypting a whole library; a deleted row may disappear before its lease.
        for (String id : ids) {
            Session session = find(id);
            if (session != null && (needle.isEmpty() || fold(session.title).contains(needle)
                    || fold(session.text).contains(needle))) result.add(session);
        }
        result.sort(Comparator.comparingLong((Session item) -> item.createdAt)
                .reversed().thenComparing(item -> item.id));
        return result;
    }

    /** Newest saved sessions by insertion order (also stable if the wall clock changes).
     * At most 12 results, with the last four text chunks capped to 600 UTF-16 characters.
     * Metadata and selected text are authenticated; audio and omitted text are not read.
     * Unlike list(), this never decrypts the whole archive. Call off the UI thread.
     */
    public List<Session> recent(int limit) throws Exception {
        List<Session> result = new ArrayList<>();
        if (limit <= 0) return result;
        int bounded = Math.min(limit, MAX_RECENT_SESSIONS);
        List<String> ids = new ArrayList<>();
        synchronized (lock) {
            // create() permits only one active session. One extra candidate covers it
            // without indexing private timestamps/status in plaintext or scanning history.
            try (Cursor cursor = db.rawQuery("SELECT id FROM sessions ORDER BY rowid DESC LIMIT ?",
                    new String[]{Integer.toString(bounded + 1)})) {
                while (cursor.moveToNext()) ids.add(cursor.getString(0));
            }
        }
        for (String id : ids) {
            if (result.size() == bounded) break;
            Session session = readSession(id, true);
            if (session != null) result.add(session);
        }
        return result;
    }

    /** Returns null only for an absent ID; corruption is never disguised as missing data. */
    public Session find(String id) throws Exception {
        return readSession(id, false);
    }

    private Session readSession(String id, boolean brief) throws Exception {
        Metadata metadata;
        RefinementMetadata refinement = null;
        boolean damaged = false;
        synchronized (lock) {
            if (!exists(id)) return null;
            metadata = loadMetadata(id, !brief);
            if (brief && ACTIVE.equals(metadata.status)) return null;
            try { refinement = loadRefinement(metadata, !brief); }
            catch (CorruptRecordingException failure) { damaged = true; }
            readers.put(id, readers.getOrDefault(id, 0) + 1);
        }
        // Append-only chunks remain stable. The lease prevents deletion/close while
        // Keystore decrypts outside the writer lock; manifests/counts were read together.
        try {
            return brief ? preview(metadata, refinement, damaged)
                    : snapshot(metadata, refinement, damaged);
        } finally { releaseReader(id); }
    }

    public void rename(String id, String title) throws Exception {
        if (title == null || title.trim().isEmpty() || title.length() > 256) {
            throw new IllegalArgumentException("Title must contain 1 to 256 characters.");
        }
        synchronized (lock) {
            Metadata metadata = loadMetadata(id);
            metadata.title = title.trim();
            transaction(() -> saveMetadata(metadata, false));
        }
    }

    /** Caller obtains explicit user confirmation. Never delete active/streaming recordings. */
    public void delete(String id) throws Exception {
        synchronized (lock) {
            // An authenticated inactive manifest is sufficient for an explicit deletion;
            // damaged/missing payload chunks must not make a recording impossible to remove.
            Metadata metadata = loadMetadata(id, false);
            if (ACTIVE.equals(metadata.status)) {
                throw new IllegalStateException("Stop this recording before deleting it.");
            }
            if (readers.containsKey(id)) {
                throw new IllegalStateException("Stop playback or wait for export before deleting.");
            }
            transaction(() -> db.delete("sessions", "id=?", new String[]{id}));
            // Nonce tombstones are intentionally kept: even a deleted chunk's nonce cannot recur.
        }
    }

    public long totalBytes() throws Exception {
        synchronized (lock) { return totalBytesLocked(); }
    }

    public void exportText(String id, OutputStream out) throws Exception {
        if (out == null) throw new IllegalArgumentException("Output is required.");
        Metadata metadata = acquireReader(id);
        try {
            RefinementMetadata refinement;
            try {
                synchronized (lock) { refinement = loadRefinement(metadata, true); }
                // Authenticate every selected derivative before emitting anything. Falling
                // back after a partial write would mix Whisper text with the original draft.
                // Retain the reader lease but release the lock between bounded chunks.
                if (refinement != null && COMPLETE.equals(refinement.state)) {
                    for (long sequence = 0; sequence < refinement.textCount; sequence++) {
                        byte[] chunk;
                        synchronized (lock) { chunk = readRefinementChunk(id, sequence); }
                        Arrays.fill(chunk, (byte) 0);
                    }
                }
            } catch (CorruptRecordingException damaged) {
                refinement = null; // Original text remains independently authenticated below.
            }
            // Snapshot the selection once, keep the lease through annotations/output, and
            // never hold the store lock while calling an output supplied by the caller.
            boolean refined = refinement != null && COMPLETE.equals(refinement.state);
            long count = refined ? refinement.textCount : metadata.textCount;
            for (long sequence = 0; sequence < count; sequence++) {
                byte[] chunk;
                synchronized (lock) {
                    chunk = refined ? readRefinementChunk(id, sequence) : readChunk(id, "text", sequence);
                }
                try {
                    if (sequence != 0) out.write('\n');
                    out.write(chunk);
                } finally { Arrays.fill(chunk, (byte) 0); }
            }
            if (refined) {
                boolean header = false;
                for (long sequence = 0; sequence < metadata.textCount; sequence++) {
                    String annotations;
                    synchronized (lock) { annotations = annotations(readText(id, sequence, false)); }
                    if (annotations.isEmpty()) continue;
                    out.write((header ? "\n" : ANNOTATIONS_HEADER).getBytes(StandardCharsets.UTF_8));
                    out.write(annotations.getBytes(StandardCharsets.UTF_8));
                    header = true;
                }
            }
        } finally { releaseReader(id); }
    }

    public void exportWav(String id, OutputStream out) throws Exception {
        if (out == null) throw new IllegalArgumentException("Output is required.");
        Metadata metadata = acquireReader(id);
        try {
            // Standard RIFF PCM16 little-endian, mono 16kHz; <=2 GiB remains within RIFF limits.
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            header.putInt((int) (36 + metadata.bytes));
            header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            header.putInt(16).putShort((short) 1).putShort((short) 1);
            header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
            header.putShort((short) 2).putShort((short) 16);
            header.put("data".getBytes(StandardCharsets.US_ASCII));
            header.putInt((int) metadata.bytes);
            out.write(header.array());
            streamAudio(metadata, out::write);
        } finally { releaseReader(id); }
    }

    public void forEachPcm(String id, PcmConsumer consumer) throws Exception {
        if (consumer == null) throw new IllegalArgumentException("Consumer is required.");
        Metadata metadata = acquireReader(id);
        try { streamAudio(metadata, consumer); }
        finally { releaseReader(id); }
    }

    private void streamAudio(Metadata metadata, PcmConsumer consumer) throws Exception {
        long bytes = 0;
        for (long sequence = 0; sequence < metadata.audioCount; sequence++) {
            byte[] pcm;
            synchronized (lock) { pcm = readChunk(metadata.id, "audio", sequence); }
            bytes += pcm.length;
            // Never hold store lock around playback, SAF writes, or any other caller code.
            consumer.accept(pcm);
        }
        if (bytes != metadata.bytes) throw new CorruptRecordingException("audio length");
    }

    private Metadata acquireReader(String id) throws Exception {
        synchronized (lock) {
            Metadata metadata = loadMetadata(id);
            readers.put(id, readers.getOrDefault(id, 0) + 1);
            return metadata;
        }
    }

    private void releaseReader(String id) {
        synchronized (lock) {
            int remaining = readers.get(id) - 1;
            if (remaining == 0) readers.remove(id); else readers.put(id, remaining);
        }
    }

    private Session snapshot(Metadata metadata, RefinementMetadata item, boolean corruptDerivative) throws Exception {
        String live = transcript(metadata.id, metadata.textCount, false);
        if (corruptDerivative) return new Session(metadata, live, live, CORRUPT, false);
        try {
            String selected = item != null && COMPLETE.equals(item.state)
                    ? withAnnotations(transcript(metadata.id, item.textCount, true), live) : live;
            return new Session(metadata, selected, live, item == null ? "none" : item.state, false);
        } catch (CorruptRecordingException damaged) {
            // A derivative must never hide an intact original. Original failures above
            // still propagate, and strict checkpoint APIs never accept this fallback.
            return new Session(metadata, live, live, CORRUPT, false);
        }
    }

    private Session preview(Metadata metadata, RefinementMetadata item, boolean corruptDerivative) throws Exception {
        // Like the original preview, do not scan/decrypt omitted chunks or audio.
        TextPreview live = textPreview(metadata.id, metadata.textCount, false);
        if (corruptDerivative) return new Session(metadata, live.text, live.text, CORRUPT, live.truncated);
        try {
            TextPreview selected = live;
            if (item != null && COMPLETE.equals(item.state)) {
                TextPreview refined = textPreview(metadata.id, item.textCount, true);
                String combined = withAnnotations(refined.text, live.text);
                selected = new TextPreview(tail(combined), refined.truncated || live.truncated
                        || combined.length() > MAX_PREVIEW_CHARS);
            }
            return new Session(metadata, selected.text, live.text, item == null ? "none" : item.state,
                    selected.truncated);
        } catch (CorruptRecordingException damaged) {
            return new Session(metadata, live.text, live.text, CORRUPT, live.truncated);
        }
    }

    private TextPreview textPreview(String id, long count, boolean refined) throws Exception {
        long first = Math.max(0, count - MAX_PREVIEW_CHUNKS);
        boolean truncated = first != 0;
        StringBuilder transcript = new StringBuilder(MAX_PREVIEW_CHARS);
        for (long sequence = first; sequence < count; sequence++) {
            String chunk = readText(id, sequence, refined);
            if (sequence != first) transcript.append('\n');
            int start = Math.max(0, chunk.length() - MAX_PREVIEW_CHARS);
            if (start > 0) truncated = true;
            transcript.append(chunk, start, chunk.length());
            if (transcript.length() > MAX_PREVIEW_CHARS) {
                transcript.delete(0, transcript.length() - MAX_PREVIEW_CHARS);
                truncated = true;
            }
            // Never leave half of a supplementary code point at a clipped boundary.
            if (truncated && transcript.length() > 0
                    && Character.isLowSurrogate(transcript.charAt(0))) {
                transcript.deleteCharAt(0);
            }
        }
        return new TextPreview(transcript.toString(), truncated);
    }

    private String transcript(String id, long count, boolean refined) throws Exception {
        StringBuilder result = new StringBuilder();
        for (long sequence = 0; sequence < count; sequence++) {
            if (sequence != 0) result.append('\n');
            result.append(readText(id, sequence, refined));
        }
        return result.toString();
    }

    private String readText(String id, long sequence, boolean refined) throws Exception {
        byte[] plain = refined ? readRefinementChunk(id, sequence) : readChunk(id, "text", sequence);
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    private static String annotations(String live) {
        Matcher matcher = ANNOTATION.matcher(live);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (result.length() != 0) result.append('\n');
            result.append(matcher.group());
        }
        return result.toString();
    }

    private static String withAnnotations(String refined, String live) {
        String annotations = annotations(live);
        return annotations.isEmpty() ? refined : refined + ANNOTATIONS_HEADER + annotations;
    }

    private static String tail(String text) {
        int first = Math.max(0, text.length() - MAX_PREVIEW_CHARS);
        if (first > 0 && Character.isLowSurrogate(text.charAt(first))) first++;
        return text.substring(first);
    }

    private static final class TextPreview {
        final String text;
        final boolean truncated;
        TextPreview(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    private static String fold(String text) {
        // NFC plus Unicode ROOT upper/lower folding handles accents, sigma and sharp-s.
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private void requireActive(Metadata metadata) {
        if (!ACTIVE.equals(metadata.status)) {
            throw new IllegalStateException("This recording has already stopped.");
        }
    }

    private boolean exists(String id) {
        return scalar("SELECT COUNT(*) FROM sessions WHERE id=?", new String[]{id}) != 0;
    }

    private List<Metadata> allMetadata() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id FROM sessions", null)) {
            while (cursor.moveToNext()) ids.add(cursor.getString(0));
        }
        List<Metadata> result = new ArrayList<>();
        for (String id : ids) result.add(loadMetadata(id));
        return result;
    }

    /** Every stamp/read/scan is inside an outermost writer transaction. Android pins that
     * thread to the primary connection, and SQLite excludes other writers until commit.
     * data_version alone is unsafe: it is connection-local and ignores own-connection DML.
     */
    private long totalBytesLocked() throws Exception {
        requireOwnQuotaTransaction();
        QuotaState next;
        db.beginTransaction();
        try {
            next = checkedQuotaLocked();
            db.setTransactionSuccessful();
        } finally {
            try { db.endTransaction(); }
            finally { quotaState = null; }
        }
        quotaState = next;
        return next.bytes;
    }

    private void requireOwnQuotaTransaction() {
        if (db.inTransaction()) {
            // A nested endTransaction is not a durable commit. Do not publish a cache
            // derived from a caller transaction that can still roll back (or be read-only).
            throw new IllegalStateException("Recording admission requires its own transaction.");
        }
    }

    private void appendChunkTransaction(String id, String kind, byte[] chunk,
                                        long remainingAudioBytes, AppendTrace trace) throws Exception {
        requireOwnQuotaTransaction();
        QuotaState next;
        QuotaSnapshot updated = null;
        if (trace != null) trace.start();
        db.beginTransaction();
        if (trace != null) trace.mark(AppendTrace.BEGIN);
        try {
            QuotaState before = checkedQuotaLocked();
            if ("audio".equals(kind)) checkCapacity(before.bytes, remainingAudioBytes);
            else checkFreeSpace(chunk.length);
            if (trace != null) trace.mark(AppendTrace.QUOTA);
            Metadata metadata = loadMetadata(id, false);
            requireActive(metadata);
            if (trace != null) trace.mark(AppendTrace.METADATA);
            boolean audio = "audio".equals(kind);
            if (chunk.length != 0) {
                insertChunk(id, kind, audio ? metadata.audioCount : metadata.textCount, chunk);
                if (trace != null) trace.mark(AppendTrace.AUDIO);
                if (audio) {
                    metadata.audioCount++;
                    metadata.bytes += chunk.length;
                } else metadata.textCount++;
                saveMetadata(metadata, false);
                if (trace != null) trace.mark(AppendTrace.MANIFEST);
            }
            long total = Math.addExact(before.bytes, audio ? chunk.length : 0);
            QuotaStamp after = readQuotaStampLocked();
            // Two unique nonce reservations + chunk insert + manifest update. Even a
            // nonce-collision retry takes the conservative fallback. Triggers are never
            // trusted by delta alone: RAISE(IGNORE) can replace an insertion with a
            // historical mutation while preserving the same total_changes() delta.
            boolean exactWrite = before.triggerFree && before.stamp.advancesTo(after,
                    chunk.length == 0 ? 0 : 4);
            if (!exactWrite) {
                long verified = scanTotalBytesLocked();
                if (verified != total) throw new CorruptRecordingException("append quota changed");
            } else if (chunk.length != 0 && (quotaCache.containsKey(id)
                    || quotaCache.size() < MAX_QUOTA_CACHE_ENTRIES)) {
                try (Cursor cursor = db.rawQuery("SELECT envelope FROM sessions WHERE id=?",
                        new String[]{id})) {
                    if (!cursor.moveToFirst()) throw new CorruptRecordingException("missing manifest");
                    updated = new QuotaSnapshot(MessageDigest.getInstance("SHA-256")
                            .digest(cursor.getBlob(0)), metadata);
                }
            }
            next = new QuotaState(after, total, before.triggerFree);
            db.setTransactionSuccessful();
        } finally {
            if (trace != null) trace.start();
            try { db.endTransaction(); }
            finally {
                quotaState = null;
                if (trace != null) trace.mark(AppendTrace.END);
            }
        }
        // Use the stamp captured before commit, not a fresh (racy) post-commit stamp.
        quotaState = next;
        if (updated != null) quotaCache.put(id, updated);
    }

    private QuotaState checkedQuotaLocked() throws Exception {
        QuotaStamp stamp = readQuotaStampLocked();
        if (quotaState != null && quotaState.stamp.advancesTo(stamp, 0)) return quotaState;
        quotaState = null;
        // Unqualified existing SQL must never authenticate shadow TEMP tables instead
        // of the archive. Normal operation has only the connection sentinel in TEMP.
        if (scalar("SELECT COUNT(*) FROM sqlite_temp_master WHERE name IN "
                + "('sessions','chunks','nonces','refinements','refinement_chunks')", null) != 0) {
            throw new CorruptRecordingException("temporary archive shadow");
        }
        boolean triggerFree = scalar("SELECT COUNT(*) FROM main.sqlite_master WHERE type='trigger'",
                null) == 0 && scalar("SELECT COUNT(*) FROM sqlite_temp_master WHERE type='trigger'",
                null) == 0;
        return new QuotaState(stamp, scanTotalBytesLocked(), triggerFree);
    }

    private QuotaStamp readQuotaStampLocked() {
        if (!db.inTransaction()) throw new IllegalStateException("Unpinned recording quota check.");
        // A reopened/replaced primary may repeat all numeric counters. A TEMP row lives
        // on the physical connection only; create a NEW random identity if it vanished.
        // Creation is transactional too, so rollback cannot leave a published identity.
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS " + QUOTA_CONNECTION
                + "(slot INTEGER PRIMARY KEY CHECK(slot=1),token TEXT NOT NULL)");
        String token;
        try (Cursor cursor = db.rawQuery("SELECT token FROM temp." + QUOTA_CONNECTION
                + " WHERE slot=1", null)) {
            token = cursor.moveToFirst() ? cursor.getString(0) : null;
        }
        if (token == null) {
            token = UUID.randomUUID().toString();
            db.execSQL("INSERT INTO temp." + QUOTA_CONNECTION + "(slot,token) VALUES(1,?)",
                    new Object[]{token});
        }
        return new QuotaStamp(token, scalar("PRAGMA main.data_version", null),
                scalar("SELECT total_changes()", null), scalar("PRAGMA main.schema_version", null),
                scalar("PRAGMA temp.schema_version", null));
    }

    // Numeric-only, deterministic regression evidence: no timing or device-speed threshold.
    long quotaFullScanCountForTest() { synchronized (lock) { return quotaFullScanCount; } }

    private static final class QuotaStamp {
        final String connection;
        final long dataVersion, changes, schemaVersion, tempSchemaVersion;
        QuotaStamp(String connection, long dataVersion, long changes, long schemaVersion,
                   long tempSchemaVersion) {
            this.connection = connection; this.dataVersion = dataVersion; this.changes = changes;
            this.schemaVersion = schemaVersion; this.tempSchemaVersion = tempSchemaVersion;
        }
        boolean advancesTo(QuotaStamp other, long expectedChanges) {
            return connection.equals(other.connection) && dataVersion == other.dataVersion
                    && schemaVersion == other.schemaVersion && tempSchemaVersion == other.tempSchemaVersion
                    && other.changes - changes == expectedChanges;
        }
    }

    private static final class QuotaState {
        final QuotaStamp stamp;
        final long bytes;
        final boolean triggerFree;
        QuotaState(QuotaStamp stamp, long bytes, boolean triggerFree) {
            this.stamp = stamp; this.bytes = bytes; this.triggerFree = triggerFree;
        }
    }

    private long scanTotalBytesLocked() throws Exception {
        quotaFullScanCount++;
        long result = 0;
        HashSet<String> present = new HashSet<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // Reauthenticating every unchanged historical manifest for every 200ms batch
        // serialized AndroidKeyStore IPC with capture. Reuse a count only for identical
        // ciphertext under the same recording ID; changed/corrupt envelopes authenticate
        // again. Plain chunk lengths never become the authority for the audio quota.
        try (Cursor cursor = db.rawQuery("SELECT id,envelope FROM sessions", null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                byte[] envelope = cursor.getBlob(1);
                if (envelope == null || envelope.length < 28 || envelope.length > MAX_METADATA_BYTES + 28) {
                    throw new CorruptRecordingException("encrypted chunk size");
                }
                byte[] fingerprint = digest.digest(envelope);
                QuotaSnapshot cached = quotaCache.get(id);
                Metadata metadata;
                if (cached != null && MessageDigest.isEqual(cached.fingerprint, fingerprint)) {
                    metadata = new Metadata();
                    metadata.id = id;
                    metadata.bytes = cached.bytes;
                    metadata.audioCount = cached.audioCount;
                    metadata.textCount = cached.textCount;
                } else {
                    metadata = decodeMetadata(id, envelope);
                    if (cached != null || quotaCache.size() < MAX_QUOTA_CACHE_ENTRIES) {
                        quotaCache.put(id, new QuotaSnapshot(fingerprint, metadata));
                    }
                }
                // A matching manifest must not hide chunk removal, reordering, or length
                // tampering. Retain the same shape checks as the uncached loadMetadata.
                verifyShape(metadata, "audio", metadata.audioCount, metadata.bytes);
                verifyShape(metadata, "text", metadata.textCount, -1);
                if (quotaCache.containsKey(id)) present.add(id);
                result = Math.addExact(result, metadata.bytes);
            }
        }
        quotaCache.keySet().retainAll(present);
        return result;
    }

    private static final class QuotaSnapshot {
        final byte[] fingerprint;
        final long bytes, audioCount, textCount;
        QuotaSnapshot(byte[] fingerprint, Metadata metadata) {
            this.fingerprint = fingerprint; this.bytes = metadata.bytes;
            this.audioCount = metadata.audioCount; this.textCount = metadata.textCount;
        }
    }

    /** Overflow-safe pure predicate, also exercised at exact quota/reserve boundaries. */
    static boolean capacityAllows(long retained, long added, long available) {
        return retained >= 0 && added >= 0 && retained <= MAX_AUDIO_BYTES
                && added <= MAX_AUDIO_BYTES - retained
                && available >= FREE_SPACE_RESERVE_BYTES + TRANSACTION_HEADROOM
                && added <= available - FREE_SPACE_RESERVE_BYTES - TRANSACTION_HEADROOM;
    }

    private void checkCapacity(long retained, long added) throws StorageLimitException {
        if (retained < 0 || added < 0 || retained > MAX_AUDIO_BYTES
                || added > MAX_AUDIO_BYTES - retained) {
            throw new StorageLimitException("The 2 GiB audio limit has been reached. "
                    + "Export and explicitly delete a recording to free space; nothing was deleted.");
        }
        checkFreeSpace(added);
    }

    private void checkFreeSpace(long added) throws StorageLimitException {
        long available = new StatFs(storagePath).getAvailableBytes();
        if (!capacityAllows(0, added, available)) {
            throw new StorageLimitException("Low device storage: a 128 MiB reserve is protected. "
                    + "Free device space to continue; existing recordings were kept.");
        }
    }

    private Metadata loadMetadata(String id) throws Exception {
        return loadMetadata(id, true);
    }

    private Metadata loadMetadata(String id, boolean verifyChunks) throws Exception {
        byte[] envelope;
        try (Cursor cursor = db.rawQuery("SELECT envelope FROM sessions WHERE id=?",
                new String[]{id})) {
            if (!cursor.moveToFirst()) throw new IOException("Recording does not exist.");
            envelope = cursor.getBlob(0);
        }
        Metadata metadata = decodeMetadata(id, envelope);
        if (verifyChunks) {
            verifyShape(metadata, "audio", metadata.audioCount, metadata.bytes);
            verifyShape(metadata, "text", metadata.textCount, -1);
        }
        return metadata;
    }

    private Metadata decodeMetadata(String id, byte[] envelope) throws Exception {
        byte[] plain = decrypt(id, "meta", 0, envelope, MAX_METADATA_BYTES);
        Metadata metadata = new Metadata();
        try {
            JSONObject object = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (object.getInt("version") != 1) throw new IllegalArgumentException();
            metadata.id = id;
            metadata.title = object.getString("title");
            metadata.status = object.getString("status");
            metadata.createdAt = object.getLong("createdAt");
            metadata.bytes = object.getLong("bytes");
            metadata.audioCount = object.getLong("audioCount");
            metadata.textCount = object.getLong("textCount");
            metadata.autoRefine = optionalBoolean(object, "autoRefine");
            metadata.refinementQueued = optionalBoolean(object, "refinementQueued");
            if (metadata.bytes < 0 || metadata.bytes > MAX_AUDIO_BYTES
                    || (metadata.bytes & 1) != 0 || metadata.audioCount < 0
                    || metadata.textCount < 0 || metadata.title.length() > 256
                    || metadata.status.isEmpty() || metadata.status.length() > 256
                    || (metadata.refinementQueued && (ACTIVE.equals(metadata.status) || metadata.bytes == 0))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception failure) {
            throw new CorruptRecordingException("manifest", failure);
        } finally { Arrays.fill(plain, (byte) 0); }
        return metadata;
    }

    private void verifyShape(Metadata metadata, String kind, long expected, long bytes)
            throws CorruptRecordingException {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(plain_length),0),"
                + "COALESCE(MIN(sequence),0),COALESCE(MAX(sequence),-1) FROM chunks "
                + "WHERE session_id=? AND kind=?", new String[]{metadata.id, kind})) {
            cursor.moveToFirst();
            if (cursor.getLong(0) != expected || (bytes >= 0 && cursor.getLong(1) != bytes)
                    || cursor.getLong(2) != 0 || cursor.getLong(3) != expected - 1) {
                throw new CorruptRecordingException("missing or reordered " + kind + " chunks");
            }
        }
    }

    private void saveMetadata(Metadata metadata, boolean insert) throws Exception {
        JSONObject object = new JSONObject();
        object.put("version", 1).put("title", metadata.title).put("status", metadata.status)
                .put("createdAt", metadata.createdAt).put("bytes", metadata.bytes)
                .put("audioCount", metadata.audioCount).put("textCount", metadata.textCount)
                .put("autoRefine", metadata.autoRefine).put("refinementQueued", metadata.refinementQueued);
        ContentValues values = new ContentValues();
        values.put("envelope", encrypt(metadata.id, "meta", 0,
                object.toString().getBytes(StandardCharsets.UTF_8)));
        if (insert) {
            values.put("id", metadata.id);
            db.insertOrThrow("sessions", null, values);
        } else if (db.update("sessions", values, "id=?", new String[]{metadata.id}) != 1) {
            throw new CorruptRecordingException("missing manifest");
        }
    }

    private static boolean optionalBoolean(JSONObject object, String name) throws Exception {
        if (!object.has(name)) return false;
        Object value = object.get(name);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid flag.");
        return (Boolean) value;
    }

    private static long integer(JSONObject object, String name) throws Exception {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid checkpoint integer.");
        }
        return ((Number) value).longValue();
    }

    private RefinementMetadata loadRefinement(Metadata metadata, boolean verifyChunks) throws Exception {
        byte[] envelope;
        try (Cursor cursor = db.rawQuery("SELECT envelope FROM refinements WHERE session_id=?",
                new String[]{metadata.id})) {
            if (!cursor.moveToFirst()) {
                if (metadata.refinementQueued || scalar(
                        "SELECT COUNT(*) FROM refinement_chunks WHERE session_id=?",
                        new String[]{metadata.id}) != 0) {
                    throw new CorruptRecordingException("missing refinement manifest");
                }
                return null;
            }
            envelope = cursor.getBlob(0);
        }
        byte[] plain = decrypt(metadata.id, "refinement-meta", 0, envelope, MAX_METADATA_BYTES);
        RefinementMetadata item = new RefinementMetadata();
        try {
            JSONObject object = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (object.length() != 7 || integer(object, "version") != 1) {
                throw new IllegalArgumentException();
            }
            item.id = metadata.id;
            item.state = object.getString("state");
            item.offsetBytes = integer(object, "offsetBytes");
            item.totalBytes = integer(object, "totalBytes");
            item.textCount = integer(object, "textCount");
            item.textBytes = integer(object, "textBytes");
            // The session binding inside the ciphertext supplements the AAD domain binding.
            if (!metadata.id.equals(object.getString("id")) || !metadata.refinementQueued
                    || ACTIVE.equals(metadata.status) || metadata.bytes <= 0
                    || item.totalBytes != metadata.bytes || item.offsetBytes < 0
                    || item.offsetBytes > item.totalBytes || (item.offsetBytes & 1) != 0
                    || item.textCount < 0 || item.textCount > item.offsetBytes / 2
                    || item.textBytes < item.textCount || item.textBytes > item.textCount * MAX_TEXT_BYTES
                    || (!PENDING.equals(item.state) && !FAILED.equals(item.state) && !COMPLETE.equals(item.state))
                    || (COMPLETE.equals(item.state) && (item.offsetBytes != item.totalBytes || item.textCount == 0))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception failure) {
            throw new CorruptRecordingException("refinement manifest", failure);
        } finally { Arrays.fill(plain, (byte) 0); }
        if (verifyChunks) verifyRefinementShape(item);
        return item;
    }

    private void verifyRefinementShape(RefinementMetadata item) throws CorruptRecordingException {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(plain_length),0),"
                + "COALESCE(MIN(sequence),0),COALESCE(MAX(sequence),-1) FROM refinement_chunks "
                + "WHERE session_id=?", new String[]{item.id})) {
            cursor.moveToFirst();
            if (cursor.getLong(0) != item.textCount || cursor.getLong(1) != item.textBytes
                    || cursor.getLong(2) != 0 || cursor.getLong(3) != item.textCount - 1) {
                throw new CorruptRecordingException("missing or reordered refinement chunks");
            }
        }
    }

    private void saveRefinement(RefinementMetadata item, boolean insert) throws Exception {
        JSONObject object = new JSONObject();
        object.put("version", 1).put("id", item.id).put("state", item.state)
                .put("offsetBytes", item.offsetBytes).put("totalBytes", item.totalBytes)
                .put("textCount", item.textCount).put("textBytes", item.textBytes);
        byte[] plain = object.toString().getBytes(StandardCharsets.UTF_8);
        ContentValues values = new ContentValues();
        try { values.put("envelope", encrypt(item.id, "refinement-meta", 0, plain)); }
        finally { Arrays.fill(plain, (byte) 0); }
        if (insert) {
            values.put("session_id", item.id);
            db.insertOrThrow("refinements", null, values);
        } else if (db.update("refinements", values, "session_id=?", new String[]{item.id}) != 1) {
            throw new CorruptRecordingException("missing refinement manifest");
        }
    }

    private byte[] readRefinementChunk(String id, long sequence) throws Exception {
        byte[] envelope;
        long length;
        synchronized (lock) {
            try (Cursor cursor = db.rawQuery("SELECT envelope,plain_length FROM refinement_chunks "
                    + "WHERE session_id=? AND sequence=?", new String[]{id, Long.toString(sequence)})) {
                if (!cursor.moveToFirst()) throw new CorruptRecordingException("missing refinement chunk");
                envelope = cursor.getBlob(0);
                length = cursor.getLong(1);
            }
        }
        byte[] plain = decrypt(id, "refinement-text", sequence, envelope, MAX_TEXT_BYTES);
        if (plain.length == 0 || plain.length != length) {
            Arrays.fill(plain, (byte) 0);
            throw new CorruptRecordingException("refinement chunk length");
        }
        return plain;
    }

    private void insertChunk(String id, String kind, long sequence, byte[] bytes)
            throws Exception {
        ContentValues values = new ContentValues();
        values.put("session_id", id);
        values.put("kind", kind);
        values.put("sequence", sequence);
        values.put("plain_length", bytes.length);
        values.put("envelope", encrypt(id, kind, sequence, bytes));
        db.insertOrThrow("chunks", null, values);
    }

    private byte[] readChunk(String id, String kind, long sequence) throws Exception {
        byte[] envelope;
        long length;
        synchronized (lock) {
            try (Cursor cursor = db.rawQuery("SELECT envelope,plain_length FROM chunks "
                    + "WHERE session_id=? AND kind=? AND sequence=?",
                    new String[]{id, kind, Long.toString(sequence)})) {
                if (!cursor.moveToFirst()) throw new CorruptRecordingException("missing chunk");
                envelope = cursor.getBlob(0);
                length = cursor.getLong(1);
            }
        }
        byte[] plain = decrypt(id, kind, sequence, envelope,
                "audio".equals(kind) ? MAX_CHUNK_BYTES : MAX_TEXT_BYTES);
        if (plain.length != length || plain.length == 0
                || ("audio".equals(kind) && (plain.length & 1) != 0)) {
            Arrays.fill(plain, (byte) 0);
            throw new CorruptRecordingException("chunk length");
        }
        return plain;
    }

    private byte[] encrypt(String id, String kind, long sequence, byte[] plain)
            throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] nonce = cipher.getIV();
            if (nonce.length != 12) throw new IOException("Unsupported encryption nonce size.");
            ContentValues reservation = new ContentValues();
            reservation.put("nonce", nonce);
            if (db.insertWithOnConflict("nonces", null, reservation,
                    SQLiteDatabase.CONFLICT_IGNORE) == -1) continue;
            cipher.updateAAD(aad(id, kind, sequence));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
        }
        throw new IOException("Could not allocate a unique encryption nonce; data was kept.");
    }

    private byte[] decrypt(String id, String kind, long sequence, byte[] envelope, int maximum)
            throws CorruptRecordingException {
        if (envelope == null || envelope.length < 28 || envelope.length > maximum + 28) {
            throw new CorruptRecordingException("encrypted chunk size");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 0, 12)));
            cipher.updateAAD(aad(id, kind, sequence));
            return cipher.doFinal(envelope, 12, envelope.length - 12);
        } catch (Exception failure) {
            throw new CorruptRecordingException("authentication or unavailable key", failure);
        }
    }

    private static byte[] aad(String id, String kind, long sequence) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("NotTheOmiAIApp/recordings/v1/PCM16LE/16000/mono");
            output.writeUTF(id);
            output.writeUTF(kind);
            output.writeLong(sequence);
        }
        return bytes.toByteArray();
    }

    private long scalar(String sql, String[] arguments) {
        try (Cursor cursor = db.rawQuery(sql, arguments)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Missing SQL result.");
            return cursor.getLong(0);
        }
    }

    private interface StoreWrite { void run() throws Exception; }

    private void transaction(StoreWrite write) throws Exception {
        transaction(write, null);
    }

    private void transaction(StoreWrite write, AppendTrace trace) throws Exception {
        if (trace != null) trace.start();
        db.beginTransaction();
        if (trace != null) trace.mark(AppendTrace.BEGIN);
        try {
            write.run();
            db.setTransactionSuccessful();
        } finally {
            if (trace != null) trace.start();
            db.endTransaction();
            if (trace != null) trace.mark(AppendTrace.END);
        }
    }

    // Package-private lifecycle only for isolated instrumentation stores, never the singleton.
    void closeForTest() {
        synchronized (lock) {
            if (!readers.isEmpty()) throw new IllegalStateException("Store is streaming.");
            helper.close();
        }
    }

    private static final class Metadata {
        String id, title, status;
        long createdAt, bytes, audioCount, textCount;
        boolean autoRefine, refinementQueued;
    }

    private static final class RefinementMetadata {
        String id, state;
        long offsetBytes, totalBytes, textCount, textBytes;
    }

    private static final class StoreDatabase extends SQLiteOpenHelper {
        StoreDatabase(Context context, String name) { super(context, name, null, 3); }
        @Override public void onConfigure(SQLiteDatabase database) {
            database.setForeignKeyConstraintsEnabled(true);
            database.execSQL("PRAGMA synchronous=FULL");
            // This PRAGMA returns a row; execSQL rejects it on Android SQLite.
            try (Cursor result = database.rawQuery("PRAGMA secure_delete=ON", null)) {
                if (!result.moveToFirst() || result.getInt(0) != 1) {
                    throw new IllegalStateException("Secure deletion could not be enabled.");
                }
            }
        }
        @Override public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,"
                    + "envelope BLOB NOT NULL)");
            database.execSQL("CREATE TABLE chunks(session_id TEXT NOT NULL REFERENCES "
                    + "sessions(id) ON DELETE CASCADE,kind TEXT NOT NULL CHECK(kind IN "
                    + "('audio','text')),sequence INTEGER NOT NULL CHECK(sequence>=0),"
                    + "plain_length INTEGER NOT NULL CHECK(plain_length>0),"
                    + "envelope BLOB NOT NULL,PRIMARY KEY(session_id,kind,sequence))");
            database.execSQL("CREATE TABLE nonces(nonce BLOB PRIMARY KEY NOT NULL)");
            createRefinementTables(database);
            createShapeIndex(database);
        }
        @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            if ((oldVersion != 1 && oldVersion != 2) || newVersion != 3) {
                throw new IllegalStateException("Unsupported recording store version; data kept.");
            }
            if (oldVersion == 1) createRefinementTables(database);
            // SQLiteOpenHelper commits this additive upgrade atomically. No encrypted
            // rows, nonce reservations, manifests or refinement checkpoints are rewritten.
            createShapeIndex(database);
        }
        private static void createShapeIndex(SQLiteDatabase database) {
            // verifyShape still checks every historical chunk against its authenticated
            // manifest. Cover plain_length too, avoiding payload-table page reads on
            // every 200ms append; the existing primary key covers only identity/sequence.
            database.execSQL("CREATE INDEX chunks_shape ON chunks"
                    + "(session_id,kind,sequence,plain_length)");
        }
        private static void createRefinementTables(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE refinements(session_id TEXT PRIMARY KEY NOT NULL "
                    + "REFERENCES sessions(id) ON DELETE CASCADE,envelope BLOB NOT NULL)");
            database.execSQL("CREATE TABLE refinement_chunks(session_id TEXT NOT NULL REFERENCES "
                    + "sessions(id) ON DELETE CASCADE,sequence INTEGER NOT NULL CHECK(sequence>=0),"
                    + "plain_length INTEGER NOT NULL CHECK(plain_length>0),"
                    + "envelope BLOB NOT NULL,PRIMARY KEY(session_id,sequence))");
        }
    }
}
