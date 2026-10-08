package app.nottheomi.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Shell-only public-audio probe. Does not access the installed app, mic, BLE or private archive. */
public final class WhisperDeviceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("model path, public JFK wav path required");
        byte[] wave = Files.readAllBytes(Paths.get(args[1]));
        ByteBuffer b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x46464952 || b.getInt(8) != 0x45564157) throw new AssertionError("RIFF WAV required");
        int offset = 0, bytes = 0;
        boolean format = false;
        for (int p = 12; p + 8 <= wave.length;) {
            int tag = b.getInt(p), size = b.getInt(p + 4);
            if (size < 0 || p + 8L + size > wave.length) throw new AssertionError("Malformed public WAV");
            if (tag == 0x20746d66) format = size >= 16 && b.getShort(p + 8) == 1 && b.getShort(p + 10) == 1
                    && b.getInt(p + 12) == 16000 && b.getShort(p + 22) == 16;
            if (tag == 0x61746164) { offset = p + 8; bytes = size; }
            p += 8 + size + (size & 1);
        }
        if (!format || bytes == 0 || bytes % 2 != 0) throw new AssertionError("Expected 16kHz mono PCM16");
        byte[] pcmBytes = Arrays.copyOfRange(wave, offset, offset + bytes);
        short[] pcm = new short[bytes / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = b.getShort(offset + i * 2);
        Arrays.fill(wave, (byte) 0);
        long began = System.nanoTime(), handle = WhisperNative.openFile(args[0]), loaded = System.nanoTime();
        if (handle == 0) throw new AssertionError("Native model load failed");
        JSONObject proof = new JSONObject();
        try {
            String text = WhisperNative.transcribe(handle, pcm, 4).trim();
            long inferred = System.nanoTime();
            requirePhrase(text);
            if (!WhisperNative.transcribe(handle, new short[16000], 4).isEmpty()) throw new AssertionError("Silence hallucination");
            if (!WhisperNative.transcribe(handle, new short[0], 4).isEmpty()) throw new AssertionError("Empty input");
            boolean oversizedRejected = false;
            try { WhisperNative.transcribe(handle, new short[480001], 4); }
            catch (java.io.IOException expected) { oversizedRejected = true; }
            if (!oversizedRejected) throw new AssertionError("Oversized input accepted");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread inference = new Thread(() -> {
                try {
                    started.countDown();
                    if (!WhisperNative.transcribe(handle, pcm, 4).isEmpty()) throw new AssertionError("Cancelled call returned transcript");
                } catch (Throwable e) { failure.set(e); }
            }, "public-cancel-probe");
            inference.start(); started.await(); Thread.sleep(250);
            if (!inference.isAlive()) throw new AssertionError("No active inference at cancellation");
            long cancelAt = System.nanoTime(); WhisperNative.cancel(handle); inference.join(20000);
            if (inference.isAlive()) throw new AssertionError("Native cancellation exceeded 20 seconds");
            if (failure.get() != null) throw new AssertionError("Native cancellation failed", failure.get());
            if (!WhisperNative.transcribe(handle, pcm, 4).isEmpty()) throw new AssertionError("Cancellation not sticky");
            proof.put("public_fixture_text", text);
            proof.put("load_seconds", (loaded - began) / 1e9);
            proof.put("inference_seconds", (inferred - loaded) / 1e9);
            proof.put("audio_seconds", pcm.length / 16000.0);
            proof.put("active_cancel_seconds", (System.nanoTime() - cancelAt) / 1e9);
            proof.put("native_silence_empty_oversize_cancel", "passed");
        } finally { WhisperNative.close(handle); Arrays.fill(pcm, (short) 0); }
        long streamingAt = System.nanoTime(); StringBuilder text = new StringBuilder();
        try (WhisperModel model = new WhisperModel(args[0]); WhisperRecognizer recognizer = new WhisperRecognizer(model, 16000)) {
            for (int p = 0; p < pcmBytes.length; p += 1280) {
                byte[] frame = Arrays.copyOfRange(pcmBytes, p, Math.min(p + 1280, pcmBytes.length));
                if (recognizer.acceptWaveForm(frame, frame.length)) text.append(new JSONObject(recognizer.getResult()).optString("text")).append(' ');
                Arrays.fill(frame, (byte) 0);
            }
            text.append(new JSONObject(recognizer.getFinalResult()).optString("text"));
            requirePhrase(text.toString());
        } finally { Arrays.fill(pcmBytes, (byte) 0); }
        proof.put("streaming_fixture_text", text.toString().trim());
        proof.put("streaming_seconds_including_load", (System.nanoTime() - streamingAt) / 1e9);
        proof.put("result", "PASS_WHISPER_DEVICE_PUBLIC_FIXTURE");
        System.out.println(proof.toString());
    }
    private static void requirePhrase(String text) {
        if (!text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll(" +", " ").contains("ask not what your country"))
            throw new AssertionError("Public JFK phrase missing: " + text);
    }
}
