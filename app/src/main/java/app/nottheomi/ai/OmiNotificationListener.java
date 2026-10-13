package app.nottheomi.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Metadata-only notification ingress. Never reads titles, text, extras, icons or actions; never
 * stores/logs/sends notification payloads. The only output is a guarded, content-free pulse request
 * to an ALREADY connected service. No startService, BLE connect, recording or backlog replay.
 */
public final class OmiNotificationListener extends NotificationListenerService {
    private EventGate events;
    private Supplier<StatusBarNotification[]> activeSnapshot;
    private BiFunction<String, RankingMap, Ranking> rankingSource;

    public OmiNotificationListener() {}

    // Per-instance synthetic seams; Android constructs the public no-argument service.
    OmiNotificationListener(EventGate events) { this(events, () -> new StatusBarNotification[0]); }
    OmiNotificationListener(EventGate events, Supplier<StatusBarNotification[]> activeSnapshot) {
        this(events, activeSnapshot, null);
    }

    // Tests can supply synthetic platform metadata without reflection/hidden APIs or system access.
    OmiNotificationListener(EventGate events, Supplier<StatusBarNotification[]> activeSnapshot,
            BiFunction<String, RankingMap, Ranking> rankingSource) {
        this.events = events;
        this.activeSnapshot = activeSnapshot;
        this.rankingSource = rankingSource;
    }

    @Override public void onListenerConnected() {
        if (events == null) {
            events = new EventGate(getPackageName(), System::currentTimeMillis,
                SystemClock::elapsedRealtime, packageName -> NotificationVibrations.currentPulse(this, packageName),
                this::interruptionsAllowed, (pattern, guard) -> OmiCaptureService.requestNotificationPulse(pattern, guard));
        }
        events.connect();
        try {
            // Seed identity/timestamps only, never replay content or pulses. An active card may
            // receive its first callback only AFTER reconnect, with a newer postTime.
            StatusBarNotification[] active = activeSnapshot == null
                ? getActiveNotifications() : activeSnapshot.get();
            if (active == null) { events.disconnect(); return; }
            for (StatusBarNotification posted : active) {
                if (posted == null) { events.disconnect(); return; }
                events.posted(posted.getKey(), posted.getPackageName(), posted.getPostTime(), false);
            }
        } catch (RuntimeException unavailable) {
            // Unknown existing-card history must not enable repeat FLAG_ONLY_ALERT_ONCE alerts.
            events.disconnect();
        }
    }

    @Override public void onListenerDisconnected() {
        if (events != null) events.disconnect();
        else NotificationVibrations.invalidate();
    }

    @Override public void onDestroy() {
        if (events != null) events.disconnect();
        else NotificationVibrations.invalidate();
        super.onDestroy();
    }

    private boolean interruptionsAllowed() {
        try { return allowsInterruptionFilter(getCurrentInterruptionFilter()); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** Conservative: do not turn a wearable into a workaround for any active DND mode. */
    static boolean allowsInterruptionFilter(int filter) {
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    @Override public void onInterruptionFilterChanged(int filter) {
        if (!allowsInterruptionFilter(filter)) NotificationVibrations.invalidate();
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        RankingMap rankings = null;
        try { rankings = getCurrentRanking(); }
        catch (RuntimeException unavailable) { /* Unknown ranking fails closed below. */ }
        onNotificationPosted(notification, rankings);
    }

    @Override public void onNotificationPosted(StatusBarNotification posted, RankingMap rankings) {
        if (events == null || posted == null) return;
        boolean alerting = false;
        boolean onlyAlertOnce = false;
        try {
            Notification notification = posted.getNotification();
            onlyAlertOnce = notification != null
                && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
            Ranking ranking = rankingSource == null ? readRanking(posted.getKey(), rankings)
                : rankingSource.apply(posted.getKey(), rankings);
            if (ranking != null) {
                alerting = alertingMetadata(notification, ranking.getChannel(),
                    ranking.getImportance(), ranking.matchesInterruptionFilter(),
                    ranking.getSuppressedVisualEffects(), Build.VERSION.SDK_INT >= 28 && ranking.isSuspended());
            }
        } catch (RuntimeException unavailable) {
            // Bad/missing platform metadata must not turn into an alert. Never log the notification.
        }
        events.posted(posted.getKey(), posted.getPackageName(), posted.getPostTime(), alerting, onlyAlertOnce);
    }

    private static Ranking readRanking(String key, RankingMap rankings) {
        Ranking ranking = new Ranking();
        return rankings != null && rankings.getRanking(key, ranking) ? ranking : null;
    }

    @Override public void onNotificationRemoved(StatusBarNotification posted) {
        if (events == null || posted == null) return;
        String packageName = posted.getPackageName();
        if (packageName == null || packageName.isEmpty() || events.ownPackage.equals(packageName)) return;
        events.removed(posted.getKey(), posted.getPostTime());
    }

    @Override public void onNotificationRemoved(StatusBarNotification posted, RankingMap rankings) {
        onNotificationRemoved(posted);
    }

    @Override public void onNotificationRemoved(StatusBarNotification posted, RankingMap rankings, int reason) {
        onNotificationRemoved(posted);
    }

    /** Shared production/test filter, using only platform delivery metadata. */
    static boolean alertingMetadata(Notification notification, NotificationChannel channel,
            int importance, boolean matchesFilter, int suppressedEffects, boolean suspended) {
        if (notification == null || channel == null || !matchesFilter || suppressedEffects != 0
                || suspended || importance < NotificationManager.IMPORTANCE_DEFAULT
                || channel.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return false;
        int excluded = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
            | Notification.FLAG_GROUP_SUMMARY;
        if ((notification.flags & excluded) != 0) return false;
        if (notification.getGroup() != null
                && notification.getGroupAlertBehavior() == Notification.GROUP_ALERT_SUMMARY) return false;
        return channel.getSound() != null || channel.shouldVibrate();
    }

    /**
     * Bounded in-memory event bookkeeping, shared by the real callbacks and deterministic tests.
     * Retains key digests, delivery timestamps and active state, never notification payloads/tags.
     * Same key + postTime is one delivery; a newer timestamp may alert on the same active card.
     * Removed-key tombstones expire outside the replay window. Active keys cannot be evicted safely:
     * forgetting them would re-alert FLAG_ONLY_ALERT_ONCE updates. Saturation fails closed until
     * reconnect rather than guessing about untracked active cards.
     */
    static final class EventGate {
        static final int MAX_KEYS = 256;
        static final int MAX_KEY_CHARS = 2048;
        static final long COOLDOWN_MS = 1500;
        static final long MAX_AGE_MS = 10000;
        private final String ownPackage;
        private final LongSupplier wallClock, elapsedClock;
        private final Function<String, NotificationVibrations.PulseRequest> guardFactory;
        private final BooleanSupplier interruptionsAllowed;
        private final BiConsumer<String, BooleanSupplier> pulseSink;
        private final Map<String, Delivery> seen = new HashMap<>();
        private boolean connected, hasPulsed, saturated;
        private long connectedWall, expiredThroughWall, lastPulseElapsed, epoch;

        private static final class Delivery {
            final long postTime;
            final boolean active;
            Delivery(long postTime, boolean active) { this.postTime = postTime; this.active = active; }
        }

        EventGate(String ownPackage, LongSupplier wallClock, LongSupplier elapsedClock,
                Supplier<BooleanSupplier> guardFactory, BooleanSupplier interruptionsAllowed,
                Consumer<BooleanSupplier> pulseSink) {
            this(ownPackage, wallClock, elapsedClock,
                packageName -> new NotificationVibrations.PulseRequest(NotificationVibrations.DEFAULT_PATTERN,
                    guardFactory.get()), interruptionsAllowed, (pattern, guard) -> pulseSink.accept(guard));
        }

        /** Package-aware production seam: the pattern and guard are one atomic config snapshot. */
        EventGate(String ownPackage, LongSupplier wallClock, LongSupplier elapsedClock,
                Function<String, NotificationVibrations.PulseRequest> guardFactory,
                BooleanSupplier interruptionsAllowed, BiConsumer<String, BooleanSupplier> pulseSink) {
            this.ownPackage = ownPackage;
            this.wallClock = wallClock;
            this.elapsedClock = elapsedClock;
            this.guardFactory = guardFactory;
            this.interruptionsAllowed = interruptionsAllowed;
            this.pulseSink = pulseSink;
        }

        synchronized void connect() {
            NotificationVibrations.invalidate();
            epoch++;
            connectedWall = wallClock.getAsLong();
            expiredThroughWall = connectedWall;
            connected = true;
            hasPulsed = false;
            saturated = false;
            seen.clear();
        }

        synchronized void disconnect() {
            connected = false;
            epoch++;
            seen.clear();
            NotificationVibrations.invalidate();
        }

        void posted(String key, String packageName, long postTime, boolean alerting) {
            posted(key, packageName, postTime, alerting, false);
        }

        void posted(String key, String packageName, long postTime, boolean alerting, boolean onlyAlertOnce) {
            BooleanSupplier guard;
            String pattern;
            synchronized (this) {
                if (!connected || packageName == null || packageName.isEmpty()
                        || ownPackage.equals(packageName) || saturated) return;
                String fingerprint = fingerprint(key);
                if (fingerprint == null) return;
                long nowWall = wallClock.getAsLong();
                expireRemoved(nowWall);
                Delivery previous = seen.get(fingerprint);
                if (previous != null && postTime <= previous.postTime) {
                    // A silent re-ranking of this delivery cancels pending work, but can never
                    // re-arm it. Older callbacks must not invalidate a newer delivery's guard.
                    if (postTime == previous.postTime && !alerting && previous.active)
                        seen.put(fingerprint, new Delivery(postTime, true));
                    return;
                }
                if (previous == null && seen.size() >= MAX_KEYS) { saturated = true; return; }
                // Consume even silent/off/cooldown deliveries; only a newer post may alert later.
                Delivery eventIdentity = new Delivery(postTime, true);
                seen.put(fingerprint, eventIdentity);
                if (!alerting || (onlyAlertOnce && previous != null && previous.active)
                        || postTime <= connectedWall || postTime <= expiredThroughWall || postTime > nowWall
                        || nowWall - postTime > MAX_AGE_MS) return;
                long nowElapsed = elapsedClock.getAsLong();
                if (hasPulsed && (nowElapsed < lastPulseElapsed || nowElapsed - lastPulseElapsed < COOLDOWN_MS)) return;
                NotificationVibrations.PulseRequest request = guardFactory.apply(packageName);
                BooleanSupplier configGuard = request.guard;
                pattern = request.pattern;
                final long eventEpoch = epoch;
                guard = () -> {
                    synchronized (EventGate.this) {
                        return connected && !saturated && epoch == eventEpoch && seen.get(fingerprint) == eventIdentity
                            && configGuard.getAsBoolean() && interruptionsAllowed.getAsBoolean();
                    }
                };
                if (!guard.getAsBoolean()) return;
                // Count attempts, not BLE success: disconnection never accrues a replay queue.
                hasPulsed = true;
                lastPulseElapsed = nowElapsed;
            }
            // Do not take the service/BLE lock while holding our gate (guards run on that worker).
            pulseSink.accept(pattern, guard);
        }

        synchronized void removed(String key, long postTime) {
            if (!connected || saturated) return;
            String fingerprint = fingerprint(key);
            if (fingerprint == null) return;
            expireRemoved(wallClock.getAsLong());
            Delivery previous = seen.get(fingerprint);
            if (previous != null && postTime < previous.postTime) return;
            if (previous == null && postTime <= expiredThroughWall) return;
            if (previous == null && seen.size() >= MAX_KEYS) { saturated = true; return; }
            // Retain replay history: removal cancels a delivery, it does not make it new again.
            seen.put(fingerprint, new Delivery(postTime, false));
        }

        private void expireRemoved(long nowWall) {
            // A monotonic floor keeps expired tombstones safe even after a wall-clock rollback.
            expiredThroughWall = Math.max(expiredThroughWall, nowWall - MAX_AGE_MS - 1);
            seen.entrySet().removeIf(entry -> !entry.getValue().active
                && entry.getValue().postTime <= expiredThroughWall);
        }

        synchronized int rememberedCount() { return seen.size(); }

        private static String fingerprint(String key) {
            if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) return null;
            try {
                return Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError("Android requires SHA-256", impossible);
            }
        }
    }
}
