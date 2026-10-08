package app.nottheomi.ai;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/** Android-scheduled, local-only saved-audio work. Never starts microphone or Bluetooth. */
public final class RefinementJobService extends JobService {
    static final int JOB_ID = 41008;
    private static final long PASS_MS = 7 * 60 * 1000L;
    private static final Object LOCK = new Object();
    private static Work owner;
    public static volatile String state = "Whisper runs after saving, while capture is idle";
    public static volatile long revision;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Work current;

    /** Safe on a service/main thread; no native close, model loading or archive reads. */
    public static void pauseForCapture() {
        synchronized (LOCK) {
            if (owner != null) owner.cancel();
        }
    }

    /** Queue is in encrypted SQLite; scheduler requests may be safely repeated. */
    public static void schedule(Context context) {
        synchronized (LOCK) {
            if (owner != null) { owner.rescheduleRequested = true; return; }
            try {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (scheduler == null || capturing()) return;
                int result = scheduler.schedule(new JobInfo.Builder(JOB_ID,
                        new ComponentName(context, RefinementJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .setRequiresStorageNotLow(true)
                        .setMinimumLatency(1000L)
                        .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build());
                if (result != JobScheduler.RESULT_SUCCESS)
                    setState("Whisper scheduling unavailable · reopen app to retry; drafts kept");
            } catch (RuntimeException unavailable) {
                setState("Whisper scheduling unavailable · reopen app to retry; drafts kept");
            }
        }
    }

    private static void setState(String value) { state = value; revision++; }
    private static boolean capturing() { return CaptureService.active || OmiCaptureService.active; }

    @Override public boolean onStartJob(JobParameters parameters) {
        synchronized (LOCK) {
            if (owner != null) {
                // A cancelled older native owner must exit before another model can start.
                owner.rescheduleRequested = true;
                main.post(() -> jobFinished(parameters, true));
                return true;
            }
            Work work = new Work(parameters);
            owner = current = work;
            work.thread = new Thread(() -> runWork(work), "saved-whisper-refinement");
            work.thread.start();
            main.post(work.monitor);
            return true;
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        synchronized (LOCK) {
            // Binder unmarshals a new JobParameters for stop; object identity is not stable.
            if (current != null && current.parameters.getJobId() == parameters.getJobId()) {
                current.platformStopped = true;
                current.cancel();
            }
        }
        // Android will reschedule. Checkpoints/drafts survive even abrupt process death.
        return true;
    }

    @Override public void onDestroy() {
        synchronized (LOCK) {
            if (current != null) { current.platformStopped = true; current.cancel(); }
        }
        super.onDestroy();
    }

    private void runWork(Work work) {
        boolean retry = false;
        try {
            if (work.shouldPause()) { retry = true; return; }
            Recordings store = Recordings.get(this);
            List<Recordings.Refinement> pending = store.pendingRefinements();
            if (pending.isEmpty()) { setState("Saved transcripts up to date"); return; }
            setState("Preparing Whisper for saved recordings");
            String path = ModelInstaller.prepare(this, work::shouldPause).getAbsolutePath();
            if (work.shouldPause()) { retry = true; return; }
            work.model = new WhisperModel(path);
            if (work.shouldPause()) { work.cancel(); retry = true; return; }
            for (Recordings.Refinement entry : pending) {
                if (work.shouldPause()) { retry = true; break; }
                try {
                    Recordings.Refinement fresh = store.refinement(entry.id);
                    if (fresh == null || !"pending".equals(fresh.state)) continue;
                    setState("Refining saved transcript · live draft and audio available");
                    refine(store, fresh, work.model, work::shouldPause);
                    revision++;
                } catch (Exception | LinkageError failure) {
                    if (work.shouldPause() || failure instanceof RefinementEngine.Paused) {
                        retry = true;
                        break;
                    }
                    // A failed item is not retried forever. The user can explicitly retry.
                    try { store.failRefinement(entry.id); }
                    catch (Exception missingOrDamaged) { /* Preserve original archive; no destructive repair. */ }
                    setState("Whisper refinement failed · live draft and audio kept; retry in Library");
                }
            }
            if (!work.shouldPause() && !store.pendingRefinements().isEmpty()) retry = true;
            if (!retry && !work.shouldPause())
                setState("Whisper pass finished · check each recording's transcript status");
        } catch (Exception | LinkageError failure) {
            if (work.shouldPause()) retry = true;
            else setState("Whisper unavailable · drafts and audio kept; reopen app to retry");
        } finally {
            // Native context belongs exclusively to this worker. Cancellation never frees it.
            if (work.model != null) {
                try { work.model.close(); } catch (RuntimeException | LinkageError ignored) { }
                work.model = null;
            }
            main.removeCallbacks(work.monitor);
            final boolean reschedule = retry;
            main.post(() -> {
                synchronized (LOCK) {
                    if (owner != work) return;
                    owner = null;
                    if (current == work) current = null;
                    if (work.shouldPause()) setState("Whisper paused · queued until capture is idle");
                    if (!work.platformStopped)
                        jobFinished(work.parameters, reschedule || work.rescheduleRequested);
                }
            });
        }
    }

    static void refine(Recordings store, Recordings.Refinement entry, WhisperModel model,
                       java.util.function.BooleanSupplier cancelled) throws Exception {
        RefinementEngine.run(entry.totalBytes, entry.offsetBytes,
                consumer -> store.forEachPcm(entry.id, consumer::accept), model::transcribe,
                new RefinementEngine.Sink() {
                    public void commit(long before, long after, String text) throws Exception {
                        store.commitRefinementBatch(entry.id, before, after, text);
                        revision++;
                    }
                    public void complete() throws Exception { store.completeRefinement(entry.id); }
                }, cancelled);
    }

    private final class Work {
        final JobParameters parameters;
        final long deadline = SystemClock.elapsedRealtime() + PASS_MS;
        volatile boolean cancelled, platformStopped, rescheduleRequested;
        volatile WhisperModel model;
        Thread thread;
        Work(JobParameters parameters) { this.parameters = parameters; }
        boolean shouldPause() {
            return cancelled || capturing() || SystemClock.elapsedRealtime() >= deadline;
        }
        void cancel() {
            cancelled = true;
            WhisperModel nativeOwner = model;
            if (nativeOwner != null) nativeOwner.cancel();
        }
        final Runnable monitor = new Runnable() {
            @Override public void run() {
                if (shouldPause()) cancel();
                if (thread != null && thread.isAlive()) main.postDelayed(this, 100L);
            }
        };
    }
}
