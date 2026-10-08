package app.nottheomi.ai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Explicit, phone-microphone-only foreground capture. No automatic restarts. */
public final class CaptureService extends Service {
    public static final String ACTION_START = "app.nottheomi.ai.START";
    public static final String ACTION_STOP = "app.nottheomi.ai.STOP";
    public static volatile boolean active;
    public static volatile String state = "Stopped";
    public static volatile String sessionId;
    public static volatile long startedAt;
    public static volatile float level;
    public static volatile String partial = "";
    public static final LiveTranscript display = new LiveTranscript();

    private static final Object LIFECYCLE = new Object();
    private static final String CHANNEL = "phone_capture";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16000;
    // At most 100 ms of PCM per encrypted commit. ASR never blocks microphone I/O.
    private static final int CHUNK_BYTES = SAMPLE_RATE * 2 / 10;
    private static final int ASR_QUEUE_CHUNKS = 300; // Thirty-second bounded Vosk backlog.
    private static final long ASR_STOP_DRAIN_MS = 30000L;
    private static final long WAKE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static CaptureService owner;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile boolean microphoneStarted;
    private volatile String fatalReason;
    private volatile String speechIssue;
    private volatile long stopDeadlineNanos;
    private AudioRecord activeMicrophone; // LIFECYCLE guards Stop against start/release.
    private Thread worker;
    private boolean foreground;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Phone microphone recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible only during an explicitly started local recording");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        synchronized (LIFECYCLE) {
            if (ACTION_STOP.equals(action)) {
                if (owner != null) owner.requestStop();
                else stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!ACTION_START.equals(action)) {
                if (!active) stopSelf(startId);
                return START_NOT_STICKY;
            }
            // Includes model preparation and the entire final teardown. A repeated tap
            // cannot allocate another microphone, key, session, or native model.
            if (active) return START_NOT_STICKY;
            if (OmiCaptureService.active) {
                state = "Stopped — Stop the Omi recording first";
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                state = "Microphone permission is required — nothing recorded";
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            owner = this;
            active = true;
            RefinementJobService.pauseForCapture();
            cancelled = false;
            stopDeadlineNanos = 0;
            microphoneStarted = false;
            fatalReason = null;
            speechIssue = null;
            sessionId = null;
            display.reset(null);
            startedAt = 0;
            level = 0;
            partial = "";
            state = "Preparing offline speech model…";
            try {
                Notification notification = notification();
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                foreground = true;
                worker = new Thread(this::capture, "phone-capture");
                worker.start();
            } catch (RuntimeException | LinkageError failure) {
                // Includes Android background-FGS and while-in-use permission rejection.
                state = "Recording could not start — open the app and check microphone permission";
                endForeground();
                stopSelf();
                owner = null;
                active = false;
                RefinementJobService.schedule(this);
            }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        // Do not clear active or release native objects from the main thread. Only
        // the owning worker can finish queued audio/text and close the microphone.
        requestStop();
        super.onDestroy();
    }

    private void requestStop() {
        synchronized (LIFECYCLE) {
            if (owner != this || !active || cancelled) return;
            cancelled = true;
            stopDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASR_STOP_DRAIN_MS);
            // Stop the source now, even if the archive worker is in a storage commit.
            stopMicrophone(activeMicrophone);
            publishPartial("");
            state = microphoneStarted ? "Saving encrypted recording…" : "Cancelling preparation…";
            updateNotification();
            // No interrupt: interrupting a storage commit could damage finalization.
            // Extraction checks this token, microphone reads are nonblocking, and
            // the startRecording boundary below shares this same monitor.
        }
    }

    private void capture() {
        AudioRecord microphone = null;
        PowerManager.WakeLock wakeLock = null;
        PreviewModel model = null;
        PreviewRecognizer recognizer = null;
        SpeechWorker speech = null;
        Recordings recordings = null;
        String id = null;
        boolean recorded = false;
        byte[] pcm = new byte[CHUNK_BYTES];
        int pendingBytes = 0;
        String terminalState;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotTheOmiAIApp:capture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_TIMEOUT_MS);
            File modelDirectory = PreviewModelInstaller.prepare(this, () -> cancelled);
            checkCancelled();
            try {
                // Do not emit transcript text or verbose native diagnostics to logcat.
                model = new PreviewModel(modelDirectory.getAbsolutePath());
                checkCancelled();
                recognizer = new PreviewRecognizer(model, SAMPLE_RATE);
            } catch (InterruptedIOException stopped) {
                throw stopped;
            } catch (Exception | LinkageError nativeFailure) {
                speechIssue = "offline speech unavailable";
                closeRecognizer(recognizer);
                recognizer = null;
                closeModel(model);
                model = null;
            }
            checkCancelled();
            recordings = Recordings.get(this);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fatalReason = "Microphone permission was removed";
                return;
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                fatalReason = "Phone microphone does not support 16 kHz recording";
                return;
            }
            microphone = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, SAMPLE_RATE * 2));
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                fatalReason = "Phone microphone could not be initialized";
                return;
            }
            AudioDeviceInfo phoneMic = null;
            AudioManager audioManager = getSystemService(AudioManager.class);
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { phoneMic = device; break; }
            }
            if (phoneMic == null || !microphone.setPreferredDevice(phoneMic)) {
                fatalReason = "Built-in phone microphone is unavailable";
                return;
            }
            checkCancelled();
            try {
                id = recordings.create().id;
                sessionId = id;
                display.reset(id);
            } catch (Exception storageFailure) {
                fatalReason = "Encrypted storage unavailable or full — nothing recorded";
                return;
            }
            if (recognizer != null) {
                speech = new SpeechWorker(recordings, id, model, recognizer);
                speech.start();
                model = null;
                recognizer = null;
            }
            synchronized (LIFECYCLE) {
                checkCancelled();
                // Stop and the native start are linearized: a cancelled preparation
                // can never subsequently acquire/start the microphone.
                microphone.startRecording();
                if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    fatalReason = "Phone microphone did not start";
                    return;
                }
                microphoneStarted = true;
                activeMicrophone = microphone;
                startedAt = SystemClock.elapsedRealtime();
                updateRecordingState();
            }
            long lastData = SystemClock.elapsedRealtime();
            long lastCommit = lastData;
            long lastRouteCheck = 0;
            long lastWakeRenewal = lastData;
            while (!cancelled && fatalReason == null) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastWakeRenewal >= WAKE_TIMEOUT_MS / 2) {
                    wakeLock.acquire(WAKE_TIMEOUT_MS);
                    lastWakeRenewal = now;
                }
                if (now - lastRouteCheck >= 1000) {
                    AudioDeviceInfo routed = microphone.getRoutedDevice();
                    if (routed != null && routed.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        fatalReason = "Recording stopped because the microphone route left this phone";
                        break;
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        AudioRecordingConfiguration configuration = microphone.getActiveRecordingConfiguration();
                        if (configuration != null && configuration.isClientSilenced()) {
                            fatalReason = "Microphone is silenced by Android or another app";
                            break;
                        }
                    }
                    lastRouteCheck = now;
                }
                int count = microphone.read(pcm, pendingBytes, pcm.length - pendingBytes,
                        AudioRecord.READ_NON_BLOCKING);
                if (count < 0) {
                    if (!cancelled) fatalReason = "Microphone read failed — captured audio was retained";
                    break;
                }
                if (count == 0) {
                    if (now - lastData > 5000) {
                        fatalReason = "Microphone stopped delivering audio";
                        break;
                    }
                    Thread.sleep(10);
                }
                if ((count & 1) != 0 || count > pcm.length - pendingBytes) {
                    fatalReason = "Microphone returned invalid PCM audio";
                    break;
                }
                if (count > 0) lastData = now;
                pendingBytes += count;
                if (pendingBytes == 0) continue;
                level = rms(pcm, pendingBytes);
                if (pendingBytes < pcm.length && now - lastCommit < 100) continue;
                try {
                    // This encrypted durable commit MUST precede every ASR enqueue.
                    // Stop does not discard a successful in-flight microphone read.
                    recordings.appendAudio(id, pcm, pendingBytes);
                    recorded = true;
                } catch (Exception storageFailure) {
                    pendingBytes = 0; // Do not retry an ambiguously failed commit.
                    fatalReason = "Encrypted storage unavailable or full — earlier audio was retained";
                    break;
                }
                if (speech != null) speech.offer(pcm, pendingBytes);
                pendingBytes = 0;
                lastCommit = now;
            }
        } catch (InterruptedIOException | InterruptedException stopped) {
            if (!cancelled) fatalReason = "Recording worker was interrupted";
            Thread.interrupted(); // Final storage flush must not inherit an interrupt.
        } catch (SecurityException permissionFailure) {
            fatalReason = "Microphone permission or foreground access was removed";
        } catch (Exception | LinkageError failure) {
            fatalReason = microphoneStarted ? "Recording failed — committed audio was retained"
                    : "Offline model or microphone unavailable — nothing recorded";
        } finally {
            requestStop();
            if (microphoneStarted || id != null) setProgress("Saving encrypted recording…");
            else setProgress("Closing offline speech model…");
            if (microphone != null) {
                synchronized (LIFECYCLE) {
                    activeMicrophone = null;
                    stopMicrophone(microphone);
                }
                try { microphone.release(); }
                catch (RuntimeException failure) {
                    if (fatalReason == null) fatalReason = "Microphone cleanup failed";
                }
            }
            level = 0;
            if (pendingBytes > 0 && recordings != null && id != null) {
                try {
                    recordings.appendAudio(id, pcm, pendingBytes);
                    recorded = true;
                    if (speech != null) speech.offer(pcm, pendingBytes);
                } catch (Exception storageFailure) {
                    fatalReason = "Final audio flush failed — earlier committed audio was retained";
                }
            }
            Arrays.fill(pcm, (byte) 0);
            // Drain all already-committed PCM, persist endpoint + stop-final text,
            // and close recognizer BEFORE its model. No native close/use races.
            if (speech != null) speech.finishAndJoin();
            closeRecognizer(recognizer);
            closeModel(model);
            publishPartial("");
            String savedStatus = fatalReason != null ? "error" : !recorded ? "cancelled"
                    : speechIssue != null ? "audio_only" : "saved";
            boolean finalized = true;
            if (recordings != null && id != null) {
                try { recordings.finish(id, savedStatus); }
                catch (Exception storageFailure) {
                    finalized = false;
                    fatalReason = "Save finalization failed — committed audio needs recovery on next launch";
                }
            }
            if (fatalReason != null) terminalState = "Stopped — " + fatalReason;
            else if (!recorded) terminalState = "Stopped — no audio recorded";
            else if (speechIssue != null) terminalState = "Stopped — audio saved; " + speechIssue;
            else terminalState = "Stopped — saved on this phone";
            if (!finalized) terminalState = "Stopped — " + fatalReason;
            try {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException releaseFailure) {
                terminalState = "Stopped — wake-lock cleanup failed";
            }
            String finalState = terminalState;
            main.post(() -> {
                synchronized (LIFECYCLE) {
                    if (owner != this) return;
                    state = finalState;
                    endForeground();
                    stopSelf();
                    worker = null;
                    owner = null;
                    // Last step: all microphone, ASR, archive and wake-lock teardown
                    // is complete before UI/deletion/restart can observe inactive.
                    active = false;
                    RefinementJobService.schedule(this);
                }
            });
        }
    }

    private void stopMicrophone(AudioRecord microphone) {
        if (microphone == null) return;
        try {
            if (microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) microphone.stop();
        } catch (RuntimeException failure) {
            if (fatalReason == null) fatalReason = "Microphone could not be stopped cleanly";
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stopped");
    }

    private void setProgress(String progress) {
        state = progress;
        updateNotification();
    }

    private void updateRecordingState() {
        if (cancelled || !microphoneStarted) return;
        setProgress(speechIssue == null ? "Recording — offline transcription"
                : "Recording — audio only (" + speechIssue + ")");
    }

    private void speechFailed(String reason) {
        synchronized (LIFECYCLE) {
            speechIssue = reason;
            publishPartial("");
            updateRecordingState();
        }
    }

    /** Stop/errors and preview writes share a boundary: late ASR cannot revive it. */
    private void publishPartial(String text) {
        synchronized (LIFECYCLE) {
            display.partial(cancelled || speechIssue != null || fatalReason != null ? "" : text);
            partial = display.snapshot().partial;
        }
    }

    private static float rms(byte[] pcm, int count) {
        double sum = 0;
        for (int i = 0; i < count; i += 2) {
            int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            sum += (double) sample * sample;
        }
        return (float) Math.min(1.0, Math.sqrt(sum / (count / 2)) / 32768.0);
    }

    private void closeRecognizer(PreviewRecognizer recognizer) {
        if (recognizer == null) return;
        try { recognizer.close(); }
        catch (RuntimeException | LinkageError failure) { speechIssue = "speech engine cleanup failed"; }
    }

    private void closeModel(PreviewModel model) {
        if (model == null) return;
        try { model.close(); }
        catch (RuntimeException | LinkageError failure) { speechIssue = "speech model cleanup failed"; }
    }

    private Notification notification() {
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wave)
                .setContentTitle("NotTheOmiAIApp")
                .setContentText(state)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 2, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void updateNotification() {
        if (!foreground) return;
        try { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification()); }
        catch (RuntimeException ignored) { /* Notification permission is separate from microphone consent. */ }
    }

    private void endForeground() {
        if (!foreground) return;
        try { stopForeground(STOP_FOREGROUND_REMOVE); }
        finally { foreground = false; }
    }

    /** Native ASR is isolated from the microphone's encryption/durability loop. */
    private final class SpeechWorker extends Thread {
        private final Recordings recordings;
        private final String id;
        private final PreviewModel model;
        private final PreviewRecognizer recognizer;
        // Bounded thirty-second backlog. Slow ASR degrades to audio-only rather than
        // blocking capture, growing indefinitely, or silently losing archive PCM.
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(ASR_QUEUE_CHUNKS);
        private final Object nativeLifetime = new Object();
        private boolean nativeClosed;
        private volatile boolean aborted;
        private volatile boolean closing;
        private volatile boolean accepting = true;

        SpeechWorker(Recordings recordings, String id, PreviewModel model, PreviewRecognizer recognizer) {
            super("offline-speech");
            this.recordings = recordings;
            this.id = id;
            this.model = model;
            this.recognizer = recognizer;
        }

        void offer(byte[] pcm, int count) {
            if (!accepting) return;
            if (!queue.offer(Arrays.copyOf(pcm, count))) {
                accepting = false;
                closing = true;
                speechFailed("speech processing fell behind; transcript may be incomplete");
            }
        }

        @Override public void run() {
            try {
                while (!aborted && (!closing || !queue.isEmpty())) {
                    byte[] pcm = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    try {
                        if (!aborted) {
                            if (recognizer.acceptWaveForm(pcm, pcm.length)) {
                                appendResult(recognizer.getResult());
                                publishPartial("");
                            } else if (!aborted) {
                                publishPartial(new JSONObject(recognizer.getPartialResult())
                                        .optString("partial", "").trim());
                            }
                        }
                    } finally { Arrays.fill(pcm, (byte) 0); }
                }
                if (!aborted) appendResult(recognizer.getFinalResult());
            } catch (TranscriptStorageException storageFailure) {
                fatalReason = "Encrypted transcript storage unavailable or full; captured audio was retained";
                requestStop();
            } catch (Exception | LinkageError nativeFailure) {
                if (!aborted) speechFailed("offline speech failed; transcript may be incomplete");
            } finally {
                accepting = false;
                closing = true;
                for (byte[] pcm : queue) Arrays.fill(pcm, (byte) 0);
                queue.clear();
                synchronized (nativeLifetime) {
                    closeRecognizer(recognizer);
                    closeModel(model);
                    nativeClosed = true;
                }
                publishPartial("");
            }
        }

        private void appendResult(String json) throws Exception {
            if (aborted) return;
            String text = new JSONObject(json).optString("text", "").trim();
            if (text.isEmpty()) return;
            try { recordings.appendText(id, text); }
            catch (Exception storageFailure) { throw new TranscriptStorageException(); }
            display.finalized(text);
        }

        void finishAndJoin() {
            accepting = false;
            closing = true;
            boolean interrupted = false;
            while (isAlive()) {
                if (!aborted && System.nanoTime() >= stopDeadlineNanos) {
                    aborted = true;
                    speechFailed("offline speech drain timed out; transcript may be incomplete");
                    synchronized (nativeLifetime) {
                        // Cancellation never closes state underneath inference. The
                        // speech owner must exit before capture can become inactive.
                        if (!nativeClosed) model.cancel();
                    }
                }
                try { join(100); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            // Do not re-interrupt until after the storage final flush; this worker
            // is terminating anyway. Preserve the interruption as truthful status.
            if (interrupted && !cancelled && fatalReason == null) fatalReason = "Saving was interrupted";
        }
    }

    private static final class TranscriptStorageException extends Exception { }
}
