package app.nottheomi.ai;

import java.io.IOException;
import java.util.Arrays;

/** Worker-confined PCM16 adapter. Eight-second batches, not word-by-word streaming. */
public final class WhisperRecognizer implements AutoCloseable {
    static final int BATCH_SAMPLES = 8 * 16000;
    private final WhisperModel model;
    private final short[] buffered = new short[BATCH_SAMPLES];
    private int count;
    private String result = "";
    private boolean closed;

    public WhisperRecognizer(WhisperModel model, float sampleRate) throws IOException {
        if (model == null || sampleRate != 16000f) throw new IOException("Whisper requires 16 kHz mono PCM");
        this.model = model;
    }

    public boolean acceptWaveForm(byte[] pcm, int length) throws IOException {
        ensureOpen();
        if (pcm == null || length < 0 || length > pcm.length || (length & 1) != 0
                || length > 30 * 16000 * 2) throw new IOException("Invalid Whisper PCM boundary");
        for (int i = 0; i < length; i += 2) {
            buffered[count++] = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            if (count == BATCH_SAMPLES) flush();
        }
        return !result.isEmpty();
    }

    public String getResult() throws IOException {
        ensureOpen();
        String text = result;
        result = "";
        return "{\"text\":" + quote(text) + "}";
    }

    public String getPartialResult() throws IOException {
        ensureOpen();
        return "{\"partial\":\"\"}";
    }

    public String getFinalResult() throws IOException {
        ensureOpen();
        if (count > 0) flush();
        return getResult();
    }

    private void flush() throws IOException {
        short[] samples = Arrays.copyOf(buffered, count);
        Arrays.fill(buffered, (short) 0);
        count = 0;
        try {
            String text = model.transcribe(samples);
            if (!text.isEmpty()) result = result.isEmpty() ? text : result + " " + text;
        } finally {
            Arrays.fill(samples, (short) 0);
        }
    }

    public void reset() throws IOException {
        ensureOpen();
        Arrays.fill(buffered, (short) 0);
        count = 0;
        result = "";
    }

    @Override public void close() {
        Arrays.fill(buffered, (short) 0);
        count = 0;
        result = "";
        closed = true;
        // Model has a distinct owner and can serve the next discontinuity segment.
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Whisper recognizer closed");
    }

    private static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') json.append('\\').append(c);
            else if (c < 32) {
                json.append("\\u00").append(Character.forDigit((c >>> 4) & 15, 16))
                        .append(Character.forDigit(c & 15, 16));
            } else json.append(c);
        }
        return json.append('"').toString();
    }
}
