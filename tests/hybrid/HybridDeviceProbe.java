package app.nottheomi.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Public-fixture shell probe only: no Context, installed app, microphone or private files. */
public final class HybridDeviceProbe {
    private static final int RATE = 16000, FRAME_BYTES = 3200, MAX_BYTES = 11 * RATE * 2;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("preview directory, Whisper model, public JFK WAV required");
        byte[] pcm = readPublicWave(Paths.get(args[2]));
        JSONObject proof = new JSONObject();
        try {
            proof.put("audio_seconds", pcm.length / (RATE * 2.0));
            proof.put("audio_bytes", pcm.length);
            proof.put("frame_source_seconds", 0.1);
            preview(args[0], pcm, proof); // Both native Vosk owners close before Whisper opens.
            refine(args[1], pcm, proof);
            proof.put("result", "PASS_HYBRID_DEVICE_PUBLIC_FIXTURE");
            System.out.println(proof.toString());
        } finally { Arrays.fill(pcm, (byte) 0); }
    }

    static byte[] readPublicWave(Path file) throws Exception {
        long length = Files.size(file);
        if (length < 44 || length > 2 * 1024 * 1024) throw new AssertionError("Public WAV size out of bounds");
        byte[] wave = Files.readAllBytes(file);
        try {
            ByteBuffer b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157
                    || Integer.toUnsignedLong(b.getInt(4)) + 8 != wave.length)
                throw new AssertionError("Complete RIFF WAVE required");
            int data = -1, bytes = 0, p = 12;
            boolean format = false;
            while (p < wave.length) {
                if (wave.length - p < 8) throw new AssertionError("Truncated WAV chunk header");
                int tag = b.getInt(p);
                long size = Integer.toUnsignedLong(b.getInt(p + 4));
                long end = p + 8L + size, next = end + (size & 1);
                if (next > wave.length) throw new AssertionError("Truncated WAV chunk or padding");
                if (tag == 0x20746d66) {
                    if (format || size < 16 || b.getShort(p + 8) != 1 || b.getShort(p + 10) != 1
                            || b.getInt(p + 12) != RATE || b.getInt(p + 16) != RATE * 2
                            || b.getShort(p + 20) != 2 || b.getShort(p + 22) != 16)
                        throw new AssertionError("Expected one 16kHz mono PCM16 format");
                    format = true;
                } else if (tag == 0x61746164) {
                    if (data != -1 || size == 0 || (size & 1) != 0) throw new AssertionError("Invalid WAV data");
                    data = p + 8;
                    bytes = (int) size;
                }
                p = (int) next;
            }
            if (!format || data < 0) throw new AssertionError("Missing WAV format/data");
            return Arrays.copyOfRange(wave, data, data + Math.min(bytes, MAX_BYTES));
        } finally { Arrays.fill(wave, (byte) 0); }
    }

    private static void preview(String path, byte[] pcm, JSONObject proof) throws Exception {
        long began = System.nanoTime(), loaded, ready, ended, inference = 0, closeAt;
        long firstWall = -1;
        double firstSource = -1;
        int nonempty = 0, changes = 0;
        String previous = "";
        Set<String> unique = new HashSet<>();
        StringBuilder finalText = new StringBuilder();
        // Resource ordering is intentional: recognizer closes before its model.
        try (PreviewModel model = new PreviewModel(path)) {
            loaded = System.nanoTime();
            try (PreviewRecognizer recognizer = new PreviewRecognizer(model, RATE)) {
                ready = System.nanoTime();
                for (int p = 0; p < pcm.length; p += FRAME_BYTES) {
                    int end = Math.min(p + FRAME_BYTES, pcm.length);
                    // Simulate arrival of the complete frame. Inference consumes this budget;
                    // never add a fixed sleep after inference or silently feed faster than real time.
                    sleepUntil(ready + (long) end * 1_000_000_000L / (RATE * 2));
                    byte[] frame = Arrays.copyOfRange(pcm, p, end);
                    boolean endpoint;
                    String result;
                    long call = System.nanoTime();
                    try {
                        endpoint = recognizer.acceptWaveForm(frame, frame.length);
                        result = endpoint ? recognizer.getResult() : recognizer.getPartialResult();
                    } finally { inference += System.nanoTime() - call; Arrays.fill(frame, (byte) 0); }
                    String text = new JSONObject(result).optString(endpoint ? "text" : "partial").trim();
                    if (endpoint) {
                        if (!text.isEmpty()) finalText.append(text).append(' ');
                        previous = "";
                    } else if (!text.isEmpty()) {
                        nonempty++;
                        unique.add(text);
                        if (!text.equals(previous)) changes++;
                        previous = text;
                        if (firstWall < 0) {
                            firstWall = System.nanoTime() - loaded;
                            firstSource = end / (RATE * 2.0);
                        }
                    } else { previous = ""; }
                }
                long call = System.nanoTime();
                String result = recognizer.getFinalResult();
                inference += System.nanoTime() - call;
                finalText.append(new JSONObject(result).optString("text"));
                closeAt = System.nanoTime();
            }
        }
        ended = System.nanoTime();
        String text = finalText.toString().trim();
        if (firstSource < 0 || firstSource >= 8.0) throw new AssertionError("No nonempty preview before 8 source seconds");
        if (!normalize(text).contains("country")) throw new AssertionError("Public Vosk JFK final missing country");
        double audio = pcm.length / (RATE * 2.0);
        proof.put("vosk_public_fixture_text", text);
        proof.put("vosk_model_load_seconds", seconds(loaded - began));
        proof.put("vosk_recognizer_load_seconds", seconds(ready - loaded));
        proof.put("vosk_first_partial_wall_seconds_after_model_load", seconds(firstWall));
        proof.put("vosk_first_partial_source_seconds", firstSource);
        proof.put("vosk_nonempty_partial_updates", nonempty);
        proof.put("vosk_distinct_partial_updates", changes);
        proof.put("vosk_unique_partial_strings", unique.size());
        proof.put("vosk_inference_seconds", seconds(inference));
        proof.put("vosk_inference_real_time_factor", seconds(inference) / audio);
        proof.put("vosk_close_seconds", seconds(ended - closeAt));
        proof.put("vosk_live_native_total_seconds", seconds(ready - began + inference + ended - closeAt));
        proof.put("vosk_live_total_seconds_including_pacing", seconds(ended - began));
        proof.put("vosk_closed_before_whisper", true);
    }

    private static void refine(String path, byte[] pcm, JSONObject proof) throws Exception {
        long began = System.nanoTime(), loaded, engineAt, engineEnd;
        long[] compute = {0}, committed = {0};
        int[] calls = {0}, commits = {0}, completed = {0};
        StringBuilder text = new StringBuilder();
        try (WhisperModel model = new WhisperModel(path)) {
            loaded = System.nanoTime();
            engineAt = System.nanoTime();
            RefinementEngine.run(pcm.length, 0, consumer -> {
                for (int p = 0; p < pcm.length; p += FRAME_BYTES) {
                    byte[] frame = Arrays.copyOfRange(pcm, p, Math.min(p + FRAME_BYTES, pcm.length));
                    try { consumer.accept(frame); }
                    finally { Arrays.fill(frame, (byte) 0); }
                }
            }, samples -> {
                long call = System.nanoTime();
                try { calls[0]++; return model.transcribe(samples); }
                finally { compute[0] += System.nanoTime() - call; }
            }, new RefinementEngine.Sink() {
                @Override public void commit(long expected, long next, String value) {
                    if (completed[0] != 0 || expected != committed[0] || next <= expected
                            || next > pcm.length || (next & 1) != 0)
                        throw new AssertionError("Invalid refinement checkpoint");
                    committed[0] = next;
                    commits[0]++;
                    text.append(value).append(' ');
                }
                @Override public void complete() {
                    if (completed[0] != 0 || committed[0] != pcm.length)
                        throw new AssertionError("Premature or duplicate completion");
                    completed[0]++;
                }
            }, () -> false);
            engineEnd = System.nanoTime();
        }
        if (completed[0] != 1 || commits[0] != 1 || calls[0] != 1 || committed[0] != pcm.length)
            throw new AssertionError("Refinement engine did not finish the complete bounded fixture");
        String finalText = text.toString().trim();
        if (!normalize(finalText).contains("ask not what your country"))
            throw new AssertionError("Public Whisper JFK phrase missing");
        proof.put("whisper_public_fixture_text", finalText);
        proof.put("whisper_model_load_seconds", seconds(loaded - began));
        proof.put("whisper_compute_seconds", seconds(compute[0]));
        proof.put("whisper_compute_real_time_factor", seconds(compute[0]) / (pcm.length / (RATE * 2.0)));
        proof.put("whisper_refinement_engine_seconds", seconds(engineEnd - engineAt));
        proof.put("whisper_total_seconds_including_load_close", seconds(System.nanoTime() - began));
        proof.put("refinement_committed_bytes", committed[0]);
        proof.put("refinement_commits", commits[0]);
        proof.put("refinement_completed", completed[0] == 1);
        proof.put("scope", "public fixture; real production wrappers and refinement engine; in-memory sink, not app job/storage lifecycle");
    }

    private static void sleepUntil(long deadline) throws InterruptedException {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0)
            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
    }
    private static double seconds(long nanos) { return nanos / 1e9; }
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll(" +", " ");
    }
}
