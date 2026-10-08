import app.nottheomi.ai.WhisperNative;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeSafetyTest {
    private static native void setMode(int mode);
    private static native boolean entered();
    private static native int live();
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("check " + checks);
    }
    static void io(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("test-model"));
        }
    }
    static long open() throws IOException { return WhisperNative.openFile("test-model-😀.bin"); }
    static short[] audio(int count) { short[] out = new short[count]; Arrays.fill(out, (short) 8192); return out; }
    static void waitEntered() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!entered() && System.nanoTime() < deadline) Thread.sleep(1);
        check(entered());
    }
    public static void main(String[] args) throws Exception {
        io(() -> WhisperNative.openFile(null));
        io(() -> WhisperNative.openFile(""));
        io(() -> WhisperNative.openFile("x".repeat(4097)));
        io(() -> WhisperNative.openFile("bad\u0000path"));
        io(() -> WhisperNative.openFile("bad\ud800path"));
        io(() -> WhisperNative.openFile("bad\udc00path"));
        io(() -> WhisperNative.openFile("bad-model"));
        io(() -> WhisperNative.openFile("oom"));
        check(live() == 0);
        long handle = open();
        check(handle > 0 && live() == 1);
        io(() -> WhisperNative.transcribe(0, audio(16000), 4));
        io(() -> WhisperNative.transcribe(Long.MAX_VALUE, audio(16000), 4));
        io(() -> WhisperNative.transcribe(handle, null, 4));
        io(() -> WhisperNative.transcribe(handle, audio(480001), 4));
        check(WhisperNative.transcribe(handle, new short[0], 4).isEmpty());
        check(WhisperNative.transcribe(handle, audio(1599), 4).isEmpty());
        check(WhisperNative.transcribe(handle, new short[480000], 4).isEmpty());
        check(!entered());
        short[] pcm = audio(1600);
        short[] copy = pcm.clone();
        check(WhisperNative.transcribe(handle, pcm, Integer.MIN_VALUE).equals(" café 😀"));
        check(Arrays.equals(copy, pcm));
        check(WhisperNative.transcribe(handle, audio(480000), Integer.MAX_VALUE).equals(" café 😀"));
        for (int mode : new int[]{2, 3, 4}) {
            setMode(mode);
            io(() -> WhisperNative.transcribe(handle, pcm, 4));
        }
        setMode(5);
        check(WhisperNative.transcribe(handle, pcm, 4).equals("bad \ufffd\ufffd"));
        for (int mode : new int[]{6, 7}) {
            setMode(mode);
            check(WhisperNative.transcribe(handle, pcm, 4).isEmpty());
        }
        WhisperNative.cancel(handle);
        setMode(0);
        check(WhisperNative.transcribe(handle, pcm, 4).isEmpty());
        check(!entered());
        WhisperNative.close(handle);
        WhisperNative.close(handle);
        WhisperNative.cancel(handle);
        WhisperNative.close(0);
        WhisperNative.cancel(Long.MAX_VALUE);
        check(live() == 0);
        io(() -> WhisperNative.transcribe(handle, pcm, 4));
        for (boolean close : new boolean[]{false, true}) {
            long current = open();
            check(current != handle);
            setMode(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    if (!WhisperNative.transcribe(current, audio(16000), 4).isEmpty())
                        failure.set(new AssertionError("Cancelled transcript published"));
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            waitEntered();
            if (close) WhisperNative.close(current); else WhisperNative.cancel(current);
            worker.join(5000);
            check(!worker.isAlive());
            check(failure.get() == null);
            WhisperNative.close(current);
            check(live() == 0);
        }
        System.out.println("JNI mock-backend safety checks passed: " + checks);
    }
}
