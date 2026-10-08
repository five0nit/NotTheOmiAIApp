package app.nottheomi.ai;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.PowerManager;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Runs unchanged CaptureService against the controlled doubles in run_host_checks.py. */
public final class CaptureServiceHostTest {
    private static final long TIMEOUT_MS = 4000;
    private static final AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    private static CaptureService service;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one scenario required");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            backgroundFailure.compareAndSet(null, error);
            error.printStackTrace();
        });
        service = new CaptureService();
        service.onCreate();
        try {
            switch (args[0]) {
                case "permission" -> permission();
                case "omi_busy" -> omiBusy();
                case "null_intent" -> nullIntent();
                case "prepare_cancel" -> prepareCancel();
                case "native_warm_cancel" -> nativeWarmCancel();
                case "mic_setup_cancel" -> micSetupCancel();
                case "archive_and_final" -> archiveAndFinal();
                case "active_until_saved" -> activeUntilSaved();
                case "native_failure" -> nativeFailure();
                case "slow_asr" -> slowAsr();
                case "storage_failure" -> storageFailure();
                case "read_failure" -> readFailure();
                case "text_failure" -> textFailure();
                case "streaming_partial" -> streamingPartial();
                case "partial_cancel" -> partialCancel();
                case "start_failure" -> startFailure();
                case "stop_accept_timeout" -> stopTimeout(false);
                case "stop_final_timeout" -> stopTimeout(true);
                case "stop_storage_blocked" -> stopStorageBlocked();
                default -> throw new IllegalArgumentException("unknown scenario: " + args[0]);
            }
        } finally {
            cleanup();
        }
        System.out.println("CaptureServiceHostTest PASS " + args[0] + ": " + assertions + " assertions");
    }

    private static void permission() throws Exception {
        Context.permission = -1;
        start();
        check(!CaptureService.active, "permission rejection remains inactive");
        check(CaptureService.state.contains("permission"), "permission rejection is visible");
        check(Service.foregroundStarts == 0, "no foreground session without permission");
        check(Service.selfStops == 1, "rejected service stopped");
        check(PreviewModelInstaller.entered.getCount() == 1, "no model preparation without permission");
        noRecording();
    }

    private static void omiBusy() {
        OmiCaptureService.active = true;
        try {
            start();
            check(!CaptureService.active, "phone capture rejected while Omi owns capture");
            check(Service.foregroundStarts == 0 && Service.selfStops == 1, "no second foreground capture owner");
            check(PreviewModelInstaller.entered.getCount() == 1 && PreviewModel.created == 0,
                    "busy rejection never prepares or loads a model");
            noRecording();
        } finally {
            OmiCaptureService.active = false;
        }
    }

    private static void nullIntent() throws Exception {
        check(service.onStartCommand(null, 0, 1) == Service.START_NOT_STICKY, "null restart is not sticky");
        check(service.onStartCommand(new Intent().setAction("unknown"), 0, 2)
                == Service.START_NOT_STICKY, "unknown action is not sticky");
        check(!CaptureService.active && Service.selfStops == 2, "unsolicited starts stopped");
        check(Service.foregroundStarts == 0, "no unsolicited foreground capture");
        noRecording();
    }

    private static void prepareCancel() throws Exception {
        PreviewModelInstaller.hold = true;
        start();
        await(PreviewModelInstaller.entered, "installer entered");
        check(CaptureService.active, "preparation owns lifecycle");
        duplicateStart();
        stop();
        finished();
        check(PreviewModel.created == 0, "cancelled extraction never loads native model");
        noRecording();
        noAudioTerminal();
    }

    private static void nativeWarmCancel() throws Exception {
        PreviewModel.gate = new CountDownLatch(1);
        start();
        await(PreviewModel.entered, "native model construction entered");
        duplicateStart();
        stop();
        check(CaptureService.active, "native warmup remains owned until cleanup");
        check(PreviewModel.closed == 0, "model not closed during construction");
        release(PreviewModel.gate);
        finished();
        check(PreviewModel.created == 1 && PreviewModel.closed == 1, "constructed model closed once");
        check(PreviewRecognizer.created == 0, "no recognizer after warmup cancellation");
        noRecording();
        noAudioTerminal();
    }

    private static void micSetupCancel() throws Exception {
        AudioRecord.constructorGate = new CountDownLatch(1);
        start();
        await(AudioRecord.constructed, "microphone construction entered");
        duplicateStart();
        stop();
        check(CaptureService.active, "microphone setup cancellation still owns resources");
        check(AudioRecord.starts == 0, "microphone not started while construction blocked");
        release(AudioRecord.constructorGate);
        finished();
        check(AudioRecord.releases == 1, "cancelled microphone object released");
        noRecording();
        noAudioTerminal();
    }

    private static void archiveAndFinal() throws Exception {
        startRecording();
        byte[] first = pcm(6400, 7);
        AudioRecord.push(first);
        until(() -> PreviewRecognizer.accepted == 2 && Recordings.text.size() == 2, "two endpoint commits");
        AudioRecord.readGate = new CountDownLatch(1);
        byte[] last = pcm(118, 19);
        AudioRecord.push(last);
        await(AudioRecord.readEntered, "partial PCM read in flight");
        stop();
        check(CaptureService.active, "stop waits for in-flight PCM read");
        release(AudioRecord.readGate);
        finished();
        check(Arrays.equals(archived(), concat(first, last)), "all ordered PCM including stop tail archived");
        check(PreviewRecognizer.accepted == Recordings.audio.size(), "every committed chunk reaches ASR");
        check(Recordings.text.equals(List.of("endpoint", "endpoint", "endpoint", "stop final")),
                "endpoints precede persisted stop-final transcript");
        check(PreviewRecognizer.finals == 1, "one stop-final result");
        check("saved".equals(Recordings.status), "successful archive marked saved");
        check("Stopped — saved on this phone".equals(CaptureService.state), "truthful saved terminal state");
    }

    private static void activeUntilSaved() throws Exception {
        PreviewRecognizer.finalGate = new CountDownLatch(1);
        Recordings.finishGate = new CountDownLatch(1);
        startRecording();
        AudioRecord.push(pcm(3200, 29));
        until(() -> Recordings.text.size() == 1, "endpoint persisted before stop");
        stop();
        await(PreviewRecognizer.finalEntered, "ASR final blocked");
        check(CaptureService.active && AudioRecord.releases == 1, "active while speech drains after mic release");
        check(Recordings.finished == 0 && Service.foregroundStops == 0, "no premature save or foreground teardown");
        duplicateStart();
        release(PreviewRecognizer.finalGate);
        await(Recordings.finishEntered, "archive finalization blocked");
        check(CaptureService.active && PowerManager.held == 1, "active and awake until archive finalized");
        check(PreviewRecognizer.closed == 1 && PreviewModel.closed == 1, "native resources closed before archive finalization");
        check(Recordings.text.equals(List.of("endpoint", "stop final")), "stop-final stored before finish");
        check(Recordings.finished == 0, "blocked finalization not counted as saved");
        duplicateStart();
        release(Recordings.finishGate);
        // Do not drain the main queue: finishing storage must not preempt its lifecycle callback.
        joinWorker();
        check(CaptureService.active && Service.foregroundStops == 0, "main-thread teardown owns final inactive transition");
        check(Recordings.finished == 1 && PowerManager.held == 0, "durability and wake release precede inactive");
        Handler.drain();
        check(!CaptureService.active && Service.foregroundStops == 1, "inactive only after foreground teardown");
        check("saved".equals(Recordings.status), "completed save status");
    }

    private static void nativeFailure() throws Exception {
        PreviewRecognizer.fail = true;
        startRecording();
        byte[] first = pcm(3200, 37);
        byte[] later = pcm(6400, 43);
        AudioRecord.push(first);
        until(() -> CaptureService.state.contains("offline speech failed"), "native fault surfaced");
        check(PreviewRecognizer.failures == 1, "native injected failure actually executed");
        check(CaptureService.active, "native ASR failure does not stop microphone archive");
        AudioRecord.push(later);
        until(() -> Recordings.total() == first.length + later.length, "archive continues without ASR");
        stop();
        finished();
        check(Arrays.equals(archived(), concat(first, later)), "ASR fault loses no archive PCM");
        check("audio_only".equals(Recordings.status), "native failure classified audio-only");
        check(CaptureService.state.contains("audio saved; offline speech failed"), "terminal state discloses incomplete speech");
        check(Recordings.text.isEmpty(), "failed native call invents no transcript");
    }

    private static void slowAsr() throws Exception {
        PreviewRecognizer.acceptGate = new CountDownLatch(1);
        startRecording();
        byte[] first = pcm(3200, 47);
        AudioRecord.push(first);
        await(PreviewRecognizer.acceptEntered, "speech acceptance blocked");
        byte[] backlog = pcm(3200 * 305, 53);
        AudioRecord.push(backlog);
        until(() -> Recordings.total() == first.length + backlog.length, "archive continues while ASR blocked");
        until(() -> CaptureService.state.contains("speech processing fell behind"), "bounded speech backlog warning");
        check(CaptureService.active && PreviewRecognizer.accepted == 0, "slow ASR cannot block microphone durability");
        stop();
        check(CaptureService.active, "stop waits for outstanding native call");
        release(PreviewRecognizer.acceptGate);
        finished();
        check(Arrays.equals(archived(), concat(first, backlog)), "overload retains every PCM byte in order");
        check(PreviewRecognizer.accepted == 301, "only in-flight plus bounded thirty-second/300-chunk Whisper backlog recognized");
        check(PreviewRecognizer.finals == 1, "overload drains queued speech and final result");
        check("audio_only".equals(Recordings.status), "speech overload not mislabeled complete transcript");
        check(CaptureService.state.contains("transcript may be incomplete"), "overload warning survives stop");
    }

    private static void storageFailure() throws Exception {
        Recordings.failAudioAt = 1;
        startRecording();
        byte[] bytes = pcm(9600, 61);
        AudioRecord.push(bytes);
        finished();
        check(Arrays.equals(archived(), Arrays.copyOf(bytes, 3200)), "only successful commit retained");
        check(AudioRecord.delivered == 6400, "capture stops at failed commit");
        check(PreviewRecognizer.accepted == 1, "failed archive chunk never enters ASR");
        check("error".equals(Recordings.status), "storage failure marked error");
        check(CaptureService.state.contains("Encrypted storage unavailable or full — earlier audio was retained"),
                "storage failure reports retained earlier audio");
    }

    private static void readFailure() throws Exception {
        startRecording();
        byte[] bytes = pcm(3200, 67);
        AudioRecord.push(bytes);
        until(() -> Recordings.total() == bytes.length, "first PCM archived");
        AudioRecord.readFailure = true;
        finished();
        check(Arrays.equals(archived(), bytes), "read failure preserves earlier audio");
        check("error".equals(Recordings.status), "microphone failure marked error");
        check(CaptureService.state.contains("Microphone read failed — captured audio was retained"), "read failure disclosed");
        check(PreviewRecognizer.accepted == 1 && PreviewRecognizer.finals == 1, "read failure still drains speech");
    }

    private static void textFailure() throws Exception {
        Recordings.failText = true;
        startRecording();
        byte[] bytes = pcm(3200, 71);
        AudioRecord.push(bytes);
        finished();
        check(PreviewRecognizer.accepted == 1 && Recordings.textFailures == 1, "transcript write failure actually injected");
        check(Arrays.equals(archived(), bytes), "transcript storage failure retains audio");
        check(Recordings.text.isEmpty(), "failed transcript is not falsely persisted");
        check("error".equals(Recordings.status), "transcript storage failure marked error");
        check(CaptureService.state.contains("Encrypted transcript storage unavailable or full; captured audio was retained"),
                "transcript storage fault disclosed");
    }

    private static void streamingPartial() throws Exception {
        PreviewRecognizer.nonEndpointAccepts = 2;
        startRecording();
        byte[] first = pcm(3200, 73), second = pcm(3200, 79), third = pcm(3200, 83);
        AudioRecord.push(first);
        until(() -> CaptureService.partial.equals("preview 1"), "first nonempty Vosk partial published");
        check(CaptureService.display.snapshot().partial.equals("preview 1") && Recordings.text.isEmpty(),
                "partial is visible before endpoint and never persisted as final text");
        AudioRecord.push(second);
        until(() -> CaptureService.partial.equals("preview 2"), "changing partial replaces earlier preview");
        AudioRecord.push(third);
        until(() -> Recordings.text.size() == 1 && CaptureService.partial.isEmpty(), "endpoint clears preview");
        stop(); finished();
        check(PreviewRecognizer.partials == 2 && PreviewRecognizer.accepted == 3 && PreviewRecognizer.finals == 1,
                "actual non-endpoint branch polls partials; endpoint and Stop still drain");
        check(Recordings.text.equals(List.of("endpoint", "stop final")), "only endpoint and final JSON persisted");
        check(Arrays.equals(archived(), concat(concat(first, second), third)), "all preview PCM archived byte-exactly");
        check("saved".equals(Recordings.status), "preview capture saves normally");
    }

    private static void partialCancel() throws Exception {
        PreviewRecognizer.nonEndpointAccepts = 2;
        startRecording(); AudioRecord.push(pcm(3200, 73));
        until(() -> CaptureService.partial.equals("preview 1"), "preview before Stop");
        PreviewRecognizer.partialGate = new CountDownLatch(1);
        PreviewRecognizer.partialEntered = new CountDownLatch(1);
        AudioRecord.push(pcm(3200, 79));
        await(PreviewRecognizer.partialEntered, "second partial call blocked");
        stop();
        check(CaptureService.partial.isEmpty() && CaptureService.display.snapshot().partial.isEmpty(), "Stop clears partial immediately");
        check(CaptureService.active && PreviewRecognizer.closed == 0 && RefinementJobService.schedules == 0,
                "blocked preview retains ownership and pauses refinement until teardown");
        PreviewRecognizer.finalGate = new CountDownLatch(1);
        release(PreviewRecognizer.partialGate);
        await(PreviewRecognizer.finalEntered, "late partial returned before final drain");
        check(PreviewRecognizer.partials == 2 && CaptureService.partial.isEmpty()
                && CaptureService.display.snapshot().partial.isEmpty(), "late partial cannot revive cancelled display");
        release(PreviewRecognizer.finalGate); finished();
        check(Recordings.text.equals(List.of("stop final")), "normal Stop still preserves final text");
    }

    private static void startFailure() throws Exception {
        Service.failForeground = true; start();
        check(!CaptureService.active && PreviewModel.created == 0, "failed foreground start allocates no native owner");
        check(RefinementJobService.pauses == 1 && RefinementJobService.schedules == 1, "failed start reschedules paused refinement");
    }

    private static void stopTimeout(boolean finalCall) throws Exception {
        if (finalCall) PreviewRecognizer.finalGate = new CountDownLatch(1);
        else PreviewRecognizer.acceptGate = new CountDownLatch(1);
        startRecording();
        byte[] bytes = pcm(3200, 83);
        AudioRecord.push(bytes);
        await(PreviewRecognizer.acceptEntered, "native accept entered");
        if (finalCall) until(() -> Recordings.text.size() == 1, "endpoint before final stall");
        stop();
        check(AudioRecord.stops == 1, "Stop immediately stops microphone before native drain");
        if (finalCall) await(PreviewRecognizer.finalEntered, "final inference blocked");
        Field limit = CaptureService.class.getDeclaredField("ASR_STOP_DRAIN_MS");
        limit.setAccessible(true);
        check(limit.getLong(null) == 30000L, "production final drain has a thirty-second budget");
        Field deadline = CaptureService.class.getDeclaredField("stopDeadlineNanos");
        deadline.setAccessible(true);
        long remaining = deadline.getLong(service) - System.nanoTime();
        check(remaining > TimeUnit.SECONDS.toNanos(25) && remaining <= TimeUnit.SECONDS.toNanos(30),
                "Stop grants its bounded drain before cancellation");
        check(PreviewModel.cancelled == 0, "normal Stop does not prematurely cancel tail inference");
        // Expire the instance deadline instead of waiting thirty wall-clock seconds.
        deadline.setLong(service, System.nanoTime() - 1);
        until(() -> PreviewModel.cancelled == 1, "expired drain requests Java-only output cancellation");
        check(CaptureService.active && PreviewModel.closed == 0 && PreviewRecognizer.closed == 0,
                "cancellation does not close native state or clear active before owner exit");
        check(Recordings.finished == 0 && Service.foregroundStops == 0, "archive and foreground remain owned");
        duplicateStart();
        release(PreviewRecognizer.acceptGate);
        release(PreviewRecognizer.finalGate);
        finished();
        check(Arrays.equals(archived(), bytes), "timed-out inference preserves exact archived PCM");
        check(Recordings.text.equals(finalCall ? List.of("endpoint") : List.of()),
                "cancelled late native result never becomes a saved transcript");
        check("audio_only".equals(Recordings.status) && CaptureService.state.contains("drain timed out"),
                "timeout truthfully reports incomplete transcript");
        check(PreviewModel.cancelled == 1, "one sticky cancellation per timed-out owner");
    }

    private static void stopStorageBlocked() throws Exception {
        Recordings.audioGate = new CountDownLatch(1);
        startRecording();
        byte[] bytes = pcm(3200, 89);
        AudioRecord.push(bytes);
        await(Recordings.audioEntered, "encrypted audio commit blocked");
        stop();
        check(AudioRecord.stops == 1 && AudioRecord.releases == 0,
                "Stop halts source without interrupting blocked archive ownership");
        check(CaptureService.active && Recordings.finished == 0, "archive owner stays active during commit");
        release(Recordings.audioGate);
        finished();
        check(Arrays.equals(archived(), bytes) && "saved".equals(Recordings.status),
                "successful in-flight commit and normal speech final survive Stop");
        check(PreviewModel.cancelled == 0, "normal drain never cancels native model");
    }

    private static void start() {
        check(service.onStartCommand(new Intent().setAction(CaptureService.ACTION_START), 0, 10)
                == Service.START_NOT_STICKY, "explicit start is nonsticky");
    }

    private static void startRecording() throws Exception {
        start();
        until(() -> CaptureService.state.equals("Recording — offline transcription"), "microphone and speech ready");
        check(CaptureService.active && AudioRecord.starts == 1 && Recordings.created == 1, "one active microphone/session");
        duplicateStart();
    }

    private static void duplicateStart() {
        int models = PreviewModel.created;
        int sessions = Recordings.created;
        int microphones = AudioRecord.starts;
        start();
        check(Service.foregroundStarts == 1, "duplicate start creates no foreground worker");
        check(PreviewModel.created == models && Recordings.created == sessions && AudioRecord.starts == microphones,
                "duplicate start allocates no resources");
    }

    private static void stop() {
        check(service.onStartCommand(new Intent().setAction(CaptureService.ACTION_STOP), 0, 11)
                == Service.START_NOT_STICKY, "stop is nonsticky");
    }

    private static void finished() throws Exception {
        joinWorker();
        Handler.drain();
        check(!CaptureService.active, "capture fully stopped");
        check(Service.foregroundStops == 1, "foreground stopped once");
        check(Recordings.finished == Recordings.created, "every allocated session finalized once");
    }

    private static void noRecording() {
        check(AudioRecord.starts == 0 && Recordings.created == 0, "no microphone start or session created");
        check(Recordings.audio.isEmpty() && Recordings.text.isEmpty(), "no audio or text persisted");
    }

    private static void noAudioTerminal() {
        check("Stopped — no audio recorded".equals(CaptureService.state), "cancellation truthfully reports no audio");
    }

    private static void joinWorker() throws Exception {
        Field field = CaptureService.class.getDeclaredField("worker");
        field.setAccessible(true);
        Thread worker = (Thread) field.get(service);
        if (worker != null) {
            worker.join(TIMEOUT_MS);
            check(!worker.isAlive(), "capture worker terminated within deadline");
        }
        checkBackground();
    }

    private static void cleanup() throws Exception {
        // Every assertion/fault path must release native/storage doubles before joining.
        service.onDestroy();
        PreviewModelInstaller.hold = false;
        release(PreviewModel.gate);
        release(AudioRecord.constructorGate);
        release(AudioRecord.readGate);
        release(PreviewRecognizer.partialGate);
        release(PreviewRecognizer.acceptGate);
        release(PreviewRecognizer.finalGate);
        release(Recordings.audioGate);
        release(Recordings.finishGate);
        joinWorker();
        Handler.drain();
        check(!CaptureService.active, "cleanup leaves no active service");
        check(PowerManager.held == 0, "wake lock released");
        check(PreviewModel.created == PreviewModel.closed, "all models closed exactly once");
        check(PreviewRecognizer.created == PreviewRecognizer.closed, "all recognizers closed exactly once");
        check(RefinementJobService.pauses == RefinementJobService.schedules,
                "every acquired capture owner resumes refinement only after teardown");
        check(RefinementJobService.pauses == (Service.foregroundStarts > 0 || Service.failForeground ? 1 : 0),
                "exactly one pause/schedule per owner, none on rejection or duplicate Start");
        check(AudioRecord.instances == AudioRecord.releases, "all microphone objects released exactly once");
        check(CaptureService.level == 0 && CaptureService.partial.isEmpty(), "transient microphone UI cleared");
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            check(!thread.isAlive() || (!thread.getName().equals("phone-capture")
                    && !thread.getName().equals("offline-speech")), "no surviving capture/speech thread");
        }
        checkBackground();
    }

    private static void await(CountDownLatch latch, String description) throws Exception {
        check(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), description);
        checkBackground();
    }

    private static void until(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            checkBackground();
            Thread.sleep(2);
        }
        check(condition.getAsBoolean(), description + " [state=" + CaptureService.state + "]");
    }

    private static void release(CountDownLatch latch) {
        if (latch != null) latch.countDown();
    }

    private static void checkBackground() {
        Throwable failure = backgroundFailure.get();
        if (failure != null) throw new AssertionError("uncaught capture/speech worker failure", failure);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] pcm(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (seed + i * 31 + i / 3200);
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] archived() {
        synchronized (Recordings.audio) {
            byte[] result = new byte[Recordings.total()];
            int offset = 0;
            for (byte[] chunk : Recordings.audio) {
                check(chunk.length > 0 && chunk.length <= 3200 && chunk.length % 2 == 0,
                        "encrypted chunks stay within 100ms aligned PCM bound");
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }
}
