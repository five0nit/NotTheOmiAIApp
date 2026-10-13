package app.nottheomi.ai;

import android.Manifest;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.test.InstrumentationTestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emulator-only deterministic tests. No camera, permission grants, capture, BLE or service starts. */
@SuppressWarnings("deprecation")
public final class TorchControlTest extends InstrumentationTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator required; never run on a physical phone",
            "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
    }

    public void testStatusStartsOneReadOnlyMonitorAndDoesNotAssumeOff() {
        Fixture f = new Fixture();
        contains(f.control.status(), "state unknown");
        contains(f.control.status(), "state unknown");
        assertEquals(1, f.backend.registrations);
        assertEquals(0, f.backend.requests.size());
        f.backend.mode(true);
        assertEquals("Flashlight on (confirmed by camera callback)", f.control.status());
        f.backend.mode(false);
        assertEquals("Flashlight off (confirmed by camera callback)", f.control.status());
        assertEquals(0, f.backend.requests.size());
    }

    public void testColdToggleWaitsForInitialOffThenRequestsOnOnce() {
        Fixture f = new Fixture();
        contains(f.control.toggle(), "toggle requested");
        assertEquals(0, f.backend.requests.size());
        f.backend.mode(false);
        assertEquals(1, f.backend.requests.size());
        assertEquals(Boolean.TRUE, f.backend.requests.get(0));
        contains(f.control.status(), "on requested; last observed off");
        f.backend.mode(true);
        assertEquals("Flashlight on (confirmed by camera callback)", f.control.status());
    }

    public void testColdToggleUsesInitialOnRatherThanAssumingOff() {
        Fixture f = new Fixture();
        f.control.toggle();
        f.backend.mode(true);
        assertEquals(Boolean.FALSE, f.backend.requests.get(0));
        contains(f.control.status(), "off requested; last observed on");
        f.backend.mode(false);
        contains(f.control.status(), "off (confirmed");
    }

    public void testRapidTogglesCoalesceUntilCallbackConfirmation() {
        Fixture f = new Fixture();
        f.control.toggle();
        f.control.toggle();
        f.backend.mode(false);
        f.control.toggle();
        f.control.toggle();
        assertEquals(1, f.backend.requests.size());
        f.backend.mode(true);
        f.control.toggle();
        assertEquals(2, f.backend.requests.size());
        assertEquals(Boolean.FALSE, f.backend.requests.get(1));
    }

    public void testConflictingCallbackIsObservedButNotConfirmationOfRequest() {
        Fixture f = observed(false);
        f.control.toggle();
        f.backend.mode(false);
        contains(f.control.status(), "on requested; last observed off");
        f.backend.mode(true);
        contains(f.control.status(), "on (confirmed");
        // An external torch change is authoritative, not the app's last requested state.
        f.backend.mode(false);
        f.control.toggle();
        assertEquals(Boolean.TRUE, f.backend.requests.get(1));
    }

    public void testCallbacksForOtherCamerasCannotSetStateOrConsumeToggle() {
        Fixture f = new Fixture();
        f.control.toggle();
        f.backend.latest.onTorchModeChanged("front-without-selected-flash", true);
        f.backend.latest.onTorchModeUnavailable("different-camera");
        contains(f.control.status(), "state unknown");
        assertEquals(0, f.backend.requests.size());
        f.backend.mode(true);
        assertEquals(Boolean.FALSE, f.backend.requests.get(0));
    }

    public void testPermissionDeniedDoesNotDiscoverRegisterOrRequest() {
        Fixture f = new Fixture();
        f.backend.granted = false;
        assertEquals(TorchControl.NEED_PERMISSION, f.control.status());
        assertEquals(TorchControl.NEED_PERMISSION, f.control.toggle());
        assertEquals(0, f.backend.discoveries);
        assertEquals(0, f.backend.registrations);
        assertEquals(0, f.backend.requests.size());
    }

    public void testPermissionGrantStartsMonitoringButDoesNotReplayDeniedToggle() {
        Fixture f = new Fixture();
        f.backend.granted = false;
        f.control.toggle();
        f.backend.granted = true;
        contains(f.control.status(), "state unknown");
        f.backend.mode(false);
        assertEquals(0, f.backend.requests.size());
        contains(f.control.toggle(), "on requested");
    }

    public void testPermissionRevokedBeforeQueuedCallbackCancelsIntent() {
        Fixture f = new Fixture();
        f.control.toggle();
        CameraManager.TorchCallback old = f.backend.latest;
        f.backend.granted = false;
        f.backend.mode(false);
        assertEquals(TorchControl.NEED_PERMISSION, f.control.status());
        assertEquals(1, f.backend.unregistrations);
        assertEquals(0, f.backend.requests.size());
        f.backend.granted = true;
        f.control.status();
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "state unknown");
        f.backend.mode(false);
        assertEquals(0, f.backend.requests.size());
    }

    public void testPermissionRevocationClearsConfirmedStateAndBlocksToggle() {
        Fixture f = observed(true);
        f.backend.granted = false;
        assertEquals(TorchControl.NEED_PERMISSION, f.control.toggle());
        assertEquals(1, f.backend.unregistrations);
        assertEquals(0, f.backend.requests.size());
    }

    public void testPermissionCheckFailureFailsClosed() {
        Fixture f = new Fixture();
        f.backend.permissionFailure = new SecurityException("synthetic denial");
        assertEquals(TorchControl.NEED_PERMISSION, f.control.toggle());
        assertEquals(0, f.backend.registrations);
    }

    public void testUnsupportedDeviceNeverRegistersOrChangesTorch() {
        Fixture f = new Fixture();
        f.backend.cameraId = null;
        contains(f.control.status(), "unsupported");
        contains(f.control.toggle(), "unsupported");
        assertEquals(1, f.backend.discoveries);
        assertEquals(0, f.backend.registrations);
        assertEquals(0, f.backend.requests.size());
    }

    public void testUnavailableCallbackErasesConfirmedStateAndBlocksRequests() {
        Fixture f = observed(true);
        f.backend.unavailable();
        contains(f.control.status(), "unavailable (state unknown)");
        contains(f.control.toggle(), "unavailable");
        assertEquals(0, f.backend.requests.size());
        f.backend.mode(false);
        contains(f.control.status(), "off (confirmed");
        assertEquals(1, f.backend.registrations);
        assertEquals(0, f.backend.requests.size());
        f.control.toggle();
        assertEquals(Boolean.TRUE, f.backend.requests.get(0));
    }

    public void testUnavailableCancelsQueuedAndInFlightRequestsWithoutReplay() {
        Fixture queued = new Fixture();
        queued.control.toggle();
        queued.backend.unavailable();
        queued.backend.mode(false);
        assertEquals(0, queued.backend.requests.size());
        Fixture inFlight = observed(false);
        inFlight.control.toggle();
        inFlight.backend.unavailable();
        inFlight.backend.mode(false);
        contains(inFlight.control.status(), "off (confirmed");
        assertEquals(1, inFlight.backend.requests.size());
    }

    public void testBusyFailureDropsCachedStateAndReregistersReadOnly() {
        Fixture f = observed(false);
        CameraManager.TorchCallback old = f.backend.latest;
        f.backend.setFailure = new CameraAccessException(CameraAccessException.CAMERA_IN_USE);
        contains(f.control.toggle(), "camera is busy (state unknown)");
        assertEquals(1, f.backend.unregistrations);
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "camera is busy");
        assertEquals(1, f.backend.registrations);
        f.backend.setFailure = null;
        f.time.advance(TorchControl.RETRY_MS);
        contains(f.control.status(), "state unknown");
        assertEquals(2, f.backend.registrations);
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "state unknown");
        f.backend.mode(true);
        contains(f.control.status(), "on (confirmed");
        assertEquals(1, f.backend.requests.size());
    }

    public void testCameraFailuresAreGuardedAndNeverClaimSuccess() {
        int[] reasons = { CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE,
            CameraAccessException.CAMERA_DISABLED, CameraAccessException.CAMERA_DISCONNECTED,
            CameraAccessException.CAMERA_ERROR };
        String[] descriptions = { "busy", "busy", "device policy", "disconnected", "service error" };
        for (int i = 0; i < reasons.length; i++) {
            Fixture f = observed(false);
            f.backend.setFailure = new CameraAccessException(reasons[i]);
            String result = f.control.toggle();
            contains(result, descriptions[i]);
            assertFalse(result.contains("confirmed"));
            assertEquals(1, f.backend.unregistrations);
        }
    }

    public void testSecurityAndRuntimeSetFailuresAreGuarded() {
        Exception[] failures = { new SecurityException("denied"), new IllegalArgumentException("gone"),
            new UnsupportedOperationException("no torch"), new IllegalStateException("service died") };
        String[] descriptions = { "permission denied", "no longer available", "unsupported", "service error" };
        for (int i = 0; i < failures.length; i++) {
            Fixture f = observed(false);
            f.backend.setFailure = failures[i];
            contains(f.control.toggle(), descriptions[i]);
            assertEquals(1, f.backend.unregistrations);
        }
    }

    public void testDiscoveryFailureCanBeRetriedWithoutAnyStaleAction() {
        Fixture f = new Fixture();
        f.backend.discoveryFailure = new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED);
        contains(f.control.toggle(), "disconnected");
        assertEquals(0, f.backend.registrations);
        f.backend.discoveryFailure = null;
        f.time.advance(TorchControl.RETRY_MS);
        f.control.status();
        f.backend.mode(false);
        assertEquals(0, f.backend.requests.size());
    }

    public void testPartialRegistrationFailureIsCleanedUpAndCanRegisterAgain() {
        Fixture f = new Fixture();
        f.backend.registrationFailure = new IllegalStateException("partial registration");
        contains(f.control.toggle(), "service error");
        CameraManager.TorchCallback old = f.backend.latest;
        assertEquals(1, f.backend.unregistrations);
        f.backend.registrationFailure = null;
        f.time.advance(TorchControl.RETRY_MS);
        f.control.status();
        assertEquals(2, f.backend.registrations);
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "state unknown");
        f.backend.mode(false);
        assertEquals(0, f.backend.requests.size());
    }

    public void testUnregisterFailureFencesOldCallbackAndPreventsRegistrationLeaks() {
        Fixture f = observed(false);
        CameraManager.TorchCallback old = f.backend.latest;
        f.backend.unregisterFailure = new IllegalStateException("cleanup unavailable");
        f.control.close();
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "cleanup pending");
        assertEquals(1, f.backend.registrations);
        f.backend.unregisterFailure = null;
        f.time.advance(TorchControl.RETRY_MS);
        contains(f.control.status(), "state unknown");
        assertEquals(2, f.backend.registrations);
        old.onTorchModeChanged("rear", true);
        contains(f.control.status(), "state unknown");
    }

    public void testMissingInitialCallbackTimesOutAndDoesNotExecuteLateToggle() {
        Fixture f = new Fixture();
        f.control.toggle();
        CameraManager.TorchCallback old = f.backend.latest;
        f.time.advance(TorchControl.CALLBACK_TIMEOUT_MS);
        contains(f.control.status(), "timed out");
        old.onTorchModeChanged("rear", false);
        assertEquals(0, f.backend.requests.size());
        f.time.advance(TorchControl.RETRY_MS);
        f.control.status();
        f.backend.mode(false);
        assertEquals(0, f.backend.requests.size());
    }

    public void testReadOnlyMonitoringAlsoRecoversFromMissingInitialCallback() {
        Fixture f = new Fixture();
        f.control.status();
        f.time.advance(TorchControl.CALLBACK_TIMEOUT_MS);
        contains(f.control.status(), "timed out");
        f.time.advance(TorchControl.RETRY_MS);
        f.control.status();
        assertEquals(2, f.backend.registrations);
        assertEquals(0, f.backend.requests.size());
    }

    public void testConfirmationTimeoutDoesNotClaimRequestedStateOrRetryWrite() {
        Fixture f = observed(false);
        f.control.toggle();
        CameraManager.TorchCallback old = f.backend.latest;
        f.time.advance(TorchControl.CALLBACK_TIMEOUT_MS);
        contains(f.control.status(), "state unknown; camera callback timed out");
        old.onTorchModeChanged("rear", true);
        f.time.advance(TorchControl.RETRY_MS);
        f.control.status();
        f.backend.mode(true);
        contains(f.control.status(), "on (confirmed");
        assertEquals(1, f.backend.requests.size());
    }

    public void testDeadlineIsEnforcedAtCallbackEvenIfTimerHasNotRun() {
        Fixture f = new Fixture();
        f.control.toggle();
        f.time.now += TorchControl.CALLBACK_TIMEOUT_MS;
        f.backend.mode(false);
        contains(f.control.status(), "timed out");
        assertEquals(0, f.backend.requests.size());
    }

    public void testRepeatedPressesDoNotExtendPendingDeadline() {
        Fixture f = new Fixture();
        f.control.toggle();
        f.time.advance(TorchControl.CALLBACK_TIMEOUT_MS - 1);
        f.control.toggle();
        f.time.advance(1);
        contains(f.control.status(), "timed out");
        assertEquals(0, f.backend.requests.size());
    }

    public void testIdleCleanupUnregistersWithoutChangingTorchAndReinitializes() {
        Fixture f = observed(true);
        CameraManager.TorchCallback old = f.backend.latest;
        f.time.advance(TorchControl.IDLE_MS);
        assertEquals(1, f.backend.unregistrations);
        assertEquals(0, f.time.tasks.size());
        assertEquals(0, f.backend.requests.size());
        contains(f.control.status(), "state unknown");
        assertEquals(2, f.backend.registrations);
        old.onTorchModeChanged("rear", false);
        contains(f.control.status(), "state unknown");
    }

    public void testStatusTickerKeepsSingleMonitorAndBoundedTimers() {
        Fixture f = observed(false);
        for (int i = 0; i < 100; i++) {
            f.time.advance(TorchControl.IDLE_MS / 2);
            contains(f.control.status(), "off (confirmed");
            assertEquals(1, f.time.tasks.size());
        }
        assertEquals(1, f.backend.registrations);
        assertEquals(0, f.backend.unregistrations);
        assertEquals(0, f.backend.requests.size());
    }

    public void testCloseIsIdempotentAndNeverChangesTorch() {
        Fixture f = observed(true);
        f.control.toggle();
        CameraManager.TorchCallback old = f.backend.latest;
        f.control.close();
        f.control.close();
        old.onTorchModeChanged("rear", false);
        assertEquals(1, f.backend.unregistrations);
        assertEquals(1, f.backend.requests.size());
        assertEquals(0, f.time.tasks.size());
        f.control.status();
        f.backend.mode(true);
        contains(f.control.status(), "on (confirmed");
        assertEquals(1, f.backend.requests.size());
    }

    public void testSynchronousCallbacksDoNotLoseConfirmation() {
        Fixture f = new Fixture();
        f.backend.initial = false;
        f.backend.confirmSynchronously = true;
        contains(f.control.toggle(), "on (confirmed");
        assertEquals(1, f.backend.requests.size());
        assertEquals(1, f.time.tasks.size());
    }

    public void testPermissionApiChecksOnlyCameraAndNeverAccessesCameraService() {
        final int[] checks = { 0 };
        ContextWrapper granted = new ContextWrapper(null) {
            @Override public int checkSelfPermission(String permission) {
                checks[0]++;
                assertEquals(Manifest.permission.CAMERA, permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            @Override public Object getSystemService(String name) {
                throw new AssertionError("Permission check must not touch a camera service");
            }
        };
        assertTrue(TorchControl.permissionGranted(granted));
        assertEquals(1, checks[0]);
        ContextWrapper denied = new ContextWrapper(null) {
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
        };
        assertFalse(TorchControl.permissionGranted(denied));
        ContextWrapper failed = new ContextWrapper(null) {
            @Override public int checkSelfPermission(String permission) { throw new SecurityException("denied"); }
        };
        assertFalse(TorchControl.permissionGranted(failed));
        assertFalse(TorchControl.permissionGranted(null));
    }

    public void testNullContextPublicApiIsGuarded() {
        contains(TorchControl.status(null), "application context missing");
        contains(TorchControl.toggle(null), "application context missing");
    }

    private static Fixture observed(boolean enabled) {
        Fixture f = new Fixture();
        f.control.status();
        f.backend.mode(enabled);
        return f;
    }

    private static void contains(String actual, String expected) {
        assertTrue("Expected '" + expected + "' in '" + actual + "'", actual.contains(expected));
    }

    private static final class Fixture {
        final FakeBackend backend = new FakeBackend();
        final FakeScheduler time = new FakeScheduler();
        final TorchControl.Controller control = new TorchControl.Controller(backend, time);
    }

    private static final class FakeScheduler implements TorchControl.Scheduler {
        long now;
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        @Override public long now() { return now; }
        @Override public void post(Runnable task, long delay) { tasks.put(task, now + delay); }
        @Override public void cancel(Runnable task) { tasks.remove(task); }
        void advance(long millis) {
            now += millis;
            // Run only due tasks, including tasks scheduled by other due tasks. No sleeping.
            while (true) {
                Runnable next = null;
                for (Map.Entry<Runnable, Long> task : tasks.entrySet()) {
                    if (task.getValue() <= now) { next = task.getKey(); break; }
                }
                if (next == null) return;
                tasks.remove(next);
                next.run();
            }
        }
    }

    private static final class FakeBackend implements TorchControl.Backend {
        boolean granted = true;
        String cameraId = "rear";
        int discoveries, registrations, unregistrations;
        Boolean initial;
        boolean confirmSynchronously;
        RuntimeException permissionFailure, registrationFailure, unregisterFailure;
        Exception discoveryFailure, setFailure;
        CameraManager.TorchCallback latest;
        final List<Boolean> requests = new ArrayList<>();

        @Override public boolean permissionGranted() {
            if (permissionFailure != null) throw permissionFailure;
            return granted;
        }
        @Override public String findFlashCamera() throws CameraAccessException {
            discoveries++;
            throwFailure(discoveryFailure);
            return cameraId;
        }
        @Override public void register(CameraManager.TorchCallback callback) {
            registrations++;
            latest = callback;
            if (registrationFailure != null) throw registrationFailure;
            if (initial != null) mode(initial);
        }
        @Override public void unregister(CameraManager.TorchCallback callback) {
            unregistrations++;
            if (unregisterFailure != null) throw unregisterFailure;
            // Keep the identity deliberately: tests exercise queued callbacks after unregister.
        }
        @Override public void setTorch(String id, boolean enabled) throws CameraAccessException {
            assertEquals(cameraId, id);
            requests.add(enabled);
            throwFailure(setFailure);
            if (confirmSynchronously) mode(enabled);
        }
        void mode(boolean enabled) { latest.onTorchModeChanged(cameraId, enabled); }
        void unavailable() { latest.onTorchModeUnavailable(cameraId); }
        private static void throwFailure(Exception failure) throws CameraAccessException {
            if (failure instanceof CameraAccessException) throw (CameraAccessException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure != null) throw new AssertionError(failure);
        }
    }
}
