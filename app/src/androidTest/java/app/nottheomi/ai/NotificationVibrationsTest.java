package app.nottheomi.ai;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Synthetic dedicated emulator only. No access grants, notification posting, BLE or audio. */
@SuppressWarnings("deprecation")
public final class NotificationVibrationsTest extends ActivityInstrumentationTestCase2<OmiSettingsActivity> {
    private static final String OTHER_PACKAGE = "synthetic.other.app";
    private static final String[] OWNED_KEYS = { NotificationVibrations.ENABLED_KEY,
        "single_action", "double_action", "triple_action" };
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private OmiSettingsActivity activity;
    private String oldPulseStatus;

    public NotificationVibrationsTest() { super(OmiSettingsActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator required", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active);
        assertFalse(OmiCaptureService.active);
        assertFalse(OmiCaptureService.controlsActive);
        context = getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("omi", Context.MODE_PRIVATE);
        saved = new HashMap<>(prefs.getAll());
        oldPulseStatus = OmiCaptureService.notificationPulseStatus;
        assertFalse("Use a fresh emulator without listener access; this suite never grants it",
            NotificationVibrations.accessGranted(context));
        prefs.edit().remove(NotificationVibrations.ENABLED_KEY).commit();
        NotificationVibrations.invalidate();
        setActivityInitialTouchMode(true);
    }

    @Override protected void tearDown() throws Exception {
        try {
            main(() -> {
                OmiCaptureService.active = false;
                OmiCaptureService.controlsActive = false;
                OmiCaptureService.notificationPulseStatus = oldPulseStatus;
                if (activity != null) activity.finish();
            });
            if (prefs != null && saved != null) {
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : OWNED_KEYS) {
                    editor.remove(key);
                    Object value = saved.get(key);
                    if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof String) editor.putString(key, (String) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Float) editor.putFloat(key, (Float) value);
                    else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
                }
                editor.commit();
            }
            NotificationVibrations.invalidate();
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    public void testDefaultOffPersistsOnOffWithoutResettingMappingsOrDevice() throws Exception {
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(prefs.contains(NotificationVibrations.ENABLED_KEY));
        prefs.edit().putString("single_action", "toggle").putString("double_action", "media_pause")
            .putString("triple_action", "ignore").commit();
        Map<String, Object> before = new HashMap<>(prefs.getAll());
        before.remove(NotificationVibrations.ENABLED_KEY);
        NotificationVibrations.setEnabled(context, true);
        Context another = context.createPackageContext(context.getPackageName(), 0);
        assertTrue(NotificationVibrations.enabled(another));
        assertTrue(prefs.getBoolean(NotificationVibrations.ENABLED_KEY, false));
        NotificationVibrations.setEnabled(another, false);
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(prefs.getBoolean(NotificationVibrations.ENABLED_KEY, true));
        Map<String, Object> after = new HashMap<>(prefs.getAll());
        after.remove(NotificationVibrations.ENABLED_KEY);
        assertEquals(before, after);
        assertEquals("toggle", ButtonActions.action(context, ButtonEvent.SINGLE));
        assertEquals("media_pause", ButtonActions.action(context, ButtonEvent.DOUBLE));
    }

    public void testWrongTypeFailsClosedAndToggleRepairsOnlyItsKey() {
        prefs.edit().putString(NotificationVibrations.ENABLED_KEY, "true").commit();
        assertFalse(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.currentGuard(context, () -> true).getAsBoolean());
        NotificationVibrations.setEnabled(context, true);
        assertTrue(NotificationVibrations.enabled(context));
    }

    public void testAccessIsIndependentFromOptInAndPostNotificationsPermission() {
        Context permissionTrap = new ContextWrapper(context) {
            @Override public int checkSelfPermission(String permission) {
                throw new AssertionError("Notification access must not use runtime permission " + permission);
            }
            @Override public Context getApplicationContext() { return this; }
        };
        NotificationVibrations.setEnabled(context, true);
        assertFalse(NotificationVibrations.accessGranted(permissionTrap));
        assertTrue(NotificationVibrations.enabled(context));
        assertFalse(NotificationVibrations.currentGuard(permissionTrap).getAsBoolean());
        assertFalse(NotificationVibrations.currentGuard(context, () -> false).getAsBoolean());
        assertTrue(NotificationVibrations.currentGuard(context, () -> true).getAsBoolean());
        NotificationVibrations.setEnabled(context, false);
        assertFalse(NotificationVibrations.currentGuard(context, () -> true).getAsBoolean());
    }

    public void testApi26AccessParserRequiresExactListenerComponent() {
        ComponentName expected = NotificationVibrations.listenerComponent(context);
        assertTrue(NotificationVibrations.containsComponent(expected.flattenToString(), expected));
        assertTrue(NotificationVibrations.containsComponent("other/other.Listener:" + expected.flattenToShortString(), expected));
        assertFalse(NotificationVibrations.containsComponent(null, expected));
        assertFalse(NotificationVibrations.containsComponent("malformed", expected));
        assertFalse(NotificationVibrations.containsComponent(context.getPackageName() + "/.DifferentListener", expected));
        assertFalse(NotificationVibrations.containsComponent("other/" + expected.getClassName(), expected));
        assertFalse(NotificationVibrations.containsComponent(expected.flattenToString() + "Extra", expected));
    }

    public void testSettingsIntentAndManifestUseSeparateProtectedListenerAccess() throws Exception {
        Intent settings = NotificationVibrations.accessSettingsIntent();
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, settings.getAction());
        assertNull(settings.getExtras());
        ServiceInfo info = context.getPackageManager().getServiceInfo(NotificationVibrations.listenerComponent(context), 0);
        assertTrue(info.exported);
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission);
        if (Build.VERSION.SDK_INT >= 29) assertEquals(0, info.getForegroundServiceType());
        Intent listener = new Intent("android.service.notification.NotificationListenerService").setPackage(context.getPackageName());
        boolean found = false;
        for (android.content.pm.ResolveInfo match : context.getPackageManager().queryIntentServices(listener, 0)) {
            if (OmiNotificationListener.class.getName().equals(match.serviceInfo.name)) found = true;
        }
        assertTrue("System can discover the protected listener", found);
    }

    public void testOffOnInvalidatesQueuedGuardAndIdempotentSetDoesNot() {
        BooleanSupplier createdOff = NotificationVibrations.currentGuard(context, () -> true);
        long before = NotificationVibrations.configurationGeneration();
        NotificationVibrations.setEnabled(context, true);
        assertTrue(NotificationVibrations.configurationGeneration() > before);
        assertFalse(createdOff.getAsBoolean());
        BooleanSupplier first = NotificationVibrations.currentGuard(context, () -> true);
        assertTrue(first.getAsBoolean());
        long unchanged = NotificationVibrations.configurationGeneration();
        NotificationVibrations.setEnabled(context, true);
        assertEquals(unchanged, NotificationVibrations.configurationGeneration());
        assertTrue(first.getAsBoolean());
        NotificationVibrations.setEnabled(context, false);
        NotificationVibrations.setEnabled(context, true);
        assertFalse(first.getAsBoolean());
        assertTrue(NotificationVibrations.currentGuard(context, () -> true).getAsBoolean());
    }

    public void testRevocationAndUnavailableAccessCancelOldGuardEvenAfterRegrant() {
        NotificationVibrations.setEnabled(context, true);
        boolean[] access = {false};
        BooleanSupplier denied = NotificationVibrations.currentGuard(context, () -> access[0]);
        access[0] = true;
        assertFalse(denied.getAsBoolean());
        BooleanSupplier first = NotificationVibrations.currentGuard(context, () -> access[0]);
        assertTrue(first.getAsBoolean());
        access[0] = false;
        assertFalse(first.getAsBoolean());
        access[0] = true;
        assertFalse(first.getAsBoolean());
        BooleanSupplier second = NotificationVibrations.currentGuard(context, () -> access[0]);
        assertTrue(second.getAsBoolean());
        assertFalse(NotificationVibrations.currentGuard(context, () -> { throw new SecurityException("synthetic denied"); }).getAsBoolean());
        assertFalse(second.getAsBoolean());
    }

    public void testConnectNeverReplaysAndOldStaleFutureCallbacksStaySilent() {
        Fixture f = new Fixture();
        NotificationVibrations.setEnabled(context, true);
        f.gate.posted("before-bind", OTHER_PACKAGE, f.wall, true);
        assertEquals(0, f.pulses.size());
        f.gate.connect();
        assertEquals(0, f.pulses.size());
        f.gate.posted("existing", OTHER_PACKAGE, f.wall - 1, true);
        f.gate.posted("equal-boundary", OTHER_PACKAGE, f.wall, true);
        f.advance(OmiNotificationListener.EventGate.MAX_AGE_MS + 2);
        f.gate.posted("stale", OTHER_PACKAGE, f.wall - OmiNotificationListener.EventGate.MAX_AGE_MS - 1, true);
        f.gate.posted("future", OTHER_PACKAGE, f.wall + 1, true);
        assertEquals(0, f.pulses.size());
        f.fresh("new");
        assertEquals(1, f.pulses.size());
        assertTrue(f.pulses.get(0).getAsBoolean());
    }

    public void testSameCardNewDeliveryAlertsButDuplicateAndOlderCallbacksDoNot() {
        Fixture f = ready();
        f.fresh("one");
        long firstPostTime = f.wall;
        BooleanSupplier first = f.pulses.get(0);
        f.fresh("one");
        assertEquals(1, f.pulses.size());
        assertTrue(first.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("one", OTHER_PACKAGE, firstPostTime, true);
        assertEquals(1, f.pulses.size());
        f.fresh("one");
        assertEquals("A new message may update the same notification card", 2, f.pulses.size());
        assertFalse(first.getAsBoolean());
        BooleanSupplier second = f.pulses.get(1);
        f.gate.posted("one", OTHER_PACKAGE, firstPostTime, false);
        f.fresh("one");
        assertEquals(2, f.pulses.size());
        assertTrue("An out-of-order callback must not cancel newer work", second.getAsBoolean());
    }

    public void testCooldownConsumesUpdatedAndOtherCardsRatherThanQueuingThem() {
        Fixture f = ready();
        f.fresh("one");
        BooleanSupplier first = f.pulses.get(0);
        f.advance(1);
        long duringCooldown = f.wall;
        f.fresh("one");
        f.fresh("two-in-burst");
        assertEquals(1, f.pulses.size());
        assertFalse("Superseded work is cancelled even when the update is dropped", first.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("one", OTHER_PACKAGE, duringCooldown, true);
        f.gate.posted("two-in-burst", OTHER_PACKAGE, duringCooldown, true);
        assertEquals(1, f.pulses.size());
        f.fresh("two-in-burst");
        assertEquals(2, f.pulses.size());
    }

    public void testRemovalRejectsReplayAndStaleRemovalCannotCancelNewerRepost() {
        Fixture f = ready();
        f.fresh("reusable");
        long firstPostTime = f.wall;
        BooleanSupplier old = f.pulses.get(0);
        assertTrue(old.getAsBoolean());
        f.gate.removed("reusable", firstPostTime);
        assertEquals("A tombstone protects against callbacks already in flight", 1, f.gate.rememberedCount());
        assertFalse(old.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("reusable", OTHER_PACKAGE, firstPostTime, true);
        assertEquals(1, f.pulses.size());
        f.fresh("reusable");
        assertEquals(2, f.pulses.size());
        assertFalse(old.getAsBoolean());
        f.gate.removed("reusable", firstPostTime);
        assertTrue(f.pulses.get(1).getAsBoolean());
        f.gate.removed("reusable", f.wall);
        assertFalse(f.pulses.get(1).getAsBoolean());
    }

    public void testOnlyAlertOnceAllowsFirstPostAndRepostButNotActiveCardUpdates() {
        Fixture f = ready();
        f.gate.posted("once", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
        BooleanSupplier first = f.pulses.get(0);
        f.gate.posted("once", OTHER_PACKAGE, f.wall, true, true);
        assertTrue("Duplicate delivery must not cancel the first alert", first.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("once", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
        assertFalse(first.getAsBoolean());
        long removedPostTime = f.wall;
        f.gate.removed("once", removedPostTime);
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("once", OTHER_PACKAGE, removedPostTime, true, true);
        assertEquals(1, f.pulses.size());
        f.gate.posted("once", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(2, f.pulses.size());
        assertTrue(f.pulses.get(1).getAsBoolean());
    }

    public void testOnlyAlertOnceRemembersSilentOffAndReconnectActiveCards() {
        Fixture f = new Fixture();
        f.gate.connect(); f.advance(1);
        f.gate.posted("off", OTHER_PACKAGE, f.wall, true, true);
        NotificationVibrations.setEnabled(context, true);
        f.gate.posted("silent", OTHER_PACKAGE, f.wall, false, false);
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("off", OTHER_PACKAGE, f.wall, true, true);
        f.gate.posted("silent", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(0, f.pulses.size());
        f.gate.disconnect(); f.gate.connect();
        f.gate.posted("existing", OTHER_PACKAGE, f.wall - 1, true, true);
        f.advance(1);
        f.gate.posted("existing", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(0, f.pulses.size());
        f.gate.posted("first", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
    }

    private StatusBarNotification activeCard(String tag, long postTime) {
        Notification value = notification();
        value.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        return new StatusBarNotification(OTHER_PACKAGE, OTHER_PACKAGE, 1, tag,
            Process.myUid(), Process.myPid(), 0, value, Process.myUserHandle(), postTime);
    }

    public void testReconnectSeedsActiveCardBeforeItsFirstNewerCallbackWithoutReplay() {
        Fixture f = ready();
        StatusBarNotification existing = activeCard("existing-at-bind", f.wall - 60000);
        OmiNotificationListener listener = new OmiNotificationListener(f.gate,
            () -> new StatusBarNotification[] { existing });
        for (int connection = 0; connection < 2; connection++) {
            listener.onListenerDisconnected();
            listener.onListenerConnected();
            assertEquals(0, f.pulses.size());
            assertEquals(1, f.gate.rememberedCount());
            f.advance(OmiNotificationListener.EventGate.MAX_AGE_MS + 1);
            // No replay/old onNotificationPosted callback is injected after connection.
            f.gate.posted(existing.getKey(), OTHER_PACKAGE, f.wall, true, true);
            assertEquals("Existing alert-once card remains silent after first/reconnection", 0, f.pulses.size());
        }
        f.gate.posted("genuinely-new", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted(existing.getKey(), OTHER_PACKAGE, f.wall, true, false);
        assertEquals("Ordinary new alerts on a reused card remain eligible", 2, f.pulses.size());
        f.gate.removed(existing.getKey(), f.wall);
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted(existing.getKey(), OTHER_PACKAGE, f.wall, true, true);
        assertEquals("A genuinely removed and reposted card may alert once again", 3, f.pulses.size());
    }

    public void testUnavailableActiveSnapshotFailsClosedAndReconnectCanRecover() {
        Fixture f = ready(); f.fresh("before-bind");
        BooleanSupplier old = f.pulses.get(0);
        OmiNotificationListener missing = new OmiNotificationListener(f.gate, () -> null);
        missing.onListenerConnected();
        assertFalse(old.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("unknown", OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
        OmiNotificationListener denied = new OmiNotificationListener(f.gate,
            () -> { throw new SecurityException("synthetic access unavailable"); });
        denied.onListenerConnected();
        f.advance(1); f.fresh("unknown-after-error");
        assertEquals(1, f.pulses.size());
        OmiNotificationListener incomplete = new OmiNotificationListener(f.gate,
            () -> new StatusBarNotification[] { activeCard("partial", f.wall - 1), null });
        incomplete.onListenerConnected();
        f.advance(1); f.fresh("unknown-after-partial");
        assertEquals(1, f.pulses.size());
        new OmiNotificationListener(f.gate, () -> new StatusBarNotification[0]).onListenerConnected();
        f.advance(1); f.fresh("known-new");
        assertEquals(2, f.pulses.size());
    }

    public void testActiveSnapshotHonoursBoundAndNeverRequestsPulses() {
        Fixture f = ready();
        StatusBarNotification[] active = new StatusBarNotification[OmiNotificationListener.EventGate.MAX_KEYS + 1];
        for (int i = 0; i < active.length; i++) active[i] = activeCard("seed-" + i, f.wall - 1);
        new OmiNotificationListener(f.gate, () -> active).onListenerConnected();
        assertEquals(OmiNotificationListener.EventGate.MAX_KEYS, f.gate.rememberedCount());
        assertEquals(0, f.pulses.size());
        f.advance(1); f.fresh("untracked-new");
        assertEquals(0, f.pulses.size());
    }

    public void testSilentMetadataUpdatesCancelPendingWorkAndNeverRearmADelivery() {
        Fixture f = ready(); f.fresh("one");
        BooleanSupplier first = f.pulses.get(0);
        f.gate.posted("one", OTHER_PACKAGE, f.wall, false);
        assertFalse("Same-delivery silent re-ranking cancels queued work", first.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        NotificationChannel muted = channel(NotificationManager.IMPORTANCE_DEFAULT, false, false);
        f.gate.posted("one", OTHER_PACKAGE, f.wall, alerting(notification(), muted));
        long silentPostTime = f.wall;
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("one", OTHER_PACKAGE, silentPostTime, true);
        assertEquals(1, f.pulses.size());
        f.fresh("one");
        assertEquals(2, f.pulses.size());
        assertFalse(first.getAsBoolean());
    }

    public void testOffDeniedIgnoredAndOwnAppEventsNeverBecomeDeferredAlerts() {
        Fixture f = new Fixture();
        f.gate.connect(); f.advance(1);
        f.fresh("while-off");
        NotificationVibrations.setEnabled(context, true);
        f.access = false;
        f.fresh("while-denied");
        f.access = true;
        f.gate.posted("ignored", OTHER_PACKAGE, f.wall, false);
        f.gate.posted("own-app", context.getPackageName(), f.wall, true);
        f.fresh("while-off"); f.fresh("while-denied"); f.fresh("ignored");
        assertEquals(0, f.pulses.size());
        f.fresh("new-allowed");
        assertEquals(1, f.pulses.size());
    }

    public void testCooldownUsesElapsedTimeNotWallClock() {
        Fixture f = ready();
        f.fresh("one");
        f.wall += 60000;
        f.fresh("wall-jump");
        assertEquals(1, f.pulses.size());
        f.elapsed += OmiNotificationListener.EventGate.COOLDOWN_MS;
        f.fresh("elapsed-ready");
        assertEquals(2, f.pulses.size());
        f.elapsed--;
        f.fresh("elapsed-backward");
        assertEquals(2, f.pulses.size());
    }

    public void testBoundedKeysFailClosedOnSaturationAndResetOnReconnect() {
        Fixture f = ready();
        for (int i = 0; i < OmiNotificationListener.EventGate.MAX_KEYS + 20; i++) {
            f.gate.posted("ignored-" + i, OTHER_PACKAGE, f.wall, false);
        }
        assertEquals(OmiNotificationListener.EventGate.MAX_KEYS, f.gate.rememberedCount());
        f.gate.removed("ignored-0", f.wall);
        f.fresh("overflow-update");
        assertEquals(0, f.pulses.size());
        f.gate.disconnect(); f.gate.connect(); f.advance(1);
        assertEquals(0, f.gate.rememberedCount());
        f.fresh("after-reconnect");
        assertEquals(1, f.pulses.size());
    }

    public void testRemovedHistoryExpiresWithoutRevivingReplaysAfterClockRollback() {
        Fixture f = ready();
        long removedPostTime = f.wall;
        for (int i = 0; i < OmiNotificationListener.EventGate.MAX_KEYS; i++) {
            f.gate.posted("removed-" + i, OTHER_PACKAGE, removedPostTime, false);
            f.gate.removed("removed-" + i, removedPostTime);
        }
        assertEquals(OmiNotificationListener.EventGate.MAX_KEYS, f.gate.rememberedCount());
        f.advance(OmiNotificationListener.EventGate.MAX_AGE_MS + 1);
        f.fresh("after-expiry");
        assertEquals(1, f.pulses.size());
        assertEquals(1, f.gate.rememberedCount());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.wall = removedPostTime;
        f.fresh("removed-0");
        assertEquals("Forgotten stale deliveries stay stale after wall-clock rollback", 1, f.pulses.size());
    }

    public void testSaturationCancelsPendingWorkButExistingKeysDoNotConsumeMoreSlots() {
        Fixture f = ready(); f.fresh("one");
        for (int i = 1; i < OmiNotificationListener.EventGate.MAX_KEYS; i++) {
            f.gate.posted("active-" + i, OTHER_PACKAGE, f.wall, false);
        }
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.fresh("one");
        assertEquals(2, f.pulses.size());
        BooleanSupplier pending = f.pulses.get(1);
        assertTrue(pending.getAsBoolean());
        f.fresh("overflow");
        assertEquals(2, f.pulses.size());
        assertEquals(OmiNotificationListener.EventGate.MAX_KEYS, f.gate.rememberedCount());
        assertFalse(pending.getAsBoolean());
    }

    public void testOwnAppAndExpiredUnknownRemovalsCannotSaturateTheGate() {
        Fixture f = ready(); f.fresh("one");
        OmiNotificationListener listener = new OmiNotificationListener(f.gate);
        StatusBarNotification own = new StatusBarNotification(context.getPackageName(), context.getPackageName(),
            1, "synthetic-own", Process.myUid(), Process.myPid(), 0, notification(), Process.myUserHandle(), f.wall);
        listener.onNotificationRemoved(own);
        assertEquals(1, f.gate.rememberedCount());
        for (int i = 1; i < OmiNotificationListener.EventGate.MAX_KEYS; i++) {
            f.gate.posted("active-" + i, OTHER_PACKAGE, f.wall, false);
        }
        listener.onNotificationRemoved(own);
        f.gate.removed("already-expired", f.wall - OmiNotificationListener.EventGate.MAX_AGE_MS - 1);
        assertTrue(f.pulses.get(0).getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.fresh("one");
        assertEquals(2, f.pulses.size());
        assertEquals(OmiNotificationListener.EventGate.MAX_KEYS, f.gate.rememberedCount());
    }

    public void testMalformedMetadataKeysAreNotRetained() {
        Fixture f = ready();
        f.gate.posted(null, OTHER_PACKAGE, f.wall, true);
        f.gate.posted("", OTHER_PACKAGE, f.wall, true);
        f.gate.posted(new String(new char[OmiNotificationListener.EventGate.MAX_KEY_CHARS + 1]), OTHER_PACKAGE, f.wall, true);
        f.gate.posted("missing-package", null, f.wall, true);
        f.gate.posted("empty-package", "", f.wall, true);
        assertEquals(0, f.pulses.size());
        assertEquals(0, f.gate.rememberedCount());
    }

    public void testSinkRunsOutsideGateLockAndCancellationStillWorksBeforeUse() {
        NotificationVibrations.setEnabled(context, true);
        long[] now = {1000000};
        List<BooleanSupplier> sent = new ArrayList<>();
        OmiNotificationListener.EventGate[] holder = new OmiNotificationListener.EventGate[1];
        holder[0] = new OmiNotificationListener.EventGate(context.getPackageName(), () -> now[0],
            () -> now[0], () -> NotificationVibrations.currentGuard(context, () -> true), () -> true,
            guard -> {
                assertFalse("No gate -> service lock inversion", Thread.holdsLock(holder[0]));
                holder[0].disconnect();
                assertFalse(guard.getAsBoolean());
                sent.add(guard);
            });
        holder[0].connect(); now[0]++;
        holder[0].posted("synthetic", OTHER_PACKAGE, now[0], true);
        assertEquals(1, sent.size());
    }

    public void testListenerQueuedGuardCancelsOnOptOutAndRevocationBetweenPulses() {
        Fixture f = ready(); f.fresh("one");
        BooleanSupplier queued = f.pulses.get(0);
        assertTrue(queued.getAsBoolean());
        NotificationVibrations.setEnabled(context, false);
        assertFalse(queued.getAsBoolean());
        NotificationVibrations.setEnabled(context, true);
        assertFalse(queued.getAsBoolean());
        long originalPostTime = f.wall;
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted("one", OTHER_PACKAGE, originalPostTime, true);
        assertEquals("Off -> on cannot replay the previous delivery", 1, f.pulses.size());
        f.fresh("one");
        BooleanSupplier next = f.pulses.get(1);
        assertTrue(next.getAsBoolean());
        f.access = false;
        assertFalse(next.getAsBoolean());
        f.access = true;
        assertFalse(next.getAsBoolean());
    }

    public void testListenerDisconnectDestroyAndDndCancelQueuedWork() {
        Fixture f = ready();
        OmiNotificationListener listener = new OmiNotificationListener(f.gate);
        f.fresh("one");
        BooleanSupplier beforeDisconnect = f.pulses.get(0);
        listener.onListenerDisconnected();
        assertFalse(beforeDisconnect.getAsBoolean());
        listener.onListenerConnected(); f.advance(1); f.fresh("two");
        assertFalse(beforeDisconnect.getAsBoolean());
        BooleanSupplier beforeDnd = f.pulses.get(1);
        listener.onInterruptionFilterChanged(NotificationManager.INTERRUPTION_FILTER_NONE);
        assertFalse(beforeDnd.getAsBoolean());
        listener.onInterruptionFilterChanged(NotificationManager.INTERRUPTION_FILTER_ALL);
        assertFalse(beforeDnd.getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS); f.fresh("three");
        BooleanSupplier beforeDestroy = f.pulses.get(2);
        assertTrue(beforeDestroy.getAsBoolean());
        listener.onDestroy();
        assertFalse(beforeDestroy.getAsBoolean());
        f.fresh("after-destroy");
        assertEquals(3, f.pulses.size());
    }

    public void testDndIsCheckedAgainAtDeliveryAndDeniedEventsDoNotReplay() {
        Fixture f = ready();
        f.fresh("one");
        f.interruptions = false;
        assertFalse(f.pulses.get(0).getAsBoolean());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.fresh("during-dnd");
        assertEquals(1, f.pulses.size());
        f.interruptions = true;
        f.fresh("during-dnd");
        assertEquals(1, f.pulses.size());
        f.fresh("after-dnd");
        assertEquals(2, f.pulses.size());
        assertTrue(OmiNotificationListener.allowsInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL));
        for (int filter : new int[]{NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS}) {
            assertFalse(OmiNotificationListener.allowsInterruptionFilter(filter));
        }
    }

    public void testMetadataFiltersOngoingForegroundSummaryAndMutedChannels() {
        Notification ordinary = notification();
        NotificationChannel loud = channel(NotificationManager.IMPORTANCE_DEFAULT, true, false);
        assertTrue(alerting(ordinary, loud));
        Notification once = notification(); once.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        assertTrue("ONLY_ALERT_ONCE is a lifecycle rule, not an unconditional mute", alerting(once, loud));
        assertTrue(alerting(ordinary, channel(NotificationManager.IMPORTANCE_DEFAULT, false, true)));
        assertFalse(alerting(ordinary, channel(NotificationManager.IMPORTANCE_DEFAULT, false, false)));
        assertFalse(alerting(ordinary, channel(NotificationManager.IMPORTANCE_LOW, true, true)));
        assertFalse(alerting(ordinary, null));
        assertFalse(alerting(null, loud));
        for (int flag : new int[]{Notification.FLAG_ONGOING_EVENT, Notification.FLAG_FOREGROUND_SERVICE,
                Notification.FLAG_GROUP_SUMMARY}) {
            Notification ignored = notification(); ignored.flags |= flag;
            assertFalse(alerting(ignored, loud));
        }
        Notification child = new Notification.Builder(context, "synthetic")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("synthetic-group")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).build();
        assertFalse(alerting(child, loud));
        assertFalse(OmiNotificationListener.alertingMetadata(ordinary, loud, NotificationManager.IMPORTANCE_LOW, true, 0, false));
        assertFalse(OmiNotificationListener.alertingMetadata(ordinary, loud, NotificationManager.IMPORTANCE_DEFAULT, false, 0, false));
        assertFalse(OmiNotificationListener.alertingMetadata(ordinary, loud, NotificationManager.IMPORTANCE_DEFAULT, true, 1, false));
        assertFalse(OmiNotificationListener.alertingMetadata(ordinary, loud, NotificationManager.IMPORTANCE_DEFAULT, true, 0, true));
    }

    public void testActualCallbacksFailClosedWithoutRankingAndRemovalRetainsReplayFence() {
        Fixture f = ready();
        OmiNotificationListener listener = new OmiNotificationListener(f.gate);
        StatusBarNotification notification = new StatusBarNotification(OTHER_PACKAGE, OTHER_PACKAGE,
            1, "synthetic", Process.myUid(), Process.myPid(), 0, notification(), Process.myUserHandle(), f.wall);
        listener.onNotificationPosted(null, null);
        listener.onNotificationPosted(notification, null);
        assertEquals(0, f.pulses.size());
        assertEquals(1, f.gate.rememberedCount());
        listener.onNotificationRemoved(notification, null, 0);
        assertEquals(1, f.gate.rememberedCount());
        f.advance(OmiNotificationListener.EventGate.COOLDOWN_MS);
        f.gate.posted(notification.getKey(), OTHER_PACKAGE, notification.getPostTime(), true);
        assertEquals(0, f.pulses.size());
        f.gate.posted(notification.getKey(), OTHER_PACKAGE, f.wall, true);
        assertEquals(1, f.pulses.size());
        listener.onNotificationRemoved(notification, null);
        assertTrue("The public removal overload forwards the removed delivery timestamp",
            f.pulses.get(0).getAsBoolean());
    }

    public void testActualRemovalBeforePostedCallbackCannotReviveThatDelivery() {
        Fixture f = ready();
        OmiNotificationListener listener = new OmiNotificationListener(f.gate);
        StatusBarNotification removed = new StatusBarNotification(OTHER_PACKAGE, OTHER_PACKAGE,
            1, "synthetic", Process.myUid(), Process.myPid(), 0, notification(), Process.myUserHandle(), f.wall);
        listener.onNotificationRemoved(removed);
        f.gate.posted(removed.getKey(), OTHER_PACKAGE, removed.getPostTime(), true);
        assertEquals(0, f.pulses.size());
        f.advance(1);
        f.gate.posted(removed.getKey(), OTHER_PACKAGE, f.wall, true, true);
        assertEquals(1, f.pulses.size());
    }

    public void testActualSwitchDefaultsOffAndPersistsAcrossActivityRecreation() throws Exception {
        openActivity();
        main(() -> {
            assertFalse(toggle().isChecked());
            assertTrue(toggle().isEnabled());
            assertTrue(text("omi.notifications.status").startsWith("Off"));
            assertFalse(tag("omi.notifications.test").isEnabled());
            assertTrue(tag("omi.notifications.access").isEnabled());
            toggle().setChecked(true);
        });
        dismissAccessExplanation();
        assertTrue(NotificationVibrations.enabled(context));
        recreate();
        main(() -> {
            assertTrue(toggle().isChecked());
            assertTrue(text("omi.notifications.status").contains("waiting for Android notification access"));
            assertFalse(tag("omi.notifications.test").isEnabled());
            toggle().setChecked(false);
        });
        assertFalse(NotificationVibrations.enabled(context));
        recreate();
        main(() -> { assertFalse(toggle().isChecked()); assertTrue(text("omi.notifications.status").startsWith("Off")); });
        assertNoCaptureOrConnection();
    }

    public void testUiDenialWaitsAndManualTestNeedsAccessEvenWhenConnectionFlagIsSet() throws Exception {
        NotificationVibrations.setEnabled(context, true);
        openActivity();
        main(() -> {
            assertFalse(((Button) tag("omi.notifications.test")).isEnabled());
            OmiCaptureService.controlsActive = true;
            refresh();
            assertTrue(toggle().isEnabled());
            assertFalse(tag("omi.notifications.test").isEnabled());
            assertTrue(text("omi.notifications.status").contains("waiting for Android notification access"));
            OmiCaptureService.controlsActive = false;
            OmiCaptureService.active = true;
            refresh();
            assertTrue(toggle().isEnabled());
            assertFalse(tag("omi.notifications.test").isEnabled());
            toggle().setChecked(false);
            assertFalse(NotificationVibrations.enabled(context));
            OmiCaptureService.active = false;
        });
        assertFalse(NotificationVibrations.accessGranted(context));
        assertNoCaptureOrConnection();
    }

    public void testUiRefreshPreservesSwitchInstanceAndLiveMappings() throws Exception {
        prefs.edit().putString("single_action", "toggle").putString("double_action", "media_pause").commit();
        openActivity();
        main(() -> {
            Switch original = toggle();
            for (int i = 0; i < 10; i++) refresh();
            assertSame(original, toggle());
            NotificationVibrations.setEnabled(context, true);
            refresh();
            assertTrue(toggle().isChecked());
            assertEquals("toggle", ButtonActions.action(context, ButtonEvent.SINGLE));
            assertEquals("media_pause", ButtonActions.action(context, ButtonEvent.DOUBLE));
        });
        assertNoCaptureOrConnection();
    }

    public void testManualRequestWithoutOwnerCannotConnectOrCapture() {
        NotificationVibrations.setEnabled(context, true);
        OmiCaptureService.requestNotificationPulse(NotificationVibrations.currentGuard(context, () -> true));
        OmiCaptureService.requestNotificationPulse(NotificationVibrations.currentGuard(context));
        OmiCaptureService.requestNotificationPulse(null);
        assertNoCaptureOrConnection();
    }

    private void assertNoCaptureOrConnection() {
        assertFalse(CaptureService.active);
        assertFalse(OmiCaptureService.active);
        assertFalse(OmiCaptureService.controlsActive);
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(100)) {
            assertFalse(CaptureService.class.getName().equals(service.service.getClassName()));
            assertFalse(OmiCaptureService.class.getName().equals(service.service.getClassName()));
        }
    }

    private Notification notification() {
        return new Notification.Builder(context, "synthetic")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build();
    }
    private NotificationChannel channel(int importance, boolean sound, boolean vibrate) {
        NotificationChannel channel = new NotificationChannel("synthetic", "Synthetic", importance);
        channel.setSound(sound ? Uri.parse("content://synthetic/alert") : null, null);
        channel.enableVibration(vibrate);
        return channel;
    }
    private boolean alerting(Notification notification, NotificationChannel channel) {
        return OmiNotificationListener.alertingMetadata(notification, channel,
            NotificationManager.IMPORTANCE_DEFAULT, true, 0, false);
    }
    private Fixture ready() {
        NotificationVibrations.setEnabled(context, true);
        Fixture f = new Fixture(); f.gate.connect(); f.advance(1); return f;
    }
    private final class Fixture {
        long wall = 1000000, elapsed = 1000;
        boolean access = true, interruptions = true;
        final List<BooleanSupplier> pulses = new ArrayList<>();
        final OmiNotificationListener.EventGate gate = new OmiNotificationListener.EventGate(
            context.getPackageName(), () -> wall, () -> elapsed,
            () -> NotificationVibrations.currentGuard(context, () -> access), () -> interruptions, pulses::add);
        void advance(long ms) { wall += ms; elapsed += ms; }
        void fresh(String key) { gate.posted(key, OTHER_PACKAGE, wall, true); }
    }

    private void openActivity() { activity = getActivity(); getInstrumentation().waitForIdleSync(); }
    private View tag(String name) {
        View view = activity.getWindow().getDecorView().findViewWithTag(name);
        assertNotNull("Missing settings view " + name, view); return view;
    }
    private Switch toggle() { return (Switch) tag("omi.notifications.enabled"); }
    private String text(String name) { return ((TextView) tag(name)).getText().toString(); }
    private void refresh() throws Exception {
        Method method = OmiSettingsActivity.class.getDeclaredMethod("refresh");
        method.setAccessible(true); method.invoke(activity);
    }
    private void dismissAccessExplanation() {
        getInstrumentation().waitForIdleSync();
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        getInstrumentation().waitForIdleSync();
    }
    private void recreate() throws Exception {
        OmiSettingsActivity old = activity;
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(OmiSettingsActivity.class.getName(), null, false);
        try {
            main(old::recreate);
            Activity replacement = getInstrumentation().waitForMonitorWithTimeout(monitor, 10000);
            assertNotNull("Settings recreation", replacement); activity = (OmiSettingsActivity) replacement;
            getInstrumentation().waitForIdleSync(); assertNotSame(old, activity);
        } finally { getInstrumentation().removeMonitor(monitor); }
    }
    private interface Checked { void run() throws Exception; }
    private void main(Checked work) throws Exception {
        Throwable[] error = {null};
        getInstrumentation().runOnMainSync(() -> { try { work.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
}
