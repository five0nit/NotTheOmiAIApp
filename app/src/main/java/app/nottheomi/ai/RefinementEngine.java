package app.nottheomi.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Bounded, restartable PCM16 refinement. Neither plaintext files nor whole-session buffers. */
final class RefinementEngine {
    static final int WINDOW_SAMPLES = 30 * 16000;
    interface Consumer { void accept(byte[] pcm) throws Exception; }
    interface Source { void stream(Consumer consumer) throws Exception; }
    interface Decoder { String transcribe(short[] samples) throws Exception; }
    interface Sink {
        void commit(long expectedOffset, long nextOffset, String text) throws Exception;
        void complete() throws Exception;
    }

    static final class Paused extends InterruptedIOException {
        Paused() { super("Saved transcript refinement paused"); }
    }

    private RefinementEngine() { }

    static void run(long totalBytes, long offsetBytes, Source source, Decoder decoder,
                    Sink sink, BooleanSupplier cancelled) throws Exception {
        if (totalBytes <= 0 || offsetBytes < 0 || offsetBytes > totalBytes
                || ((totalBytes | offsetBytes) & 1) != 0) {
            throw new IOException("Invalid saved PCM checkpoint");
        }
        new Pass(totalBytes, offsetBytes, decoder, sink, cancelled).run(source);
    }

    private static final class Pass {
        final long total, resume;
        final Decoder decoder;
        final Sink sink;
        final BooleanSupplier cancelled;
        final short[] window = new short[WINDOW_SAMPLES];
        long seen, committed;
        int count;

        Pass(long total, long resume, Decoder decoder, Sink sink, BooleanSupplier cancelled) {
            this.total = total; this.resume = resume; this.committed = resume;
            this.decoder = decoder; this.sink = sink; this.cancelled = cancelled;
        }

        void check() throws Paused {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Paused();
        }

        void run(Source source) throws Exception {
            try {
                check();
                source.stream(this::accept);
                check();
                if (seen != total) throw new IOException("Saved audio length differs from manifest");
                if (count > 0) flush();
                check();
                if (committed != total) throw new IOException("Refinement checkpoint incomplete");
                sink.complete();
            } finally { Arrays.fill(window, (short) 0); }
        }

        void accept(byte[] pcm) throws Exception {
            if (pcm == null) throw new IOException("Missing saved PCM chunk");
            try {
                check();
                if (pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > total - seen)
                    throw new IOException("Invalid saved PCM chunk");
                int start = (int) Math.min(pcm.length, Math.max(0L, resume - seen));
                seen += pcm.length;
                for (int i = start; i < pcm.length; i += 2) {
                    window[count++] = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                    if (count == window.length) flush();
                }
            } finally { Arrays.fill(pcm, (byte) 0); }
        }

        void flush() throws Exception {
            check();
            short[] samples = count == window.length ? window : Arrays.copyOf(window, count);
            int used = count;
            String text;
            try { text = decoder.transcribe(samples); }
            finally {
                Arrays.fill(samples, (short) 0);
                Arrays.fill(window, (short) 0);
                count = 0;
            }
            check(); // Never commit a result from preempted native work.
            if (text == null) throw new IOException("Refinement returned no result");
            long next = committed + used * 2L;
            sink.commit(committed, next, text.trim());
            committed = next;
        }
    }
}
