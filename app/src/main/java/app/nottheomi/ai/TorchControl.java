package app.nottheomi.ai;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Flashlight only: never opens a camera, creates a capture session or requests permission.
 * The UI owns the optional CAMERA permission prompt. A successful setTorchMode call is a request,
 * not proof that the torch is on/off; only TorchCallback updates the observed state.
 */
public final class TorchControl {
    private static Controller singleton;
    private static final String NO_CONTEXT = "Flashlight unavailable; application context missing";
    static final String NEED_PERMISSION = "Flashlight permission denied; enable Camera permission in Settings";
    static final long CALLBACK_TIMEOUT_MS = 5000;
    static final long RETRY_MS = 1000;
    static final long IDLE_MS = 60000;

    private TorchControl() {}

    /**
     * Requests one toggle. If the initial state is unknown, waits at most five seconds for the
     * first callback before choosing the opposite state. Repeated presses while pending coalesce;
     * a denial, unavailable callback or timeout cancels the intent (there is no automatic retry).
     */
    public static String toggle(Context context) {
        Controller controller = controller(context);
        return controller == null ? NO_CONTEXT : controller.toggle();
    }

    /** Starts read-only monitoring; never requests permission or changes the torch by itself. */
    public static String status(Context context) {
        Controller controller = controller(context);
        return controller == null ? NO_CONTEXT : controller.status();
    }

    /** A runtime permission check only; it does not initialize camera monitoring. */
    public static boolean permissionGranted(Context context) {
        if (context == null) return false;
        try {
            return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static synchronized Controller controller(Context context) {
        if (context == null) return null;
        if (singleton == null) {
            // Never retain an Activity/Service as a fallback when its application is unavailable.
            Context application = context.getApplicationContext();
            if (application == null) return null;
            Handler handler = new Handler(Looper.getMainLooper());
            singleton = new Controller(new AndroidBackend(application, handler), new Scheduler() {
                @Override public long now() { return SystemClock.elapsedRealtime(); }
                @Override public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
                @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
            });
        }
        return singleton;
    }

    // Instance-local seams: tests never replace the singleton, grant permission or use real hardware.
    interface Backend {
        boolean permissionGranted();
        String findFlashCamera() throws CameraAccessException;
        void register(CameraManager.TorchCallback callback);
        void unregister(CameraManager.TorchCallback callback);
        void setTorch(String cameraId, boolean enabled) throws CameraAccessException;
    }

    interface Scheduler {
        long now();
        void post(Runnable task, long delay);
        void cancel(Runnable task);
    }

    static final class Controller {
        private enum Observed { UNKNOWN, ON, OFF, UNAVAILABLE }
        private final Backend backend;
        private final Scheduler scheduler;
        private CameraManager.TorchCallback callback;
        private CameraManager.TorchCallback cleanupPending;
        private String cameraId;
        private Observed observed = Observed.UNKNOWN;
        private Boolean requested;
        private boolean queuedToggle;
        private String problem;
        private long retryAt;
        private long deadline = -1;
        private long idleAt = -1;
        private final Runnable watchdog = this::checkDeadline;
        private final Runnable idleCleanup = this::checkIdle;

        Controller(Backend backend, Scheduler scheduler) {
            this.backend = backend;
            this.scheduler = scheduler;
        }

        synchronized String status() {
            checkDeadline();
            ensureMonitoring();
            touch();
            return describe();
        }

        synchronized String toggle() {
            checkDeadline();
            if (ensureMonitoring()) {
                if (requested == null && !queuedToggle) {
                    if (observed == Observed.UNKNOWN) {
                        queuedToggle = true;
                        armDeadline();
                    } else if (observed != Observed.UNAVAILABLE) {
                        request(observed == Observed.OFF);
                    }
                }
            }
            touch();
            return describe();
        }

        private boolean allowed() {
            try { return backend.permissionGranted(); }
            catch (RuntimeException denied) { return false; }
        }

        private boolean ensureMonitoring() {
            if (!allowed()) {
                fail(NEED_PERMISSION, 0);
                return false;
            }
            if (scheduler.now() < retryAt) return false;
            // If unregister failed, keep its identity for a later cleanup attempt. Never accumulate
            // live callback registrations by blindly retrying registration after a partial failure.
            if (!finishCleanup()) {
                problem = "Flashlight unavailable; camera monitor cleanup pending";
                retryAt = scheduler.now() + RETRY_MS;
                return false;
            }
            if (callback != null) return true;
            try {
                cameraId = backend.findFlashCamera();
                if (cameraId == null) {
                    fail("Flashlight unsupported on this device", IDLE_MS);
                    return false;
                }
                observed = Observed.UNKNOWN;
                problem = null;
                callback = new CameraManager.TorchCallback() {
                    @Override public void onTorchModeChanged(String id, boolean enabled) {
                        modeChanged(this, id, enabled);
                    }
                    @Override public void onTorchModeUnavailable(String id) {
                        unavailable(this, id);
                    }
                };
                armDeadline();
                // Identity is assigned before registration so even synchronous test callbacks work.
                backend.register(callback);
                return callback != null;
            } catch (CameraAccessException | RuntimeException failure) {
                fail(message(failure), RETRY_MS);
                return false;
            }
        }

        private synchronized void modeChanged(CameraManager.TorchCallback source, String id, boolean enabled) {
            if (source != callback || !cameraId.equals(id)) return;
            if (!allowed()) { fail(NEED_PERMISSION, 0); return; }
            checkDeadline();
            if (source != callback) return;
            observed = enabled ? Observed.ON : Observed.OFF;
            problem = null;
            if (requested != null) {
                if (requested == enabled) {
                    requested = null;
                    cancelDeadline();
                }
            } else {
                cancelDeadline();
            }
            if (queuedToggle) {
                queuedToggle = false;
                request(!enabled);
            }
        }

        private synchronized void unavailable(CameraManager.TorchCallback source, String id) {
            if (source != callback || !cameraId.equals(id)) return;
            if (!allowed()) { fail(NEED_PERMISSION, 0); return; }
            observed = Observed.UNAVAILABLE;
            requested = null;
            queuedToggle = false;
            problem = null;
            cancelDeadline();
            // Keep this registration: CameraManager reports availability recovery via the same
            // callback. Recovery is observation only and must not replay the cancelled toggle.
        }

        private void request(boolean enabled) {
            if (!allowed()) { fail(NEED_PERMISSION, 0); return; }
            requested = enabled;
            armDeadline();
            try {
                backend.setTorch(cameraId, enabled);
            } catch (CameraAccessException | RuntimeException failure) {
                // Even a failed IPC may have reached the camera. Forget cached state and await a
                // fresh registration; never report that the previous state is still current.
                fail(message(failure), RETRY_MS);
            }
        }

        private void armDeadline() {
            scheduler.cancel(watchdog);
            deadline = scheduler.now() + CALLBACK_TIMEOUT_MS;
            scheduler.post(watchdog, CALLBACK_TIMEOUT_MS);
        }

        private void cancelDeadline() {
            deadline = -1;
            scheduler.cancel(watchdog);
        }

        private synchronized void checkDeadline() {
            if (deadline < 0) return;
            long remaining = deadline - scheduler.now();
            if (remaining <= 0) {
                fail("Flashlight state unknown; camera callback timed out", RETRY_MS);
            }
        }

        private void touch() {
            scheduler.cancel(idleCleanup);
            idleAt = callback == null ? -1 : scheduler.now() + IDLE_MS;
            if (idleAt >= 0) scheduler.post(idleCleanup, IDLE_MS);
        }

        private synchronized void checkIdle() {
            if (idleAt >= 0 && scheduler.now() >= idleAt) close();
        }

        /** Unregister observation only, never force the torch off or retain stale observed state. */
        synchronized void close() {
            invalidate();
            problem = null;
            retryAt = 0;
        }

        private void fail(String description, long retryDelay) {
            invalidate();
            problem = description;
            retryAt = scheduler.now() + retryDelay;
        }

        private void invalidate() {
            CameraManager.TorchCallback old = callback;
            callback = null; // Fence already-queued callbacks before unregistering, even if it throws.
            cameraId = null;
            observed = Observed.UNKNOWN;
            requested = null;
            queuedToggle = false;
            cancelDeadline();
            scheduler.cancel(idleCleanup);
            idleAt = -1;
            if (old != null) cleanupPending = old;
            finishCleanup();
        }

        private boolean finishCleanup() {
            if (cleanupPending == null) return true;
            try {
                backend.unregister(cleanupPending);
                cleanupPending = null;
                return true;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private String describe() {
            if (problem != null) return problem;
            if (queuedToggle) return "Flashlight toggle requested; waiting for initial camera callback (state unknown)";
            if (requested != null) {
                return "Flashlight " + (requested ? "on" : "off") + " requested; last observed "
                    + (observed == Observed.ON ? "on" : "off") + " (awaiting camera callback)";
            }
            switch (observed) {
                case ON: return "Flashlight on (confirmed by camera callback)";
                case OFF: return "Flashlight off (confirmed by camera callback)";
                case UNAVAILABLE: return "Flashlight unavailable; camera busy or unavailable (state unknown)";
                default: return "Flashlight state unknown; waiting for camera callback";
            }
        }
    }

    private static String message(Exception failure) {
        if (failure instanceof SecurityException) return NEED_PERMISSION;
        if (failure instanceof CameraAccessException) {
            switch (((CameraAccessException) failure).getReason()) {
                case CameraAccessException.CAMERA_IN_USE:
                case CameraAccessException.MAX_CAMERAS_IN_USE:
                    return "Flashlight unavailable; camera is busy (state unknown)";
                case CameraAccessException.CAMERA_DISABLED:
                    return "Flashlight unavailable; camera disabled by device policy";
                case CameraAccessException.CAMERA_DISCONNECTED:
                    return "Flashlight unavailable; camera disconnected (state unknown)";
                default: return "Flashlight unavailable; camera service error (state unknown)";
            }
        }
        if (failure instanceof UnsupportedOperationException) return "Flashlight unsupported on this device";
        if (failure instanceof IllegalArgumentException) return "Flashlight unavailable; flash camera no longer available";
        return "Flashlight unavailable; camera service error (state unknown)";
    }

    private static final class AndroidBackend implements Backend {
        private final Context application;
        private final Handler handler;
        private CameraManager manager;

        AndroidBackend(Context application, Handler handler) {
            this.application = application;
            this.handler = handler;
        }

        @Override public boolean permissionGranted() { return TorchControl.permissionGranted(application); }

        @Override public String findFlashCamera() throws CameraAccessException {
            manager = application.getSystemService(CameraManager.class);
            if (manager == null) throw new UnsupportedOperationException("No camera service");
            String fallback = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics camera = manager.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(camera.get(CameraCharacteristics.LENS_FACING))) {
                    return id;
                }
                if (fallback == null) fallback = id;
            }
            return fallback;
        }

        @Override public void register(CameraManager.TorchCallback callback) {
            manager.registerTorchCallback(callback, handler);
        }

        @Override public void unregister(CameraManager.TorchCallback callback) {
            manager.unregisterTorchCallback(callback);
        }

        @Override public void setTorch(String cameraId, boolean enabled) throws CameraAccessException {
            manager.setTorchMode(cameraId, enabled);
        }
    }
}
