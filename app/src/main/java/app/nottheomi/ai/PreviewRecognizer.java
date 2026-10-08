package app.nottheomi.ai;

import java.io.IOException;
import org.vosk.Recognizer;

/** Serialized Vosk calls; cancellation fences results but cannot abort native work. */
public final class PreviewRecognizer implements AutoCloseable {
    private final PreviewModel model;
    private final Recognizer recognizer;
    private boolean closed;

    public PreviewRecognizer(PreviewModel model, float sampleRate) throws IOException {
        if (model == null || sampleRate != 16000f) throw new IllegalArgumentException("Expected 16 kHz preview PCM");
        this.model = model;
        recognizer = model.newRecognizer(sampleRate);
    }

    private void checkUsable() throws IOException {
        if (closed) throw new IllegalStateException("Preview recognizer closed");
        model.checkUsable();
    }

    public boolean acceptWaveForm(byte[] pcm, int length) throws IOException {
        if (pcm == null || length < 0 || length > pcm.length || (length & 1) != 0) {
            throw new IllegalArgumentException("Expected complete PCM16 samples");
        }
        synchronized (model.nativeLifetime) {
            checkUsable();
            boolean endpoint = length != 0 && recognizer.acceptWaveForm(pcm, length);
            checkUsable(); // Drop a result from a call that outlived cancellation.
            return endpoint;
        }
    }

    public String getResult() throws IOException { return result(0); }
    public String getPartialResult() throws IOException { return result(1); }
    public String getFinalResult() throws IOException { return result(2); }

    private String result(int kind) throws IOException {
        synchronized (model.nativeLifetime) {
            checkUsable();
            String json = kind == 0 ? recognizer.getResult()
                    : kind == 1 ? recognizer.getPartialResult() : recognizer.getFinalResult();
            checkUsable();
            return json;
        }
    }

    public void reset() throws IOException {
        synchronized (model.nativeLifetime) {
            checkUsable();
            recognizer.reset();
            checkUsable();
        }
    }

    @Override public void close() {
        synchronized (model.nativeLifetime) {
            if (closed) return;
            closed = true;
            try { recognizer.close(); }
            finally { model.recognizerClosed(); }
        }
    }
}
