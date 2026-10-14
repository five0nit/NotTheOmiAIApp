package app.nottheomi.ai;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Opt-in only. Notification access is independent of permission to POST_NOTIFICATIONS. */
public final class NotificationVibrations {
    static final String ENABLED_KEY = "notification_vibrations_enabled";
    static final String MODE_KEY = "notification_vibrations_mode";
    static final String FILTER_KEY = "notification_vibrations_filter";
    static final String PACKAGES_KEY = "notification_vibrations_packages";
    static final String DEFAULT_PATTERN_KEY = "notification_vibrations_pattern";
    static final String APP_PATTERN_PREFIX = "notification_vibrations_pattern.";
    public static final String MODE_ALL = "all", MODE_SELECTED = "selected";
    public static final String FILTER_ALL = "all_notifications", FILTER_ALERTING = "alerting_only";
    public static final String DEFAULT_PATTERN = HapticPatterns.DEFAULT;
    private static final Object LOCK = new Object();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static boolean previouslyGranted;

    private NotificationVibrations() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("omi", Context.MODE_PRIVATE);
    }

    /** Missing or malformed preferences fail closed, without rewriting existing device settings. */
    public static boolean enabled(Context context) {
        try { return preferences(context).getBoolean(ENABLED_KEY, false); }
        catch (ClassCastException malformed) { return false; }
    }

    public static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            if (enabled(context) != enabled) GENERATION.incrementAndGet();
            // apply publishes in memory before returning; old work is fenced before that publication.
            preferences(context).edit().putBoolean(ENABLED_KEY, enabled).apply();
        }
    }

    /** Missing mode preserves old all-app behavior; a corrupt mode must never broaden access. */
    public static String mode(Context context) {
        try {
            String value = preferences(context).getString(MODE_KEY, MODE_ALL);
            return MODE_ALL.equals(value) ? MODE_ALL : MODE_SELECTED;
        } catch (ClassCastException malformed) { return MODE_SELECTED; }
    }

    public static void setMode(Context context, String mode) {
        if (!MODE_ALL.equals(mode) && !MODE_SELECTED.equals(mode))
            throw new IllegalArgumentException("Unknown notification app mode");
        synchronized (LOCK) {
            if (!mode.equals(mode(context))) GENERATION.incrementAndGet();
            preferences(context).edit().putString(MODE_KEY, mode).apply();
        }
    }

    /** All delivered notification types by default; malformed policy retains restrictive filtering. */
    public static String filter(Context context) {
        try {
            String value = preferences(context).getString(FILTER_KEY, FILTER_ALL);
            return FILTER_ALL.equals(value) ? FILTER_ALL : FILTER_ALERTING;
        } catch (ClassCastException malformed) { return FILTER_ALERTING; }
    }

    public static boolean includeAllNotifications(Context context) {
        return FILTER_ALL.equals(filter(context));
    }

    public static void setFilter(Context context, String filter) {
        if (!FILTER_ALL.equals(filter) && !FILTER_ALERTING.equals(filter))
            throw new IllegalArgumentException("Unknown notification filter");
        synchronized (LOCK) {
            if (!filter.equals(filter(context))) GENERATION.incrementAndGet();
            preferences(context).edit().putString(FILTER_KEY, filter).apply();
        }
    }

    /** Returns a defensive snapshot. Malformed selected data is an empty allowlist, never all. */
    public static Set<String> selectedPackages(Context context) {
        try {
            Set<String> stored = preferences(context).getStringSet(PACKAGES_KEY, Collections.emptySet());
            if (stored == null) return Collections.emptySet();
            Set<String> result = new HashSet<>();
            for (String name : stored) {
                if (!validPackageName(name)) return Collections.emptySet();
                result.add(name);
            }
            return Collections.unmodifiableSet(result);
        } catch (ClassCastException malformed) { return Collections.emptySet(); }
    }

    public static void setSelectedPackages(Context context, Set<String> packages) {
        if (packages == null) throw new IllegalArgumentException("Missing notification app selection");
        Set<String> snapshot = new HashSet<>(packages);
        for (String name : snapshot) requirePackageName(name);
        synchronized (LOCK) {
            if (!snapshot.equals(selectedPackages(context))) GENERATION.incrementAndGet();
            preferences(context).edit().putStringSet(PACKAGES_KEY, snapshot).apply();
        }
    }

    public static void setAppSelected(Context context, String packageName, boolean selected) {
        requirePackageName(packageName);
        synchronized (LOCK) {
            Set<String> packages = new HashSet<>(selectedPackages(context));
            if (selected) packages.add(packageName); else packages.remove(packageName);
            setSelectedPackages(context, packages);
        }
    }

    public static boolean packageAllowed(Context context, String packageName) {
        return validPackageName(packageName) && !context.getPackageName().equals(packageName)
            && (MODE_ALL.equals(mode(context)) || selectedPackages(context).contains(packageName));
    }

    static boolean validPackageName(String name) {
        // Accept the framework package "android" too; never trim or reinterpret an exact ID.
        return name != null && name.length() <= 255
            && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
    }

    private static void requirePackageName(String name) {
        if (!validPackageName(name)) throw new IllegalArgumentException("Invalid exact package name");
    }

    static boolean validPattern(String pattern) {
        return HapticPatterns.isKnown(pattern);
    }

    private static void requirePattern(String pattern) {
        if (!validPattern(pattern)) throw new IllegalArgumentException("Unknown notification vibration pattern");
    }

    public static String defaultPattern(Context context) {
        try {
            String pattern = preferences(context).getString(DEFAULT_PATTERN_KEY, DEFAULT_PATTERN);
            return validPattern(pattern) ? pattern : DEFAULT_PATTERN;
        } catch (ClassCastException malformed) { return DEFAULT_PATTERN; }
    }

    public static void setDefaultPattern(Context context, String pattern) {
        requirePattern(pattern);
        synchronized (LOCK) {
            if (!pattern.equals(defaultPattern(context))) GENERATION.incrementAndGet();
            preferences(context).edit().putString(DEFAULT_PATTERN_KEY, pattern).apply();
        }
    }

    /** null means use the default; malformed overrides also use the bounded default pattern. */
    public static String appPatternOverride(Context context, String packageName) {
        if (!validPackageName(packageName)) return null;
        try {
            String pattern = preferences(context).getString(APP_PATTERN_PREFIX + packageName, null);
            return validPattern(pattern) ? pattern : null;
        } catch (ClassCastException malformed) { return null; }
    }

    public static String patternForPackage(Context context, String packageName) {
        String override = appPatternOverride(context, packageName);
        return override == null ? defaultPattern(context) : override;
    }

    public static void setAppPattern(Context context, String packageName, String pattern) {
        requirePackageName(packageName);
        if (pattern != null) requirePattern(pattern);
        synchronized (LOCK) {
            String before = appPatternOverride(context, packageName);
            if (pattern == null ? before != null : !pattern.equals(before)) GENERATION.incrementAndGet();
            SharedPreferences.Editor editor = preferences(context).edit();
            if (pattern == null) editor.remove(APP_PATTERN_PREFIX + packageName);
            else editor.putString(APP_PATTERN_PREFIX + packageName, pattern);
            editor.apply();
        }
    }

    /** Immutable pattern + guard pair, captured under one configuration-generation fence. */
    static final class PulseRequest {
        final String pattern;
        final BooleanSupplier guard;
        PulseRequest(String pattern, BooleanSupplier guard) { this.pattern = pattern; this.guard = guard; }
    }

    static PulseRequest currentPulse(Context context, String packageName) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return currentPulse(owner, packageName, () -> readAccess(owner));
    }

    static PulseRequest currentPulse(Context context, String packageName, BooleanSupplier access) {
        synchronized (LOCK) {
            return new PulseRequest(patternForPackage(context, packageName),
                captureGuard(context, packageName, true, access));
        }
    }

    static ComponentName listenerComponent(Context context) {
        return new ComponentName(context, OmiNotificationListener.class);
    }

    private static boolean readAccess(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                return manager != null && manager.isNotificationListenerAccessGranted(listenerComponent(context));
            }
            return containsComponent(Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners"), listenerComponent(context));
        } catch (RuntimeException unavailable) {
            // Access revoked, platform service unavailable, or OEM denial: never assume permission.
            return false;
        }
    }

    /** Exact component comparison: another listener in the same package does not grant access. */
    static boolean containsComponent(String flattened, ComponentName expected) {
        if (flattened == null || expected == null) return false;
        for (String entry : flattened.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private static boolean observeAccess(BooleanSupplier access) {
        boolean granted;
        try { granted = access.getAsBoolean(); }
        catch (RuntimeException unavailable) { granted = false; }
        if (previouslyGranted && !granted) GENERATION.incrementAndGet();
        previouslyGranted = granted;
        return granted;
    }

    public static boolean accessGranted(Context context) {
        synchronized (LOCK) { return observeAccess(() -> readAccess(context)); }
    }

    /** The user, never this app, changes notification access in Android Settings. */
    public static Intent accessSettingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    public static void openAccessSettings(Activity activity) {
        activity.startActivity(accessSettingsIntent());
    }

    /**
     * Capture at event ingress, evaluate immediately before EACH motor write. A guard created while
     * off/denied stays false. Off -> on, observed revocation and listener loss cancel old work forever.
     * This does not connect BLE or grant notification access, and must not be used to start capture.
     */
    public static BooleanSupplier currentGuard(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return currentGuard(owner, () -> readAccess(owner));
    }

    public static BooleanSupplier currentGuard(Context context, String packageName) {
        return currentPulse(context, packageName).guard;
    }

    // Instance-local access supplier seam for deterministic tests; no global permission override.
    static BooleanSupplier currentGuard(Context context, BooleanSupplier access) {
        return captureGuard(context, null, false, access);
    }

    static BooleanSupplier currentGuard(Context context, String packageName, BooleanSupplier access) {
        return captureGuard(context, packageName, true, access);
    }

    private static BooleanSupplier captureGuard(Context context, String packageName,
            boolean packageSpecific, BooleanSupplier access) {
        synchronized (LOCK) {
            boolean initiallyAllowed = observeAccess(access) && enabled(context)
                && (!packageSpecific || packageAllowed(context, packageName));
            long generation = GENERATION.get();
            return () -> {
                synchronized (LOCK) {
                    return initiallyAllowed && generation == GENERATION.get()
                        && observeAccess(access) && enabled(context)
                        && (!packageSpecific || packageAllowed(context, packageName))
                        && generation == GENERATION.get();
                }
            };
        }
    }

    static void invalidate() {
        synchronized (LOCK) { GENERATION.incrementAndGet(); }
    }

    // Do not expose a setter's increment until its preference publication completes under LOCK.
    static long configurationGeneration() {
        synchronized (LOCK) { return GENERATION.get(); }
    }
}
