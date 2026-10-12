package app.nottheomi.ai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit service-owned BLE capture. No microphone, cloud, or automatic service restart.
 * Recoverable gaps stay inside the user's active session; Stop always cancels retry.
 * BLE callbacks only enqueue bounded PCM/control events. Audio is durably encrypted
 * before it enters the independent, bounded offline recognizer queue.
 */
public final class OmiCaptureService extends Service {
    public static final String ACTION_START = "app.nottheomi.ai.OMI_START";
    public static final String ACTION_STOP = "app.nottheomi.ai.OMI_STOP";
    public static final String ACTION_CONTROLS = "app.nottheomi.ai.OMI_CONTROLS";
    public static final String ACTION_DISCONNECT = "app.nottheomi.ai.OMI_DISCONNECT";
    public static volatile boolean active;
    public static volatile boolean controlsActive;
    public static volatile String controlsState = "Disconnected";
    public static volatile String state = "Stopped", sessionId, partial = "";
    public static final LiveTranscript display = new LiveTranscript();
    public static volatile String transport = "Disconnected", lastButton = "No press received";
    public static volatile long startedAt;
    public static volatile float level;
    public static volatile int battery = -1;
    public static volatile OmiBle.LedState ledState = unknownLed();

    private static final Object LIFECYCLE = new Object();
    private static final String CHANNEL = "omi_capture";
    private static final int NOTIFICATION_ID = 42;
    private static final int SAMPLE_RATE = 16000;
    private static final long WAKE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long STARTUP_TIMEOUT_MS = 120000L;
    private static final String GAP_MARKER = "[Omi audio gap — recording stopped; earlier audio retained]";
    private static final String RECOVERY_MARKER = "[Omi audio gap — audio missing; recovery attempted; earlier audio retained]";
    private static final String RESUME_MARKER = "[Omi audio resumed after a gap — separate recording segment; missing audio not reconstructed]";
    private static final String BOOKMARK = "[Omi button bookmark]";
    private static final String INCOMPLETE_MARKER = "[Offline transcript incomplete — encrypted audio retained]";
    private static final long ASR_ROLLOVER_WAIT_MS = 250L;
    private static final long ASR_STOP_DRAIN_MS = 30000L;
    private static final int ASR_MAX_BYTES = SAMPLE_RATE * 2 * 30;
    private static final int ASR_MAX_EVENTS = 512;
    private static OmiCaptureService owner;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled, receivedPcm, recovering, hadGap;
    private volatile long lastPcmAt;
    private volatile long lastArchivedAt = -1, archivedSamples;
    private volatile long stopDeadlineNanos;
    private volatile String fatalReason, speechIssue, failureMarker;
    private volatile Thread transportWorker;
    private CountDownLatch transportThreadKnown;
    private boolean connectEnqueued;
    private boolean foreground;
    private String lastNotificationState;
    private String selectedAddress;
    private boolean switching, disconnectRequested, returnToControls;
    private long transportEpoch;
    private Thread worker;
    private OmiBle ble;
    private OmiPcmQueue incoming;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Omi wearable recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Explicit wearable recording, encrypted and kept on this phone");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        synchronized (LIFECYCLE) {
            if (ACTION_DISCONNECT.equals(action)) {
                if (owner != null) owner.disconnect();
                else stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                if (owner != null) owner.stopCapture(null, null);
                else stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!ACTION_START.equals(action) && !ACTION_CONTROLS.equals(action)) {
                if (owner == null) stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (active || switching) return START_NOT_STICKY;
            if (controlsActive) {
                if (ACTION_START.equals(action)) closeControls(true);
                return START_NOT_STICKY;
            }
            return startSession(action, intent.getStringExtra("address"));
        }
    }

    /** Called under LIFECYCLE, including internal transitions without Android start IDs. */
    private int startSession(String action, String address) {
        if (CaptureService.active && ACTION_START.equals(action)) return reject("Stop the phone microphone recording first");
        if (!hasBluetoothPermissions()) return reject("Bluetooth permissions are required — nothing recorded");
        if (!notificationsVisible()) return reject("Enable recording notifications before starting — nothing recorded");
        if (address == null) address = getSharedPreferences("omi", MODE_PRIVATE).getString("address", "");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return reject("Select a valid Omi device first — nothing recorded");
        selectedAddress = address;
        disconnectRequested = returnToControls = false;
        owner = this;
        if (ACTION_CONTROLS.equals(action)) {
            startControls();
            return START_NOT_STICKY;
        }
        active = true;
        RefinementJobService.pauseForCapture();
        cancelled = receivedPcm = recovering = hadGap = false;
        lastPcmAt = 0;
        stopDeadlineNanos = 0;
        fatalReason = speechIssue = failureMarker = null;
        transportWorker = null;
        transportThreadKnown = new CountDownLatch(1);
        connectEnqueued = false;
        sessionId = null; startedAt = 0; level = 0; partial = "";
        display.reset(null);
        battery = -1; ledState = unknownLed(); lastButton = "No press received";
        transport = "Disconnected";
        incoming = new OmiPcmQueue();
        state = "Preparing offline speech model…";
        final String selected = address;
        try {
            ensureForeground();
            worker = new Thread(() -> capture(selected), "omi-capture");
            worker.start();
        } catch (RuntimeException | LinkageError failure) {
            state = "Omi capture could not start — open the app and check Bluetooth/notification access";
            endForeground(); stopSelf(); worker = null; owner = null; active = false;
            RefinementJobService.schedule(this);
        }
        return START_NOT_STICKY;
    }

    private int reject(String reason) {
        state = "Stopped — " + reason;
        finishDisconnected();
        return START_NOT_STICKY;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return allowed(Manifest.permission.BLUETOOTH_CONNECT) && allowed(Manifest.permission.BLUETOOTH_SCAN);
        }
        return allowed(Manifest.permission.BLUETOOTH) && allowed(Manifest.permission.BLUETOOTH_ADMIN)
                && allowed(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean allowed(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsVisible() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    private void ensureForeground() {
        if (foreground) { updateNotification(); return; }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, notification());
        foreground = true;
        lastNotificationState = notificationText();
    }

    /** No archive, recognizer, PCM queue or capture wake lock exists in standby. */
    private void startControls() {
        controlsActive = true; cancelled = true;
        incoming = null; startedAt = 0; level = 0;
        controlsState = "Connecting buttons — no audio recorded";
        try {
            ensureForeground();
            ble = new OmiBle(this, listener(++transportEpoch, true));
            ble.connectControls(selectedAddress);
        } catch (RuntimeException | LinkageError failure) {
            controlsState = "Button connection unavailable";
            disconnect();
        }
    }

    private void disconnect() {
        disconnectRequested = true; returnToControls = false;
        if (active) stopCapture(null, null);
        else if (controlsActive) closeControls(false);
        else if (!switching) finishDisconnected();
    }

    /** A native close callback precedes worker exit. Join off-main before reuse. */
    private void closeControls(boolean startRecording) {
        if (!controlsActive || switching) return;
        controlsActive = false;
        controlsState = startRecording ? "Preparing recording…" : "Disconnecting…";
        switching = true;
        long epoch = ++transportEpoch;
        OmiBle closing = ble;
        Runnable complete = () -> {
            Thread closedOn = Thread.currentThread();
            new Thread(() -> {
                boolean interrupted = false;
                if (closedOn.getName().equals("OmiBle")) {
                    while (closedOn.isAlive()) {
                        try { closedOn.join(); } catch (InterruptedException e) { interrupted = true; }
                    }
                }
                final boolean failed = interrupted;
                main.post(() -> {
                    synchronized (LIFECYCLE) {
                        if (owner != this || transportEpoch != epoch) return;
                        switching = false; ble = null;
                        if (startRecording && !disconnectRequested && !failed) {
                            startSession(ACTION_START, selectedAddress);
                        } else finishDisconnected();
                    }
                });
            }, "omi-controls-close").start();
        };
        if (closing != null) closing.stop(complete); else complete.run();
    }

    private void finishDisconnected() {
        controlsActive = false; controlsState = "Disconnected";
        transport = "Disconnected"; battery = -1; ledState = unknownLed();
        ble = null; owner = null;
        endForeground(); stopSelf();
    }

    private static OmiBle.LedState unknownLed() {
        return new OmiBle.LedState(false, false, -1, "LED brightness unknown; start an explicit Omi recording to read it");
    }

    public static boolean waitingForOmi() {
        synchronized (LIFECYCLE) { return owner != null && active && !owner.cancelled && owner.recovering; }
    }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        // Read-only counters only; no database, audio, text, device identifiers,
        // exception strings or recording IDs. Never waits on BLE/ASR workers.
        long now = SystemClock.elapsedRealtime(), accepted = lastPcmAt, written = lastArchivedAt;
        out.println("omi_capture_snapshot=approximate active=" + (owner == this && active)
            + " cancelled=" + cancelled + " waiting=" + recovering + " received_pcm=" + receivedPcm
            + " accepted_pcm_age_ms=" + (!receivedPcm ? -1 : Math.max(0, now-accepted))
            + " archived_samples=" + archivedSamples
            + " archive_write_age_ms=" + (written < 0 ? -1 : Math.max(0, now-written)));
        OmiBle currentBle = ble;
        if (currentBle != null) out.println(currentBle.diagnostics());
    }
    public static void requestStopCapture() {
        synchronized (LIFECYCLE) { if (owner != null) owner.stopCapture(null, null); }
    }
    public static void requestDisconnect() {
        synchronized (LIFECYCLE) { if (owner != null) owner.disconnect(); }
    }
    public static void readLedBrightness() {
        synchronized (LIFECYCLE) {
            if (owner != null && !owner.cancelled && owner.ble != null) owner.ble.readLedBrightness();
        }
    }
    public static void setLedBrightness(int value) {
        synchronized (LIFECYCLE) {
            if (owner != null && !owner.cancelled && owner.ble != null) owner.ble.setLedBrightness(value);
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { synchronized (LIFECYCLE) { disconnect(); } super.onDestroy(); }

    /** Stop is a FIFO terminal boundary, not an interrupt of an encrypted commit. */
    private void stopCapture(String reason, String marker) {
        synchronized (LIFECYCLE) {
            if (owner != this) return;
            if (!active) {
                if (switching) { disconnectRequested = true; returnToControls = false; }
                return;
            }
            if (reason != null && fatalReason == null) fatalReason = reason;
            if (fatalReason != null) returnToControls = false;
            // Speech/storage failure can arrive after normal Stop's terminal.
            if (marker != null) failureMarker = marker;
            if (cancelled) return;
            returnToControls = receivedPcm && fatalReason == null && !disconnectRequested;
            cancelled = true;
            stopDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASR_STOP_DRAIN_MS);
            publishPartial("");
            incoming.close(marker);
            state = receivedPcm ? "Saving encrypted Omi recording…" : "Cancelling Omi preparation…";
            updateNotification();
            // Invalidates queued transport callbacks/retries immediately. stop() also
            // closes GATT and quits its own FIFO worker; callbacks are gated below.
            if (ble != null) ble.stop();
        }
    }

    private boolean listening() { return owner == this && active && !cancelled && foreground; }

    private OmiBle.Listener listener(long epoch, boolean controls) { return new OmiBle.Listener() {
        private boolean current() { return owner == OmiCaptureService.this && epoch == transportEpoch; }
        private boolean accepting() { return current() && (controls ? controlsActive : listening()); }
        @Override public void onDevice(String address, String name) { /* UI owns selection. */ }
        @Override public void onPcm(short[] samples) {
            synchronized (LIFECYCLE) {
                if (controls || !accepting()) return;
                if (!incoming.offer(samples)) {
                    stopCapture("Omi audio queue overflow or invalid PCM; earlier audio retained", GAP_MARKER);
                    return;
                }
                lastPcmAt = SystemClock.elapsedRealtime();
                boolean resumed = recovering;
                recovering = false;
                if (!receivedPcm) {
                    receivedPcm = true;
                    startedAt = lastPcmAt;
                    updateRecordingState();
                } else if (resumed) {
                    transport = "Omi connected: receiving audio · PCM arriving";
                    updateRecordingState();
                }
            }
        }
        @Override public void onGap() {
            // Every gap, including final stopInternal, runs on OmiBle's FIFO.
            // Remember it even after cancellation to JOIN actual transport exit.
            synchronized (LIFECYCLE) {
                if (controls || !current()) return;
                transportWorker = Thread.currentThread();
                transportThreadKnown.countDown();
                if (!accepting()) return;
                // Reset, packet loss and reconnect all use this callback. Initial
                // resets lost no audio. Coalesce repeated gaps until real PCM
                // returns; retain an ordered speech-reset marker, not silent splice.
                beginRecovery();
            }
        }
        @Override public void onStatus(String value) {
            synchronized (LIFECYCLE) {
                if (!accepting()) return;
                if (controls) {
                    transport = value;
                    controlsState = value + " · no audio recorded";
                    updateNotification();
                    if (value.contains("controls disconnected") || value.startsWith("Invalid selected Bluetooth address"))
                        main.post(() -> { synchronized (LIFECYCLE) { if (accepting()) disconnect(); } });
                    return;
                }
                // Transport diagnostics cannot claim this service has ASR running.
                transport = value.replace("local transcription active", "PCM arriving")
                        .replace("incomplete task discarded", "audio gap marked; waiting for valid audio");
                if (value.contains("capture disconnected")
                        || value.startsWith("Invalid selected Bluetooth address")) {
                    stopCapture("Omi connection failed: " + transport, receivedPcm ? GAP_MARKER : null);
                } else if (!receivedPcm) setProgress("Connecting to Omi — waiting for audio…");
            }
        }
        @Override public void onButton(int event) {
            synchronized (LIFECYCLE) {
                if (accepting() && ble != null) onButton(event, ble.connectionGeneration());
            }
        }
        @Override public void onButton(int event, long generation) {
            synchronized (LIFECYCLE) {
                // LONG/PRESS/RELEASE remain firmware-reserved.
                if (!accepting() || (event != 1 && event != 2)) return;
                OmiBle source = ble;
                if (source == null || generation != source.connectionGeneration()) return;
                String action = ButtonActions.action(OmiCaptureService.this, event);
                // Preparation is cancelled explicitly in the UI; legacy capture
                // gestures arm only after actual PCM. Media stays independent.
                if (!controls && !receivedPcm && ("bookmark".equals(action)
                        || "stop".equals(action) || "toggle".equals(action))) return;
                lastButton = (event == 1 ? "Single" : "Double") + " press — " + action;
                // Archive controls share the PCM FIFO; UI/media work may use main.
                if (!controls && ("stop".equals(action) || "toggle".equals(action))) {
                    stopCapture(null, null); return;
                }
                if (!controls && "bookmark".equals(action)) {
                    if (receivedPcm && !incoming.bookmark(BOOKMARK))
                        stopCapture("Omi control queue overflow; earlier audio retained", GAP_MARKER);
                    return;
                }
                main.post(() -> {
                    synchronized (LIFECYCLE) {
                        if (!accepting() || source != ble || generation != source.connectionGeneration()) return;
                        if (ButtonActions.isMediaAction(action)) lastButton = ButtonActions.dispatchMedia(OmiCaptureService.this, event);
                        else if ("shortcut".equals(action)) lastButton = ButtonActions.launchShortcut(OmiCaptureService.this, event);
                        else if (controls && ("start".equals(action) || "toggle".equals(action))) closeControls(true);
                    }
                });
            }
        }
        @Override public void onLedState(OmiBle.LedState value) {
            synchronized (LIFECYCLE) { if (accepting()) ledState = value; }
        }
        @Override public void onBattery(int percent) {
            synchronized (LIFECYCLE) { if (accepting()) battery = percent >= 0 && percent <= 100 ? percent : -1; }
        }
    }; }

    /** Called under LIFECYCLE; one gap per missing-audio episode, not per retry. */
    private void beginRecovery() {
        if (!listening() || !receivedPcm || recovering) return;
        if (!incoming.bookmark(RECOVERY_MARKER)) {
            stopCapture("Omi gap marker queue overflow; earlier audio retained", GAP_MARKER);
            return;
        }
        recovering = hadGap = true;
        level = 0;
        publishPartial("");
        setProgress("Waiting for Omi — audio paused; reconnecting automatically; Stop cancels retry");
    }

    private void capture(String address) {
        PowerManager.WakeLock wakeLock = null;
        PreviewModel model = null;
        PreviewRecognizer recognizer = null;
        SpeechWorker speech = null;
        SpeechWorker retiredSpeech = null; // At most one native owner; no replacement on stall.
        Recordings recordings = null;
        String id = null;
        boolean recorded = false;
        boolean rotateBeforePcm = false;
        boolean incompleteMarked = false;
        String terminalMarker = null;
        try {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "NotTheOmiAIApp:omi-capture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_TIMEOUT_MS);
            long lastWakeRenewal = SystemClock.elapsedRealtime();
            File directory = PreviewModelInstaller.prepare(this, () -> cancelled);
            checkCancelled();
            try {
                model = new PreviewModel(directory.getAbsolutePath());
                checkCancelled();
                recognizer = new PreviewRecognizer(model, SAMPLE_RATE);
            } catch (InterruptedIOException stopped) { throw stopped; }
            catch (Exception | LinkageError nativeFailure) {
                speechIssue = "offline speech unavailable";
                closeRecognizer(recognizer); recognizer = null;
                closeModel(model); model = null;
            }
            checkCancelled();
            if (CaptureService.active) {
                fatalReason = "Phone microphone capture became active — Omi was not connected";
                return;
            }
            // Preparation consumes the original bounded lease. Do not restart its
            // renewal clock at connect; renew promptly when preparation returns.
            // A preparation blocked beyond that lease can still let it expire.
            long preparedAt = SystemClock.elapsedRealtime();
            if (!wakeLock.isHeld() || preparedAt - lastWakeRenewal >= WAKE_TIMEOUT_MS / 2) {
                wakeLock.acquire(WAKE_TIMEOUT_MS);
                lastWakeRenewal = SystemClock.elapsedRealtime();
            }
            try {
                recordings = Recordings.get(this); id = recordings.create().id; sessionId = id;
                display.reset(id);
            }
            catch (Exception storageFailure) {
                fatalReason = "Encrypted storage unavailable or full — nothing recorded";
                return;
            }
            if (recognizer != null) {
                speech = new SpeechWorker(recordings, id, model, recognizer);
                speech.start(); model = null; recognizer = null;
            }
            synchronized (LIFECYCLE) {
                checkCancelled();
                if (!hasBluetoothPermissions() || !notificationsVisible()) {
                    fatalReason = "Bluetooth or notification access was removed — nothing recorded";
                    return;
                }
                // Stop shares this exact monitor: cancelled preparation cannot connect.
                ble = new OmiBle(this, listener(++transportEpoch, false));
                setProgress("Connecting to Omi — waiting for audio…");
                ble.connect(address);
                connectEnqueued = true;
            }
            long connectedAt = SystemClock.elapsedRealtime();
            long lastPermissionCheck = connectedAt;
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (!wakeLock.isHeld() || now - lastWakeRenewal >= WAKE_TIMEOUT_MS / 2) {
                    wakeLock.acquire(WAKE_TIMEOUT_MS); lastWakeRenewal = SystemClock.elapsedRealtime();
                }
                if (!cancelled && now - lastPermissionCheck >= 1000) {
                    if (!hasBluetoothPermissions() || !notificationsVisible()) {
                        stopCapture("Bluetooth or notification access was removed", GAP_MARKER);
                    } else if (CaptureService.active) {
                        stopCapture("Competing phone microphone capture detected", GAP_MARKER);
                    }
                    lastPermissionCheck = now;
                }
                // Only initial startup expires. Once real PCM established this
                // explicit session, absence means waiting, not implicit Stop.
                // Fence against concurrent PCM; status/battery cannot resume it.
                synchronized (LIFECYCLE) {
                    if (!cancelled && !receivedPcm && now - connectedAt >= STARTUP_TIMEOUT_MS) {
                        stopCapture("Omi startup timed out without audio", GAP_MARKER);
                    } else if (!cancelled && receivedPcm && now - lastPcmAt >= STARTUP_TIMEOUT_MS) {
                        beginRecovery();
                    }
                }
                OmiPcmQueue.Event event = incoming.pollBatch(200);
                if (event == null) continue;
                if (event.terminal) { terminalMarker = event.marker; break; }
                if (event.pcm != null) {
                    try {
                        if (rotateBeforePcm) {
                            // Text markers alone cannot prevent WAV/playback from
                            // splicing audio. Bound ASR drain; on timeout fence late
                            // writes and transfer pending controls, without waiting
                            // for native return or closing its owner underneath it.
                            // No empty segment per retry: rotation needs real PCM.
                            if (speech != null) {
                                if (!speech.awaitIdle()) {
                                    if (fatalReason == null) {
                                        speechFailed("offline speech stalled during recovery; transcript may be incomplete");
                                        retiredSpeech = speech; speech = null;
                                        retiredSpeech.retireToArchive();
                                    }
                                } else speech.useRecording(null);
                            }
                            if (fatalReason != null) {
                                terminalMarker = GAP_MARKER;
                                break;
                            }
                            if (speechIssue != null && !incompleteMarked) {
                                incompleteMarked = true; // Attempt once, even if commit outcome is ambiguous.
                                recordings.appendText(id, INCOMPLETE_MARKER);
                                display.finalized(INCOMPLETE_MARKER);
                            }
                            recordings.finish(id, speechIssue == null ? "saved" : "audio_only");
                            id = null; // Never append to/re-finalize the closed prefix.
                            id = recordings.create().id; sessionId = id;
                            incompleteMarked = false;
                            display.reset(id);
                            recordings.appendText(id, RESUME_MARKER);
                            display.finalized(RESUME_MARKER);
                            if (speech != null) speech.useRecording(id);
                            rotateBeforePcm = false;
                            // Stop still drains PCM accepted BEFORE its FIFO fence.
                            // Creating an archive segment is not a capture restart.
                        }
                        recordings.appendAudio(id, event.pcm, event.pcm.length);
                        archivedSamples += event.pcm.length / 2;
                        lastArchivedAt = SystemClock.elapsedRealtime();
                        recorded = true;
                        level = recovering ? 0 : rms(event.pcm);
                        if (speech != null) speech.offerAudio(event.pcm);
                    } catch (Exception storageFailure) {
                        fatalReason = "Encrypted storage unavailable or full — earlier audio retained";
                        terminalMarker = GAP_MARKER;
                        break; // Never retry an ambiguously failed encrypted commit.
                    } finally { Arrays.fill(event.pcm, (byte) 0); }
                } else if (event.marker != null) {
                    if (speech != null && !speech.offerMarker(event.marker)) {
                        // Controls cannot be silently lost on an ASR-overloaded FIFO.
                        // Fence/retire without waiting for a stalled native call.
                        if (fatalReason != null) { terminalMarker = GAP_MARKER; break; }
                        speechFailed("speech controls fell behind; transcript may be incomplete");
                        retiredSpeech = speech; speech = null;
                        retiredSpeech.retireToArchive();
                        if (fatalReason != null) { terminalMarker = GAP_MARKER; break; }
                    }
                    if (speech == null) {
                        recordings.appendText(id, event.marker);
                        display.finalized(event.marker);
                    }
                    if (RECOVERY_MARKER.equals(event.marker)) rotateBeforePcm = true;
                }
            }
        } catch (InterruptedIOException | InterruptedException stopped) {
            if (!cancelled) fatalReason = "Omi capture worker was interrupted";
            Thread.interrupted();
        } catch (SecurityException permissionFailure) {
            fatalReason = "Bluetooth permission or foreground access was removed";
            if (receivedPcm) terminalMarker = GAP_MARKER;
        } catch (Exception | LinkageError failure) {
            fatalReason = receivedPcm ? "Omi capture failed — committed audio retained"
                    : "Offline model or Omi capture unavailable — nothing recorded";
            if (receivedPcm) terminalMarker = GAP_MARKER;
        } finally {
            stopCapture(null, null);
            setProgress("Saving encrypted Omi recording…");
            awaitTransportStop();
            // BLE has already been invalidated; no callback can enqueue later data.
            incoming.clear();
            level = 0;
            if (speech != null) speech.finishAndJoin();
            // Native cleanup still belongs to its worker. Stop already fenced BLE;
            // a permanently stuck native call can still delay final teardown.
            if (retiredSpeech != null) retiredSpeech.finishAndJoin();
            closeRecognizer(recognizer); closeModel(model); publishPartial("");
            if (recordings != null && id != null) {
                if (speechIssue != null && !incompleteMarked) {
                    try {
                        incompleteMarked = true;
                        recordings.appendText(id, INCOMPLETE_MARKER);
                        display.finalized(INCOMPLETE_MARKER);
                    } catch (Exception storageFailure) {
                        fatalReason = "Encrypted transcript warning could not be saved; earlier audio retained";
                    }
                }
                if (failureMarker != null) terminalMarker = failureMarker;
                if (terminalMarker != null) {
                    try {
                        recordings.appendText(id, terminalMarker);
                        display.finalized(terminalMarker);
                    }
                    catch (Exception storageFailure) {
                        fatalReason = "Encrypted gap marker could not be saved; earlier audio retained";
                    }
                }
                String status = fatalReason != null ? "error" : !recorded ? "cancelled"
                        : speechIssue != null ? "audio_only" : "saved";
                try { recordings.finish(id, status); }
                catch (Exception storageFailure) {
                    fatalReason = "Save finalization failed — committed audio needs recovery on next launch";
                }
            }
            if (wakeLock != null) {
                try { if (wakeLock.isHeld()) wakeLock.release(); }
                catch (RuntimeException failure) { fatalReason = "Wake-lock cleanup failed"; }
            }
            String finalState = fatalReason != null ? "Stopped — " + fatalReason
                    : !recorded ? "Stopped — no audio recorded"
                    : speechIssue != null ? "Stopped — audio saved; " + speechIssue
                    : hadGap ? "Stopped — Omi audio saved; gaps marked, resumed audio kept in separate segments"
                    : "Stopped — Omi recording saved on this phone";
            main.post(() -> {
                synchronized (LIFECYCLE) {
                    if (owner != this) return;
                    state = finalState;
                    ble = null; worker = null;
                    // Last: native speech, archive commits/finalization and wake lock
                    // are finished before deletion, recovery or restart sees idle.
                    active = false;
                    RefinementJobService.schedule(this);
                    if (returnToControls && fatalReason == null && !disconnectRequested) startControls();
                    else finishDisconnected();
                }
            });
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stopped");
    }
    private void awaitTransportStop() {
        if (!connectEnqueued) return;
        boolean interrupted = false;
        // Never hold LIFECYCLE while waiting: final BLE callbacks take it. If
        // Stop beat connect dispatch, stopInternal still emits onGap before exit.
        while (true) {
            try { transportThreadKnown.await(); break; }
            catch (InterruptedException failure) { interrupted = true; }
        }
        Thread closing = transportWorker;
        while (closing != null && closing.isAlive()) {
            try { closing.join(); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted && fatalReason == null) fatalReason = "Bluetooth teardown was interrupted";
    }
    private void setProgress(String value) {
        synchronized (LIFECYCLE) { state = value; updateNotification(); }
    }
    private void updateRecordingState() {
        if (!receivedPcm || cancelled || recovering) return;
        setProgress(speechIssue == null ? "Recording Omi — offline transcription"
                : "Recording Omi — audio only (" + speechIssue + ")");
    }
    private void speechFailed(String reason) {
        synchronized (LIFECYCLE) { speechIssue = reason; publishPartial(""); updateRecordingState(); }
    }
    /** Stop/errors and preview writes share a boundary: late ASR cannot revive it. */
    private void publishPartial(String text) {
        synchronized (LIFECYCLE) {
            display.partial(cancelled || recovering || speechIssue != null || fatalReason != null ? "" : text);
            partial = display.snapshot().partial;
        }
    }
    private static float rms(byte[] pcm) {
        double sum = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            sum += (double) sample * sample;
        }
        return (float) Math.min(1.0, Math.sqrt(sum / (pcm.length / 2)) / 32768.0);
    }
    private void closeRecognizer(PreviewRecognizer value) {
        if (value == null) return;
        try { value.close(); }
        catch (RuntimeException | LinkageError failure) { speechIssue = "speech engine cleanup failed"; }
    }
    private void closeModel(PreviewModel value) {
        if (value == null) return;
        try { value.close(); }
        catch (RuntimeException | LinkageError failure) { speechIssue = "speech model cleanup failed"; }
    }

    private Notification notification() {
        Intent stop = new Intent(this, OmiCaptureService.class).setAction(controlsActive ? ACTION_DISCONNECT : ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wave).setContentTitle("NotTheOmiAIApp · Omi")
                .setContentText(notificationText()).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, controlsActive ? "Disconnect" : "Stop & save", stopAction).build());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 4, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void updateNotification() {
        synchronized (LIFECYCLE) {
            if (!foreground || notificationText().equals(lastNotificationState)) return;
            // Startup diagnostics may arrive at packet frequency. Only foreground
            // text changes need Binder IPC; transport diagnostics still reach the UI.
            try {
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                lastNotificationState = notificationText();
            } catch (RuntimeException ignored) {
                // Do not cache failed publication. A later update can retry; the
                // periodic visibility/permission check still stops hidden capture.
            }
        }
    }
    private String notificationText() { return controlsActive ? controlsState : state; }
    private void endForeground() {
        if (!foreground) return;
        try { stopForeground(STOP_FOREGROUND_REMOVE); }
        finally { foreground = false; lastNotificationState = null; }
    }

    /** Owns Vosk preview; archive writes end on join OR the output retirement fence. */
    private final class SpeechWorker extends Thread {
        private final Recordings recordings;
        private String id; // Switched only with the speech FIFO idle, under queue.
        private final PreviewModel model;
        private final PreviewRecognizer recognizer;
        private final Object nativeLifetime = new Object();
        private boolean nativeClosed;
        private final ArrayDeque<OmiPcmQueue.Event> queue = new ArrayDeque<>();
        // Never hold output across any native call. Lock order: output, then queue.
        private final Object output = new Object();
        private final ArrayDeque<OmiPcmQueue.Event> pendingControls = new ArrayDeque<>();
        private volatile boolean retired;
        private int bytes;
        private boolean acceptingAudio = true, closing, failed, processing;
        SpeechWorker(Recordings recordings, String id, PreviewModel model, PreviewRecognizer recognizer) {
            super("omi-offline-speech");
            this.recordings = recordings; this.id = id; this.model = model; this.recognizer = recognizer;
        }
        void offerAudio(byte[] pcm) {
            boolean overloaded = false;
            synchronized (queue) {
                if (!acceptingAudio || closing || failed) return;
                if (queue.size() >= ASR_MAX_EVENTS || bytes + pcm.length > ASR_MAX_BYTES) {
                    acceptingAudio = false; overloaded = true;
                } else {
                    queue.addLast(new OmiPcmQueue.Event(pcm.clone(), null, false));
                    bytes += pcm.length; queue.notifyAll();
                }
            }
            if (overloaded) speechFailed("speech processing fell behind; transcript may be incomplete");
        }
        boolean offerMarker(String marker) {
            synchronized (output) {
                synchronized (queue) {
                    if (retired || closing || failed || queue.size() >= ASR_MAX_EVENTS) return false;
                    OmiPcmQueue.Event event = new OmiPcmQueue.Event(null, marker, false);
                    pendingControls.addLast(event); queue.addLast(event); queue.notifyAll(); return true;
                }
            }
        }
        /** Archive worker is the sole producer; idle stays idle until it offers PCM. */
        boolean awaitIdle() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASR_ROLLOVER_WAIT_MS);
            synchronized (queue) {
                while (!failed && (processing || !queue.isEmpty())) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(queue, remaining);
                }
                return !failed;
            }
        }
        void useRecording(String nextId) {
            synchronized (output) {
                synchronized (queue) {
                    if (retired || processing || !queue.isEmpty() || closing || failed || !pendingControls.isEmpty()) {
                        throw new IllegalStateException("Speech must be idle before archive rollover");
                    }
                    id = nextId;
                }
            }
        }
        /** Archive thread takes pending controls once; native results can no longer write. */
        void retireToArchive() throws Exception {
            synchronized (output) {
                if (retired) return;
                retired = true;
                synchronized (nativeLifetime) {
                    // Sticky, nonblocking cancellation; inference's owner alone closes.
                    if (!nativeClosed) model.cancel();
                }
                synchronized (queue) {
                    closing = true; acceptingAudio = false;
                    for (OmiPcmQueue.Event pending : queue) if (pending.pcm != null) Arrays.fill(pending.pcm, (byte) 0);
                    queue.clear(); bytes = 0; queue.notifyAll();
                }
                // Never retry an ambiguously failed transcript commit.
                if (fatalReason != null) return;
                for (OmiPcmQueue.Event control : pendingControls) {
                    recordings.appendText(id, control.marker); display.finalized(control.marker);
                }
                pendingControls.clear();
            }
        }
        @Override public void run() {
            boolean nativeUsable = true;
            OmiPcmQueue.Event event = null;
            try {
                while (true) {
                    synchronized (queue) {
                        while (queue.isEmpty() && !closing) queue.wait(100);
                        if (queue.isEmpty() && closing) break;
                        event = queue.removeFirst();
                        processing = true;
                        if (event.pcm != null) bytes -= event.pcm.length;
                    }
                    if (event.pcm != null) {
                        if (nativeUsable) {
                            try {
                                if (recognizer.acceptWaveForm(event.pcm, event.pcm.length)) {
                                    appendResult(recognizer.getResult()); publishPartial("");
                                } else if (!retired) {
                                    publishPartial(new JSONObject(recognizer.getPartialResult())
                                            .optString("partial", "").trim());
                                }
                            } catch (TranscriptStorageException storageFailure) { throw storageFailure; }
                            catch (Exception | LinkageError nativeFailure) {
                                nativeUsable = false;
                                synchronized (queue) { acceptingAudio = false; }
                                speechFailed("offline speech failed; transcript may be incomplete");
                            }
                        }
                        Arrays.fill(event.pcm, (byte) 0);
                    } else {
                        if (nativeUsable) {
                            try { appendResult(recognizer.getFinalResult()); recognizer.reset(); publishPartial(""); }
                            catch (TranscriptStorageException storageFailure) { throw storageFailure; }
                            catch (Exception | LinkageError nativeFailure) {
                                nativeUsable = false;
                                synchronized (queue) { acceptingAudio = false; }
                                speechFailed("offline speech failed; transcript may be incomplete");
                            }
                        }
                        synchronized (output) {
                            if (!retired) {
                                appendText(event.marker); pendingControls.remove(event);
                            }
                        }
                    }
                    event = null;
                    synchronized (queue) { processing = false; queue.notifyAll(); }
                }
                if (!retired && id != null) {
                    if (nativeUsable) appendResult(recognizer.getFinalResult());
                }
            } catch (TranscriptStorageException storageFailure) {
                stopCapture("Encrypted transcript storage unavailable or full; captured audio retained", GAP_MARKER);
            } catch (Exception | LinkageError nativeFailure) {
                speechFailed("offline speech failed; transcript may be incomplete");
            } finally {
                if (event != null && event.pcm != null) Arrays.fill(event.pcm, (byte) 0);
                synchronized (queue) {
                    acceptingAudio = false; closing = failed = true; processing = false;
                    for (OmiPcmQueue.Event pending : queue) if (pending.pcm != null) Arrays.fill(pending.pcm, (byte) 0);
                    queue.clear(); bytes = 0;
                    queue.notifyAll();
                }
                synchronized (nativeLifetime) {
                    closeRecognizer(recognizer); closeModel(model);
                    nativeClosed = true;
                }
                publishPartial("");
            }
        }
        private void publishPartial(String text) {
            synchronized (output) {
                if (!retired) OmiCaptureService.this.publishPartial(text);
            }
        }
        private void speechFailed(String reason) {
            synchronized (output) {
                if (!retired) OmiCaptureService.this.speechFailed(reason);
            }
        }
        private void appendResult(String json) throws Exception {
            String text = new JSONObject(json).optString("text", "").trim();
            if (!text.isEmpty()) appendText(text);
        }
        private void appendText(String text) throws TranscriptStorageException {
            synchronized (output) {
                if (retired || id == null) return;
                try { recordings.appendText(id, text); }
                catch (Exception storageFailure) {
                    // Publish storage failure inside the fence before retirement
                    // can take controls, so failed commits are never retried.
                    stopCapture("Encrypted transcript storage unavailable or full; captured audio retained", GAP_MARKER);
                    throw new TranscriptStorageException();
                }
                display.finalized(text);
            }
        }
        void finishAndJoin() {
            synchronized (queue) { closing = true; acceptingAudio = false; queue.notifyAll(); }
            boolean interrupted = false;
            while (isAlive()) {
                if (!retired && System.nanoTime() >= stopDeadlineNanos) {
                    speechFailed("offline speech drain timed out; transcript may be incomplete");
                    try { retireToArchive(); }
                    catch (Exception storageFailure) {
                        stopCapture("Encrypted transcript storage unavailable or full; captured audio retained", GAP_MARKER);
                    }
                }
                try { join(100); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            if (interrupted && fatalReason == null) fatalReason = "Saving was interrupted";
        }
    }
    private static final class TranscriptStorageException extends Exception { }
}
