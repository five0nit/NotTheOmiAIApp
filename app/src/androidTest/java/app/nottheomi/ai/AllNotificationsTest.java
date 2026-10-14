package app.nottheomi.ai;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.LinearLayout;
import android.widget.Spinner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Synthetic callbacks only on the isolated emulator; no notification access, BLE or real messages. */
@SuppressWarnings("deprecation")
public final class AllNotificationsTest extends ActivityInstrumentationTestCase2<OmiSettingsActivity> {
    private static final String A = "synthetic.chat.app", B = "synthetic.other.app";
    private static final String PREFIX = "notification_vibrations_";
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private OmiSettingsActivity activity;
    private LinearLayout container;
    private NotificationControls controls;

    public AllNotificationsTest() { super(OmiSettingsActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Isolated emulator required", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertNoCapture();
        context = getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("omi", Context.MODE_PRIVATE);
        saved = new HashMap<>(prefs.getAll());
        assertFalse(NotificationVibrations.accessGranted(context));
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : saved.keySet()) if (key.startsWith(PREFIX)) edit.remove(key);
        assertTrue(edit.commit());
        NotificationVibrations.invalidate();
    }

    @Override protected void tearDown() throws Exception {
        try {
            main(() -> { if (activity != null) activity.finish(); });
            if (prefs != null && saved != null) {
                SharedPreferences.Editor edit = prefs.edit();
                for (String key : prefs.getAll().keySet()) if (key.startsWith(PREFIX)) edit.remove(key);
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    if (!key.startsWith(PREFIX)) continue;
                    if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                    else if (value instanceof String) edit.putString(key, (String) value);
                    else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                    else if (value instanceof Long) edit.putLong(key, (Long) value);
                    else if (value instanceof Float) edit.putFloat(key, (Float) value);
                    else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
                }
                assertTrue(edit.commit());
            }
            NotificationVibrations.invalidate();
            assertNoCapture();
        } finally { super.tearDown(); }
    }

    public void testDefaultMigrationPersistenceInvalidValuesAndGeneration() throws Exception {
        assertEquals(NotificationVibrations.FILTER_ALL, NotificationVibrations.filter(context));
        assertFalse(NotificationVibrations.enabled(context));
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setMode(context, "selected");
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setAppPattern(context, A, "triple");
        long original = NotificationVibrations.configurationGeneration();
        NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALL);
        assertEquals(original, NotificationVibrations.configurationGeneration());
        BooleanSupplier pending = NotificationVibrations.currentGuard(context, A, () -> true);
        NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALERTING);
        assertFalse(pending.getAsBoolean());
        Context recreated = context.createPackageContext(context.getPackageName(), 0);
        assertEquals(NotificationVibrations.FILTER_ALERTING, NotificationVibrations.filter(recreated));
        NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALL);
        assertFalse(pending.getAsBoolean());
        assertEquals("selected", NotificationVibrations.mode(context));
        assertEquals("triple", NotificationVibrations.patternForPackage(context, A));
        assertTrue(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.accessGranted(context));
        for (String invalid : new String[]{"all", "ALL_NOTIFICATIONS", "all_notifications ", "", null}) {
            try { NotificationVibrations.setFilter(context, invalid); fail("Invalid filter accepted"); }
            catch (IllegalArgumentException expected) {}
        }
        prefs.edit().putInt(NotificationVibrations.FILTER_KEY, 42).commit();
        assertFalse(NotificationVibrations.includeAllNotifications(context));
        prefs.edit().putString(NotificationVibrations.FILTER_KEY, "unknown").commit();
        assertFalse(NotificationVibrations.includeAllNotifications(context));
    }

    public void testActualSettingsFilterDefaultSelectRefreshAndReattach() throws Exception {
        activity = getActivity(); getInstrumentation().waitForIdleSync();
        main(() -> assertNotNull(activity.getWindow().getDecorView().findViewWithTag("omi.notifications.filter")));
        main(() -> {
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            android.widget.ScrollView scroll = (android.widget.ScrollView) root.getChildAt(0);
            android.view.View filter = activity.getWindow().getDecorView().findViewWithTag("omi.notifications.filter");
            scroll.scrollTo(0, Math.max(0, filter.getTop() - 240));
        });
        main(() -> {
            android.view.View content = activity.findViewById(android.R.id.content);
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(content.getWidth(), content.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null), "all-notifications-0413.png"))) {
                content.draw(new android.graphics.Canvas(image));
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
            } finally { image.recycle(); }
        });
        main(this::attachHelper); getInstrumentation().waitForIdleSync();
        main(() -> assertEquals(0, spinner().getSelectedItemPosition()));
        select(1);
        assertEquals(NotificationVibrations.FILTER_ALERTING, NotificationVibrations.filter(context));
        main(this::attachHelper); getInstrumentation().waitForIdleSync();
        main(() -> assertEquals(1, spinner().getSelectedItemPosition()));
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        main(() -> { for (int i = 0; i < 10; i++) controls.refresh(); });
        getInstrumentation().waitForIdleSync();
        assertEquals(before, prefs.getAll());
        select(0);
        assertTrue(NotificationVibrations.includeAllNotifications(context));
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.accessGranted(context));
        assertNoCapture();
    }

    public void testSilentLowPriorityOngoingForegroundSummaryAndMissingRankingsAccepted() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        for (int flags : new int[]{0, Notification.FLAG_ONGOING_EVENT,
                Notification.FLAG_FOREGROUND_SERVICE, Notification.FLAG_GROUP_SUMMARY}) {
            StatusBarNotification card = f.card(A, "flags-" + flags, false);
            card.getNotification().flags |= flags;
            card.getNotification().priority = Notification.PRIORITY_MIN;
            f.post(card);
            assertTrue(f.pulses.get(f.pulses.size() - 1).guard.getAsBoolean());
            f.advance();
        }
        assertEquals(4, f.pulses.size());
        StatusBarNotification card = f.card(A, "no-ranking", false);
        f.listener.onNotificationPosted(card);
        f.listener.onNotificationPosted(card, null);
        assertEquals(5, f.pulses.size());
        assertEquals("All mode must not depend on ranking availability", 0, f.rankingReads);
        f.advance(); f.post(f.card(context.getPackageName(), "own", false));
        f.listener.onNotificationPosted(null, null);
        assertEquals(5, f.pulses.size());
    }

    public void testWhatsappStyleGroupSummaryAndChildrenCoverEitherOrder() {
        NotificationVibrations.setEnabled(context, true);
        for (boolean summaryFirst : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.connect();
            Notification child = new Notification.Builder(context, "synthetic-silent")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("synthetic-group")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setSound(null).setVibrate(null).setDefaults(0).build();
            Notification summary = new Notification.Builder(context, "synthetic-silent")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("synthetic-group")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setGroupSummary(true).build();
            StatusBarNotification a = f.withNotification(A, "group-child", child);
            StatusBarNotification b = f.withNotification(A, "group-summary", summary);
            f.post(summaryFirst ? b : a); f.post(summaryFirst ? a : b);
            assertEquals("Group must produce a pulse, not be dropped entirely", 1, f.pulses.size());
            assertTrue(f.pulses.get(0).guard.getAsBoolean());
            f.advance(); f.post(f.withNotification(A, "group-child", child));
            assertEquals(2, f.pulses.size());
        }
    }

    public void testDndNoInvalidationAndFilterChangeCancelsPermanently() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        f.post(f.card(A, "during-dnd", false));
        assertEquals(1, f.pulses.size());
        BooleanSupplier guard = f.pulses.get(0).guard;
        long generation = NotificationVibrations.configurationGeneration();
        f.listener.onInterruptionFilterChanged(NotificationManager.INTERRUPTION_FILTER_NONE);
        assertEquals(generation, NotificationVibrations.configurationGeneration());
        assertTrue(guard.getAsBoolean());
        NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALERTING);
        assertFalse(guard.getAsBoolean());
        generation = NotificationVibrations.configurationGeneration();
        f.listener.onInterruptionFilterChanged(NotificationManager.INTERRUPTION_FILTER_NONE);
        assertTrue(NotificationVibrations.configurationGeneration() > generation);
        f.advance(); f.post(f.card(A, "legacy-no-ranking", false));
        assertEquals(1, f.pulses.size());
        NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALL);
        assertFalse(guard.getAsBoolean());
    }

    public void testOnlyOnceFreshUpdatesButNoDuplicateStaleOrReconnectReplay() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        StatusBarNotification card = f.card(A, "same", true);
        f.post(card); f.post(card); assertEquals(1, f.pulses.size());
        f.advance(); f.post(f.card(A, "same", true)); assertEquals(2, f.pulses.size());
        f.advance(); f.post(card); assertEquals(2, f.pulses.size());
        StatusBarNotification retained = f.card(A, "retained", true);
        f.snapshot = new StatusBarNotification[]{retained};
        f.listener.onListenerDisconnected(); f.connect();
        assertFalse(f.pulses.get(1).guard.getAsBoolean());
        f.post(retained); assertEquals(2, f.pulses.size());
        f.advance(); f.post(f.card(A, "retained", true)); assertEquals(3, f.pulses.size());
    }

    public void testSelectionOptInAccessPatternsAndRemovalStillEnforced() {
        NotificationVibrations.setEnabled(context, true);
        NotificationVibrations.setMode(context, "selected");
        Fixture f = new Fixture(); f.connect();
        StatusBarNotification blocked = f.card(A, "blocked", false);
        f.post(blocked); assertEquals(0, f.pulses.size());
        NotificationVibrations.setAppSelected(context, A, true);
        NotificationVibrations.setAppPattern(context, A, "long");
        f.post(blocked); assertEquals(0, f.pulses.size());
        f.advance(); StatusBarNotification allowed = f.card(A, "fresh", false); f.post(allowed);
        assertEquals(1, f.pulses.size()); assertEquals("long", f.pulses.get(0).pattern);
        assertTrue(f.pulses.get(0).guard.getAsBoolean());
        f.listener.onNotificationRemoved(allowed); assertFalse(f.pulses.get(0).guard.getAsBoolean());
        f.advance(); f.post(f.card(B, "unselected", false)); assertEquals(1, f.pulses.size());
        NotificationVibrations.setEnabled(context, false);
        f.advance(); f.post(f.card(A, "off", false)); assertEquals(1, f.pulses.size());
        NotificationVibrations.setEnabled(context, true); f.access = false;
        f.advance(); f.post(f.card(A, "denied", false)); assertEquals(1, f.pulses.size());
    }

    public void testPolicyChangeDuringClassificationCannotAdoptNewGeneration() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        OmiNotificationListener racing = new OmiNotificationListener(f.gate, () -> f.snapshot,
            (key, ranks) -> { throw new AssertionError("All mode must not read rankings"); }, () -> {
                boolean before = NotificationVibrations.includeAllNotifications(context);
                NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALERTING);
                NotificationVibrations.setFilter(context, NotificationVibrations.FILTER_ALL);
                return before;
            });
        StatusBarNotification card = f.card(A, "race", false);
        racing.onNotificationPosted(card, null);
        assertEquals(0, f.pulses.size());
        f.post(card); assertEquals("Consumed old delivery cannot replay", 0, f.pulses.size());
        f.advance(); f.post(f.card(A, "race", false)); assertEquals(1, f.pulses.size());
    }

    public void testIngressCannotReadAnUnpublishedPreferenceGeneration() throws Exception {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.connect();
        java.util.concurrent.CountDownLatch incremented = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch publish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        Context blocked = new android.content.ContextWrapper(context) {
            int reads;
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                // setFilter reads once, increments generation, then obtains its editor. Pause there.
                if (++reads == 2) {
                    incremented.countDown();
                    try { assertTrue(publish.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return prefs;
            }
        };
        try {
            java.util.concurrent.Future<?> setter = workers.submit(() ->
                NotificationVibrations.setFilter(blocked, NotificationVibrations.FILTER_ALERTING));
            assertTrue(incremented.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> callback = workers.submit(() -> {
                started.countDown(); f.post(f.card(A, "publication-race", false));
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try { callback.get(100, java.util.concurrent.TimeUnit.MILLISECONDS); fail("Ingress bypassed publisher lock"); }
            catch (java.util.concurrent.TimeoutException expected) { /* Waits for complete publication. */ }
            assertEquals("Policy must not be classified under a partially published generation", 0, f.policyReads);
            publish.countDown();
            setter.get(5, java.util.concurrent.TimeUnit.SECONDS);
            callback.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, f.pulses.size());
            assertEquals(1, f.rankingReads);
        } finally {
            publish.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private final class Fixture {
        long wall = 1000000, elapsed = 1000;
        boolean access = true;
        int rankingReads;
        volatile int policyReads;
        StatusBarNotification[] snapshot = new StatusBarNotification[0];
        final List<NotificationVibrations.PulseRequest> pulses = new ArrayList<>();
        final OmiNotificationListener.EventGate gate = new OmiNotificationListener.EventGate(
            context.getPackageName(), () -> wall, () -> elapsed,
            name -> NotificationVibrations.currentPulse(context, name, () -> access), this::canInterrupt,
            (pattern, guard) -> pulses.add(new NotificationVibrations.PulseRequest(pattern, guard)));
        final OmiNotificationListener listener = new OmiNotificationListener(gate, () -> snapshot,
            (key, rankings) -> { rankingReads++; return null; },
            () -> { policyReads++; return NotificationVibrations.includeAllNotifications(context); });
        boolean canInterrupt() { return listener.interruptionsAllowed(); }
        void connect() { listener.onListenerConnected(); wall++; elapsed++; }
        void advance() { wall += OmiNotificationListener.EventGate.COOLDOWN_MS; elapsed += OmiNotificationListener.EventGate.COOLDOWN_MS; }
        void post(StatusBarNotification card) { listener.onNotificationPosted(card, null); }
        StatusBarNotification card(String name, String tag, boolean onlyOnce) {
            return withNotification(name, tag, new Notification.Builder(context, "synthetic-silent")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setSound(null).setVibrate(null).setDefaults(0).setOnlyAlertOnce(onlyOnce).build());
        }
        StatusBarNotification withNotification(String name, String tag, Notification notification) {
            return new StatusBarNotification(name, name, 1, tag, Process.myUid(), Process.myPid(),
                0, notification, Process.myUserHandle(), wall);
        }
    }

    private void attachHelper() {
        container = new LinearLayout(activity); container.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(container); controls = NotificationControls.attach(activity, container);
    }
    private Spinner spinner() { return (Spinner) container.findViewWithTag("omi.notifications.filter"); }
    private void select(int index) throws Exception {
        main(() -> spinner().setSelection(index)); getInstrumentation().waitForIdleSync();
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
