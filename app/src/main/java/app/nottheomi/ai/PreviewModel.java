package app.nottheomi.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

/** Narrow owner of the pinned Vosk preview model; no native cancellation API. */
public final class PreviewModel implements AutoCloseable {
    final Object nativeLifetime = new Object();
    private final Model model;
    private volatile boolean cancelled;
    private boolean closed;
    private int recognizers;

    public PreviewModel(String directory) throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS); // -1: no verbose/transcript diagnostics.
        model = new Model(directory);
    }

    /** Sticky Java-only fence. Never blocks, closes, or interrupts native inference. */
    public void cancel() { cancelled = true; }

    void checkUsable() throws IOException {
        // Caller holds nativeLifetime; cancel deliberately does not take it.
        if (closed) throw new IllegalStateException("Preview model closed");
        if (cancelled) throw new InterruptedIOException("Preview cancelled");
    }

    org.vosk.Recognizer newRecognizer(float sampleRate) throws IOException {
        synchronized (nativeLifetime) {
            checkUsable();
            org.vosk.Recognizer recognizer = new org.vosk.Recognizer(model, sampleRate);
            recognizers++;
            return recognizer;
        }
    }

    void recognizerClosed() { recognizers--; } // Caller holds nativeLifetime.

    @Override public void close() {
        synchronized (nativeLifetime) {
            if (closed) return;
            // Do not release a model before its recognizers, even between calls.
            if (recognizers != 0) throw new IllegalStateException("Preview recognizer still owned");
            closed = true;
            model.close();
        }
    }
}
