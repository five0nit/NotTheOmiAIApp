package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local per-gesture actions, native media controls and validated activity targets. */
public final class ButtonActions {
    static final String[] IDS = {"bookmark", "start", "stop", "toggle", "media_play_pause", "media_pause", "shortcut", "ignore"};
    static final String[] LABELS = {"Bookmark in transcript", "Start recording", "Stop & save",
        "Start / stop recording", "Play / pause music", "Pause music", "Phone shortcut", "No action"};
    static final int MAX_URI = 8192, MAX_LABEL = 120, MAX_PARCEL = 32768;
    static final String CHANNEL = "omi_button_shortcuts";
    private static final Object VISIBLE_LOCK = new Object();
    private static WeakReference<Activity> visible = new WeakReference<>(null);
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    private ButtonActions() {}

    static boolean gesture(int event) { return event == ButtonEvent.SINGLE || event == ButtonEvent.DOUBLE; }
    static String key(int event) { return event == ButtonEvent.SINGLE ? "single_action" : "double_action"; }
    static String targetKey(int event) { return key(event) + "_shortcut_uri"; }
    static String labelKey(int event) { return key(event) + "_shortcut_label"; }
    private static String string(SharedPreferences prefs, String key, String fallback) {
        try { return prefs.getString(key, fallback); } catch (ClassCastException invalid) { return fallback; }
    }
    static int index(String id) { for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i; return IDS.length - 1; }

    /** Reads live preferences on every press; legacy bookmark/stop/ignore IDs remain intact. */
    public static String action(Context context, int event) {
        if (!gesture(event)) return "ignore";
        String fallback = event == ButtonEvent.SINGLE ? "bookmark" : "stop";
        SharedPreferences prefs = OmiSettingsActivity.preferences(context);
        // Missing values migrate to the original defaults; corrupt/unknown values fail closed.
        String value = prefs.contains(key(event)) ? string(prefs, key(event), "ignore") : fallback;
        return IDS[index(value)];
    }
    static boolean setAction(Context context, int event, String id) {
        if (!gesture(event) || !IDS[index(id)].equals(id)) return false;
        OmiSettingsActivity.preferences(context).edit().putString(key(event), id).apply();
        if (!"shortcut".equals(id)) cancelNotification(context, event);
        return true;
    }
    static String targetLabel(Context context, int event) {
        if (!gesture(event)) return "No phone shortcut selected";
        String label = string(OmiSettingsActivity.preferences(context), labelKey(event), "");
        return validLabel(label) ? label : "No phone shortcut selected";
    }
    private static boolean validLabel(String label) {
        if (label == null || label.trim().isEmpty() || label.length() > MAX_LABEL) return false;
        for (int i = 0; i < label.length(); i++) {
            int type = Character.getType(label.charAt(i));
            if (type == Character.CONTROL || type == Character.FORMAT) return false;
        }
        return true;
    }

    static final class Choice {
        final ComponentName component;
        final String label;
        final boolean provider;
        Choice(ComponentName component, String label, boolean provider) {
            this.component = component; this.label = label; this.provider = provider;
        }
        Intent intent() {
            Intent intent = new Intent(provider ? Intent.ACTION_CREATE_SHORTCUT : Intent.ACTION_MAIN).setComponent(component);
            if (!provider) intent.addCategory(Intent.CATEGORY_LAUNCHER);
            return intent;
        }
        @Override public String toString() { return (provider ? "Shortcut · " : "Open app · ") + label + "\n" + component.getPackageName(); }
    }
    static List<Choice> choices(Context context) {
        List<Choice> out = new ArrayList<>();
        collect(context, new Intent(Intent.ACTION_CREATE_SHORTCUT), true, out);
        collect(context, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), false, out);
        out.sort(Comparator.comparing((Choice c) -> !c.provider).thenComparing(c -> c.label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }
    private static void collect(Context context, Intent query, boolean provider, List<Choice> out) {
        PackageManager pm = context.getPackageManager();
        for (ResolveInfo match : pm.queryIntentActivities(query, 0)) {
            ActivityInfo info = match.activityInfo;
            if (!allowed(context, info)) continue;
            String label = String.valueOf(match.loadLabel(pm)).trim();
            if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL);
            if (!validLabel(label)) label = info.packageName;
            out.add(new Choice(new ComponentName(info.packageName, info.name), label, provider));
        }
    }
    private static boolean allowed(Context context, ActivityInfo info) {
        return info != null && info.exported && info.enabled && info.applicationInfo != null
            && info.applicationInfo.enabled && !context.getPackageName().equals(info.packageName)
            && (info.permission == null || info.permission.isEmpty()
                || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED);
    }
    static boolean providerAvailable(Context context, ComponentName component) {
        if (component == null) return false;
        Intent query = new Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(component.getPackageName());
        for (ResolveInfo match : context.getPackageManager().queryIntentActivities(query, 0)) {
            ActivityInfo info = match.activityInfo;
            if (allowed(context, info) && component.equals(new ComponentName(info.packageName, info.name))) return true;
        }
        return false;
    }
    private static boolean scalarExtras(Bundle extras) {
        if (extras == null) return true;
        if (extras.size() > 64) return false;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (key == null || key.length() > 256 || !(value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Character || value instanceof Double
                    || value instanceof Float || value instanceof Integer || value instanceof Long || value instanceof Short)) return false;
        }
        return true;
    }

    /** Rejects grant-bearing, nested or oversized payloads, then pins a permitted external activity. */
    static Intent validated(Context context, Intent input, String expectedPackage) {
        if (input == null) return null;
        try {
            Parcel parcel = Parcel.obtain();
            try { input.writeToParcel(parcel, 0); if (parcel.dataSize() > MAX_PARCEL || parcel.hasFileDescriptors()) return null; }
            finally { parcel.recycle(); }
            if (input.getClipData() != null || input.getSelector() != null || (input.getFlags() & GRANTS) != 0
                    || !scalarExtras(input.getExtras()) || "file".equalsIgnoreCase(input.getScheme())) return null;
            Intent safe = new Intent(input);
            // No provider-controlled task/forward-result flags, bounds, identifiers or grants survive.
            safe.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); safe.setSourceBounds(null);
            if (Build.VERSION.SDK_INT >= 29) safe.setIdentifier(null);
            ComponentName component = safe.getComponent();
            if (component == null) {
                ResolveInfo resolved = context.getPackageManager().resolveActivity(safe, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null || !allowed(context, resolved.activityInfo)) return null;
                component = new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
                safe.setComponent(component);
            }
            if (expectedPackage != null && !expectedPackage.equals(component.getPackageName())) return null;
            if (safe.getPackage() != null && !safe.getPackage().equals(component.getPackageName())) return null;
            safe.setPackage(component.getPackageName());
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            if (!allowed(context, info) || context.getPackageManager().resolveActivity(safe, 0) == null) return null;
            String uri = safe.toUri(Intent.URI_INTENT_SCHEME);
            if (uri.length() > MAX_URI) return null;
            return safe;
        } catch (Exception invalid) { return null; }
    }
    static boolean saveTarget(Context context, int event, Intent input, String label, String expectedPackage) {
        if (!gesture(event) || !validLabel(label)) return false;
        Intent safe = validated(context, input, expectedPackage);
        if (safe == null) return false;
        String uri = safe.toUri(Intent.URI_INTENT_SCHEME);
        // Make persistence lossless for the supported primitive-extra contract.
        try {
            Intent roundTrip = validated(context, Intent.parseUri(uri, Intent.URI_INTENT_SCHEME | Intent.URI_ALLOW_UNSAFE), expectedPackage);
            if (roundTrip == null || !uri.equals(roundTrip.toUri(Intent.URI_INTENT_SCHEME))) return false;
        } catch (Exception invalid) { return false; }
        OmiSettingsActivity.preferences(context).edit().putString(targetKey(event), uri).putString(labelKey(event), label.trim()).apply();
        cancelNotification(context, event);
        return true;
    }
    static Intent target(Context context, int event) {
        if (!gesture(event)) return null;
        String uri = string(OmiSettingsActivity.preferences(context), targetKey(event), "");
        if (uri.isEmpty() || uri.length() > MAX_URI || !validLabel(string(OmiSettingsActivity.preferences(context), labelKey(event), ""))) return null;
        try {
            Intent parsed = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME | Intent.URI_ALLOW_UNSAFE);
            // Stored mappings are explicit; never resolve a newly installed default from a corrupt URI.
            if (parsed.getComponent() == null) return null;
            return validated(context, parsed, parsed.getComponent().getPackageName());
        } catch (Exception invalid) { return null; }
    }
    static boolean acceptProviderResult(Context context, int event, ComponentName provider, int resultCode, Intent data) {
        if (!gesture(event) || resultCode != Activity.RESULT_OK || data == null || !providerAvailable(context, provider)) return false;
        try {
            Object value = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
            if (!(value instanceof Intent)) return false;
            return saveTarget(context, event, (Intent)value, data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME), provider.getPackageName());
        } catch (RuntimeException invalid) { return false; }
    }
    public static boolean isMediaAction(String action) {
        return "media_play_pause".equals(action) || "media_pause".equals(action);
    }
    /** Native media key pair: no activity, notification tap, audio focus or capture change.
     * Android chooses the media receiver; dispatch is a request, not playback acknowledgement. */
    public static String dispatchMedia(Context context, int event) {
        String action = action(context, event);
        if (!isMediaAction(action)) return "No music action selected";
        AudioManager audio = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return "Music control unavailable";
        int key = "media_pause".equals(action) ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        long now = SystemClock.uptimeMillis();
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
            return (key == KeyEvent.KEYCODE_MEDIA_PAUSE ? "Pause music" : "Play / pause music")
                + " requested — Android routes to the current media player";
        } catch (RuntimeException unavailable) { return "Music control could not be sent"; }
    }
    public static void onResume(Activity activity) { synchronized (VISIBLE_LOCK) { visible = new WeakReference<>(activity); } }
    public static void onPause(Activity activity) { synchronized (VISIBLE_LOCK) { if (visible.get() == activity) visible.clear(); } }
    static boolean visiblyResumed(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return false;
        KeyguardManager lock = activity.getSystemService(KeyguardManager.class);
        return lock == null || !lock.isKeyguardLocked();
    }
    /** Foreground launch only from a resumed, focused Activity; otherwise a direct tap-to-run intent. */
    public static String launchShortcut(Context context, int event) {
        if (!gesture(event)) return "Reserved button gesture — no phone action";
        Intent target = target(context, event);
        if (target == null) return "Phone shortcut unavailable — choose an exported app activity in Omi settings";
        synchronized (VISIBLE_LOCK) {
            Activity activity = visible.get();
            if (visiblyResumed(activity)) {
                try {
                    activity.startActivity(target);
                    cancelNotification(context, event);
                    return "Phone shortcut launch requested";
                } catch (RuntimeException unavailable) {
                    return "Phone shortcut could not open — choose it again in Omi settings";
                }
            }
        }
        return notifyShortcut(context, event, target);
    }
    private static String notifyShortcut(Context context, int event, Intent target) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Phone shortcut not launched — allow notifications, then press again with the app open or tap its notification";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return "Phone shortcut not launched — notifications are disabled; open the app and press again";
        try {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Omi phone shortcuts", NotificationManager.IMPORTANCE_DEFAULT));
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return "Phone shortcut not launched — enable the Omi phone shortcuts notification channel";
            PendingIntent tap = PendingIntent.getActivity(context, notificationId(event), target, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle(event == ButtonEvent.SINGLE ? "Omi single press · Tap to run" : "Omi double press · Tap to run")
                .setContentText(targetLabel(context, event)).setContentIntent(tap).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).build();
            manager.notify(notificationId(event), notification);
            return "Phone shortcut ready — tap the notification to run it (not auto-launched)";
        } catch (RuntimeException unavailable) {
            return "Phone shortcut not launched — notification unavailable; open the app and press again";
        }
    }
    static int notificationId(int event) { return 8200 + event; }
    private static void cancelNotification(Context context, int event) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(notificationId(event));
    }
}
