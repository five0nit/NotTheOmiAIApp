package app.nottheomi.ai;

import java.io.IOException;

/** CPU-only, offline JNI. The owner serializes open/transcribe/close off the UI thread. */
public final class WhisperNative {
    static { System.loadLibrary("nottheomi-whisper"); }

    private WhisperNative() { }

    /** Opens a previously hash-verified private model file. Returns an opaque nonzero handle. */
    public static native long openFile(String path) throws IOException;

    /**
     * Transcribes 16kHz mono signed PCM16, at most 480000 samples (30 seconds).
     * Zero-length, sub-100ms, near-silent or cancelled input returns an empty string.
     * Threads are clamped to 1..4. This method does not modify the caller's array;
     * the caller must wipe it after return and must not mutate it during this call.
     * Native temporary raw PCM copies are wiped on success, error and cancellation.
     * No transcript, audio, or model path is logged by this adapter.
     */
    public static native String transcribe(long handle, short[] pcm, int threads) throws IOException;

    /**
     * Cross-thread safe, non-blocking with respect to inference. Cancellation is
     * sticky: discard/close this handle and open a new one to resume. Unknown or
     * already-closed handles are harmless. Model loading itself is synchronous.
     */
    public static native void cancel(long handle);

    /** Cancels, waits for an active call, then releases ownership. Idempotent, including zero. */
    public static native void close(long handle);
}
