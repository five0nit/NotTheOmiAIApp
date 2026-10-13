package app.nottheomi.ai;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Dedicated synthetic emulator only. No listener grants, real notification posts, BLE or audio. */
@SuppressWarnings("deprecation")
public final class NotificationControlsTest extends ActivityInstrumentationTestCase2<OmiSettingsActivity> {
    private static final String A = "synthetic.chat.app", B = "synthetic.calendar.app";
    private static final String PREFIX = "notification_vibrations_";
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private OmiSettingsActivity activity;
    private NotificationControls controls;
    private LinearLayout container;

    public NotificationControlsTest() { super(OmiSettingsActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator required", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertNoCapture();
        context = getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("omi", Context.MODE_PRIVATE);
        saved = new HashMap<>(prefs.getAll());
        assertFalse("This suite never grants listener access", NotificationVibrations.accessGranted(context));
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : saved.keySet()) if (key.startsWith(PREFIX)) editor.remove(key);
        assertTrue(editor.commit());
        NotificationVibrations.invalidate();
        setActivityInitialTouchMode(true);
    }

    @Override protected void tearDown() throws Exception {
        try {
            main(() -> {
                if (controls != null && dialog() != null) dialog().dismiss();
                if (activity != null) activity.finish();
            });
            if (prefs != null && saved != null) {
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : prefs.getAll().keySet()) if (key.startsWith(PREFIX)) editor.remove(key);
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    if (!entry.getKey().startsWith(PREFIX)) continue;
                    Object value = entry.getValue(); String key = entry.getKey();
                    if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof String) editor.putString(key, (String) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Float) editor.putFloat(key, (Float) value);
                    else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
                }
                assertTrue(editor.commit());
            }
            NotificationVibrations.invalidate();
            assertNoCapture();
        } finally { super.tearDown(); }
    }

    public void testDefaultsRemainOffAllAppsDoubleAndEmptySelectedBlocksAll() throws Exception {
        assertFalse(NotificationVibrations.enabled(context));
        assertEquals("all", NotificationVibrations.mode(context));
        assertEquals("double", NotificationVibrations.defaultPattern(context));
        assertTrue(NotificationVibrations.packageAllowed(context, A));
        assertFalse(NotificationVibrations.packageAllowed(context, context.getPackageName()));
        assertFalse(NotificationVibrations.packageAllowed(context, null));
        NotificationVibrations.setMode(context, "selected");
        assertTrue(NotificationVibrations.selectedPackages(context).isEmpty());
        assertFalse(NotificationVibrations.packageAllowed(context, A));
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setAppPattern(context, A, "triple");
        NotificationVibrations.setDefaultPattern(context, "long");
        Context recreated = context.createPackageContext(context.getPackageName(), 0);
        assertEquals("selected", NotificationVibrations.mode(recreated));
        assertEquals(Collections.singleton(A), NotificationVibrations.selectedPackages(recreated));
        assertEquals("triple", NotificationVibrations.patternForPackage(recreated, A));
        assertEquals("long", NotificationVibrations.patternForPackage(recreated, B));
        assertFalse(NotificationVibrations.enabled(recreated));
        assertFalse(NotificationVibrations.packageAllowed(recreated, B));
        NotificationVibrations.setMode(context, "all");
        assertTrue(NotificationVibrations.packageAllowed(context, B));
        assertEquals(Collections.singleton(A), NotificationVibrations.selectedPackages(context));
        NotificationVibrations.setMode(context, "selected");
        assertFalse(NotificationVibrations.packageAllowed(context, B));
    }

    public void testSelectionsAreDefensiveAndIdentifiersAreExact() {
        Set<String> input = new HashSet<>(Collections.singleton(A));
        NotificationVibrations.setSelectedPackages(context, input);
        input.add(B);
        assertEquals(Collections.singleton(A), NotificationVibrations.selectedPackages(context));
        try { NotificationVibrations.selectedPackages(context).add(B); fail("Mutable preference snapshot"); }
        catch (UnsupportedOperationException expected) {}
        for (String invalid : new String[]{"", " synthetic.chat.app", "synthetic.chat.app ", "chat/app", "a..b"}) {
            try { NotificationVibrations.setAppSelected(context, invalid, true); fail("Invalid package accepted"); }
            catch (IllegalArgumentException expected) {}
        }
        try { NotificationVibrations.setDefaultPattern(context, "DOUBLE"); fail("Invalid pattern accepted"); }
        catch (IllegalArgumentException expected) {}
        try { NotificationVibrations.setMode(context, "selected "); fail("Invalid mode accepted"); }
        catch (IllegalArgumentException expected) {}
        assertEquals(Collections.singleton(A), NotificationVibrations.selectedPackages(context));
        assertEquals("double", NotificationVibrations.defaultPattern(context));
    }

    public void testCorruptPreferencesFailClosedWithoutOverwritingUnrelatedSettings() {
        Map<String, Object> before = new HashMap<>(prefs.getAll());
        prefs.edit().putInt(NotificationVibrations.MODE_KEY, 7)
            .putString(NotificationVibrations.PACKAGES_KEY, "all")
            .putBoolean(NotificationVibrations.DEFAULT_PATTERN_KEY, true)
            .putInt(NotificationVibrations.APP_PATTERN_PREFIX + A, 42).commit();
        assertEquals("selected", NotificationVibrations.mode(context));
        assertFalse(NotificationVibrations.packageAllowed(context, A));
        assertEquals("double", NotificationVibrations.patternForPackage(context, A));
        assertEquals(7, prefs.getInt(NotificationVibrations.MODE_KEY, -1));
        prefs.edit().putString(NotificationVibrations.MODE_KEY, "unknown")
            .putStringSet(NotificationVibrations.PACKAGES_KEY, new HashSet<>(Arrays.asList(A, " invalid")))
            .putString(NotificationVibrations.DEFAULT_PATTERN_KEY, "unknown")
            .putString(NotificationVibrations.APP_PATTERN_PREFIX + A, "unknown").commit();
        assertTrue(NotificationVibrations.selectedPackages(context).isEmpty());
        assertFalse(NotificationVibrations.packageAllowed(context, A));
        assertEquals("double", NotificationVibrations.patternForPackage(context, A));
        NotificationVibrations.setSelectedPackages(context, Collections.singleton(A));
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setDefaultPattern(context, "short");
        assertTrue(NotificationVibrations.packageAllowed(context, A));
        assertEquals("short", NotificationVibrations.patternForPackage(context, A));
        Map<String, Object> after = new HashMap<>(prefs.getAll());
        after.keySet().removeIf(key -> key.startsWith(PREFIX));
        before.keySet().removeIf(key -> key.startsWith(PREFIX));
        assertEquals(before, after);
    }

    public void testAllConfigurationEditsCancelOldGuardsButIdempotentSettersDoNot() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setAppPattern(context, A, "triple");
        BooleanSupplier original = guard(A);
        long same = NotificationVibrations.configurationGeneration();
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setDefaultPattern(context, "double");
        NotificationVibrations.setAppPattern(context, A, "triple");
        assertEquals(same, NotificationVibrations.configurationGeneration());
        assertTrue(original.getAsBoolean());
        NotificationVibrations.setMode(context, "all");
        NotificationVibrations.setMode(context, "selected");
        assertFalse(original.getAsBoolean());
        BooleanSupplier list = guard(A);
        NotificationVibrations.setAppSelected(context, A, false);
        NotificationVibrations.setAppSelected(context, A, true);
        assertFalse(list.getAsBoolean());
        BooleanSupplier pattern = guard(A);
        NotificationVibrations.setAppPattern(context, A, "long");
        NotificationVibrations.setAppPattern(context, A, "triple");
        assertFalse(pattern.getAsBoolean());
        BooleanSupplier defaults = guard(A);
        NotificationVibrations.setDefaultPattern(context, "short");
        NotificationVibrations.setDefaultPattern(context, "double");
        assertFalse(defaults.getAsBoolean());
        BooleanSupplier removedOverride = guard(A);
        NotificationVibrations.setAppPattern(context, A, null);
        assertFalse(removedOverride.getAsBoolean());
        assertEquals("double", NotificationVibrations.patternForPackage(context, A));
    }

    public void testPackageGuardRechecksEligibilityAndInitiallyBlockedNeverArms() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setMode(context, "selected");
        BooleanSupplier blocked = guard(A);
        assertFalse(blocked.getAsBoolean());
        NotificationVibrations.setAppSelected(context, A, true);
        assertFalse(blocked.getAsBoolean());
        BooleanSupplier eligible = guard(A);
        assertTrue(eligible.getAsBoolean());
        // Even unexpected preference corruption outside the setters cannot bypass the write-time check.
        prefs.edit().putString(NotificationVibrations.PACKAGES_KEY, "corrupt").commit();
        assertFalse(eligible.getAsBoolean());
        assertFalse(guard(B).getAsBoolean());
    }

    public void testActualPostedCallbacksConsumeBlockedDeliveryWithoutReplayOnAllow() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setMode(context, "selected");
        Fixture f = new Fixture(); f.connect();
        StatusBarNotification first = f.card(A, "same-card", false);
        f.post(first);
        assertEquals(0, f.pulses.size());
        assertEquals(1, f.gate.rememberedCount());
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setAppPattern(context, A, "triple");
        f.post(first);
        assertEquals("Allowing an app must not replay an already-consumed callback", 0, f.pulses.size());
        f.advance();
        f.post(f.card(A, "same-card", false));
        assertEquals(1, f.pulses.size());
        assertEquals("triple", f.pulses.get(0).pattern);
        assertTrue(f.pulses.get(0).guard.getAsBoolean());
        f.advance(); f.post(f.card(B, "blocked-other", false));
        assertEquals(1, f.pulses.size());
    }

    public void testActualIngressCapturesEachAppPatternAndDefaultUnderGenerationFence() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setDefaultPattern(context, "short");
        NotificationVibrations.setAppPattern(context, A, "long");
        Fixture f = new Fixture(); f.connect();
        f.post(f.card(A, "a", false));
        assertEquals("long", f.pulses.get(0).pattern);
        f.advance(); f.post(f.card(B, "b", false));
        assertEquals("short", f.pulses.get(1).pattern);
        BooleanSupplier old = f.pulses.get(1).guard;
        assertTrue(old.getAsBoolean());
        NotificationVibrations.setDefaultPattern(context, "triple");
        assertFalse(old.getAsBoolean());
        assertEquals("short", f.pulses.get(1).pattern);
        f.advance(); f.post(f.card(B, "b", false));
        assertEquals("triple", f.pulses.get(2).pattern);
        assertTrue(f.pulses.get(2).guard.getAsBoolean());
    }

    public void testActualIngressEditsBetweenMotorWritesAndOffOnNeverReviveWork() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setAppPattern(context, A, "triple");
        Fixture f = new Fixture(); f.connect(); f.post(f.card(A, "a", false));
        NotificationVibrations.PulseRequest request = f.pulses.get(0);
        assertEquals(3, HapticPatterns.pulseCount(request.pattern));
        assertTrue("First motor-write check", request.guard.getAsBoolean());
        NotificationVibrations.setAppPattern(context, A, "short");
        assertFalse("Remaining motor-write checks cancel after edit", request.guard.getAsBoolean());
        NotificationVibrations.setAppPattern(context, A, "triple");
        assertFalse(request.guard.getAsBoolean());
        f.advance(); f.post(f.card(A, "a", false));
        BooleanSupplier next = f.pulses.get(1).guard;
        NotificationVibrations.setEnabled(context, false);
        NotificationVibrations.setEnabled(context, true);
        assertFalse(next.getAsBoolean());
        f.advance(); f.post(f.card(A, "a", false));
        BooleanSupplier mode = f.pulses.get(2).guard;
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setAppSelected(context, A, true);
        assertFalse(mode.getAsBoolean());
        f.advance(); f.post(f.card(A, "a", false));
        BooleanSupplier list = f.pulses.get(3).guard;
        NotificationVibrations.setAppSelected(context, A, false);
        NotificationVibrations.setAppSelected(context, A, true);
        assertFalse(list.getAsBoolean());
    }

    public void testActualOnlyOnceBlockedCardsStaySilentAfterAllowAndReconnectSeeding() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setMode(context, "selected");
        Fixture f = new Fixture(); f.connect();
        f.post(f.card(A, "once", true));
        NotificationVibrations.setAppSelected(context, A, true);
        f.advance(); f.post(f.card(A, "once", true));
        assertEquals(0, f.pulses.size());
        for (int connection = 0; connection < 2; connection++) {
            f.snapshot = new StatusBarNotification[]{f.card(A, "existing", true)};
            f.listener.onListenerDisconnected();
            f.connect();
            // Directly deliver the NEWER callback; never inject an old callback after seeding.
            f.post(f.card(A, "existing", true));
            assertEquals(0, f.pulses.size());
        }
        StatusBarNotification current = f.card(A, "existing", true);
        f.listener.onNotificationRemoved(current);
        f.advance(); f.post(f.card(A, "existing", true));
        assertEquals(1, f.pulses.size());
    }

    public void testActualCallbacksStillFilterSilentOngoingOwnAppAndUnavailableMetadata() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        f.muted = true;
        StatusBarNotification silent = f.card(A, "silent", false);
        f.post(silent); f.muted = false; f.post(silent);
        StatusBarNotification ongoing = f.card(A, "ongoing", false);
        ongoing.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        f.post(ongoing);
        f.post(f.card(context.getPackageName(), "own", false));
        f.rankingUnavailable = true; f.post(f.card(A, "unknown", false));
        f.rankingUnavailable = false;
        assertEquals(0, f.pulses.size());
        f.post(f.card(A, "fresh", false));
        assertEquals(1, f.pulses.size());
        f.interruptions = false;
        assertFalse(f.pulses.get(0).guard.getAsBoolean());
    }

    public void testPackageAwareSinkRunsOutsideGateLockAndEditsCancelBeforeUse() {
        NotificationVibrations.setEnabled(context, true);
        long[] now = {1000000};
        OmiNotificationListener.EventGate[] gate = new OmiNotificationListener.EventGate[1];
        List<String> patterns = new ArrayList<>();
        gate[0] = new OmiNotificationListener.EventGate(context.getPackageName(), () -> now[0], () -> now[0],
            name -> NotificationVibrations.currentPulse(context, name, () -> true), () -> true,
            (pattern, allowed) -> {
                assertFalse(Thread.holdsLock(gate[0]));
                NotificationVibrations.setMode(context, "selected");
                assertFalse(allowed.getAsBoolean());
                patterns.add(pattern);
            });
        gate[0].connect(); now[0]++;
        gate[0].posted("a", A, now[0], true);
        assertEquals(Collections.singletonList("double"), patterns);
    }

    public void testSettingsAttachesActualControlsWithoutGrantOrCapture() throws Exception {
        activity = getActivity();
        getInstrumentation().waitForIdleSync();
        main(() -> {
            View root = activity.getWindow().getDecorView();
            assertNotNull(root.findViewWithTag("omi.notifications.mode"));
            assertNotNull(root.findViewWithTag("omi.notifications.pattern.default"));
            assertNotNull(root.findViewWithTag("omi.notifications.apps"));
        });
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.accessGranted(context));
        assertNoCapture();
    }

    public void testUiSelectedEmptyDefaultPatternAndRefreshDoNotSilentlyRewritePreferences() throws Exception {
        openHelper();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        main(() -> { for (int i = 0; i < 10; i++) controls.refresh(); });
        getInstrumentation().waitForIdleSync();
        assertEquals(before, prefs.getAll());
        select("omi.notifications.mode", 1);
        assertEquals("selected", NotificationVibrations.mode(context));
        assertFalse(NotificationVibrations.packageAllowed(context, A));
        main(() -> assertTrue(((TextView) view("omi.notifications.selection.summary")).getText().toString().contains("0 selected — no apps")));
        select("omi.notifications.pattern.default", 3);
        assertEquals("long", NotificationVibrations.defaultPattern(context));
        main(() -> {
            View original = view("omi.notifications.mode");
            for (int i = 0; i < 10; i++) controls.refresh();
            assertSame(original, view("omi.notifications.mode"));
        });
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.accessGranted(context));
        assertNoCapture();
    }

    public void testUiLaunchableAppCheckboxPatternOverrideAndReattachPersistence() throws Exception {
        openHelper();
        String[] selectedPackage = {null};
        main(() -> {
            List<NotificationControls.App> apps = NotificationControls.launchableApps(activity);
            assertFalse("Synthetic emulator must have a launchable app besides this app", apps.isEmpty());
            Set<String> names = new HashSet<>();
            for (NotificationControls.App app : apps) {
                assertTrue(app.launchable);
                assertTrue(names.add(app.packageName));
                assertFalse(context.getPackageName().equals(app.packageName));
            }
            selectedPackage[0] = apps.get(0).packageName;
            view("omi.notifications.apps").performClick();
        });
        final String name = selectedPackage[0];
        main(() -> ((CheckBox) dialogView("omi.notifications.app." + name)).performClick());
        assertTrue(NotificationVibrations.selectedPackages(context).contains(name));
        main(() -> ((Spinner) dialogView("omi.notifications.pattern." + name)).setSelection(3));
        getInstrumentation().waitForIdleSync();
        assertEquals("triple", NotificationVibrations.appPatternOverride(context, name));
        main(() -> dialog().dismiss());
        select("omi.notifications.mode", 1);
        assertTrue(NotificationVibrations.packageAllowed(context, name));
        main(this::attachHelper);
        getInstrumentation().waitForIdleSync();
        main(() -> {
            assertEquals(1, ((Spinner) view("omi.notifications.mode")).getSelectedItemPosition());
            view("omi.notifications.apps").performClick();
            assertTrue(((CheckBox) dialogView("omi.notifications.app." + name)).isChecked());
            assertEquals(3, ((Spinner) dialogView("omi.notifications.pattern." + name)).getSelectedItemPosition());
            ((Spinner) dialogView("omi.notifications.pattern." + name)).setSelection(0);
        });
        getInstrumentation().waitForIdleSync();
        assertNull(NotificationVibrations.appPatternOverride(context, name));
        main(() -> ((CheckBox) dialogView("omi.notifications.app." + name)).performClick());
        assertFalse(NotificationVibrations.packageAllowed(context, name));
        assertNoCapture();
    }

    public void testUiKeepsUnavailableSavedPackageRemovable() throws Exception {
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setAppSelected(context, A, true);
        openHelper();
        main(() -> {
            view("omi.notifications.apps").performClick();
            CheckBox savedPackage = (CheckBox) dialogView("omi.notifications.app." + A);
            assertTrue(savedPackage.isChecked());
            assertTrue(savedPackage.getText().toString().contains("not currently launchable"));
            savedPackage.performClick();
        });
        assertTrue(NotificationVibrations.selectedPackages(context).isEmpty());
        assertFalse(NotificationVibrations.packageAllowed(context, A));
    }

    private BooleanSupplier guard(String name) { return NotificationVibrations.currentGuard(context, name, () -> true); }

    private final class Fixture {
        long wall = 1000000, elapsed = 1000;
        boolean muted, rankingUnavailable, interruptions = true;
        StatusBarNotification[] snapshot = new StatusBarNotification[0];
        final List<NotificationVibrations.PulseRequest> pulses = new ArrayList<>();
        final OmiNotificationListener.EventGate gate = new OmiNotificationListener.EventGate(
            context.getPackageName(), () -> wall, () -> elapsed,
            name -> NotificationVibrations.currentPulse(context, name, () -> true), () -> interruptions,
            (pattern, guard) -> pulses.add(new NotificationVibrations.PulseRequest(pattern, guard)));
        final OmiNotificationListener listener = new OmiNotificationListener(gate, () -> snapshot,
            (key, rankings) -> rankingUnavailable ? null : new Ranking() {
                @Override public NotificationChannel getChannel() {
                    NotificationChannel channel = new NotificationChannel("synthetic", "Synthetic", NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setSound(null, null); channel.enableVibration(!muted); return channel;
                }
                @Override public int getImportance() { return NotificationManager.IMPORTANCE_DEFAULT; }
                @Override public boolean matchesInterruptionFilter() { return true; }
                @Override public int getSuppressedVisualEffects() { return 0; }
                @Override public boolean isSuspended() { return false; }
            });
        void connect() { listener.onListenerConnected(); wall++; elapsed++; }
        void advance() { wall += OmiNotificationListener.EventGate.COOLDOWN_MS; elapsed += OmiNotificationListener.EventGate.COOLDOWN_MS; }
        void post(StatusBarNotification card) { listener.onNotificationPosted(card, null); }
        StatusBarNotification card(String packageName, String tag, boolean onlyOnce) {
            Notification notification = new Notification.Builder(context, "synthetic")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setOnlyAlertOnce(onlyOnce).build();
            return new StatusBarNotification(packageName, packageName, 1, tag, Process.myUid(), Process.myPid(),
                0, notification, Process.myUserHandle(), wall);
        }
    }

    private void openHelper() throws Exception {
        activity = getActivity(); main(this::attachHelper); getInstrumentation().waitForIdleSync();
    }
    private void attachHelper() {
        container = new LinearLayout(activity); container.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(container);
        controls = NotificationControls.attach(activity, container);
    }
    private View view(String tag) {
        View found = container.findViewWithTag(tag); assertNotNull(tag, found); return found;
    }
    private AlertDialog dialog() throws Exception {
        Field field = NotificationControls.class.getDeclaredField("appsDialog");
        field.setAccessible(true); return (AlertDialog) field.get(controls);
    }
    private View dialogView(String tag) throws Exception {
        View found = dialog().getWindow().getDecorView().findViewWithTag(tag); assertNotNull(tag, found); return found;
    }
    private void select(String tag, int index) throws Exception {
        main(() -> ((Spinner) view(tag)).setSelection(index)); getInstrumentation().waitForIdleSync();
    }
    private void assertNoCapture() {
        assertFalse(CaptureService.active); assertFalse(OmiCaptureService.active); assertFalse(OmiCaptureService.controlsActive);
    }
    private interface Checked { void run() throws Exception; }
    private void main(Checked work) throws Exception {
        Throwable[] failure = {null};
        getInstrumentation().runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
