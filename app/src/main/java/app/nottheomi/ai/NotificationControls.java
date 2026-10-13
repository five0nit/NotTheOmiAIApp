package app.nottheomi.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/** Optional settings controls only: no access grants, capture, BLE or notification-content reads. */
public final class NotificationControls {
    private static final int INK = 0xff191c18, MUTED = 0xff646a61;
    private static final String[] PATTERNS = HapticPatterns.ids();
    private static final String[] PATTERN_LABELS = HapticPatterns.labels();
    private static final String[] OVERRIDE_LABELS = {"Use default pattern", "Short pulse", "Double pulse", "Triple pulse", "Long pulse"};
    private final Activity activity;
    private final Choice mode, defaultPattern;
    private final TextView summary;
    private final Button choose;
    private AlertDialog appsDialog;
    private TextView dialogSummary;
    private final List<AppRow> rows = new ArrayList<>();
    private boolean binding;

    public static NotificationControls attach(Activity activity, LinearLayout parent) {
        return new NotificationControls(activity, parent);
    }

    public NotificationControls(Activity activity, LinearLayout parent) {
        this.activity = activity;
        label(parent, "Apps & vibration patterns", 18, INK);
        label(parent, "Choose which apps can alert your Omi. Changes cancel queued vibrations; they never connect, start recording or grant notification access.", 13, MUTED);
        mode = choice(parent, "Notification apps", "omi.notifications.mode",
            new String[]{"All apps", "Selected apps only"}, position -> {
                NotificationVibrations.setMode(activity, position == 0
                    ? NotificationVibrations.MODE_ALL : NotificationVibrations.MODE_SELECTED);
                refresh();
            });
        summary = label(parent, "", 14, INK);
        summary.setTag("omi.notifications.selection.summary");
        defaultPattern = choice(parent, "Default vibration pattern", "omi.notifications.pattern.default",
            PATTERN_LABELS, position -> {
                NotificationVibrations.setDefaultPattern(activity, PATTERNS[position]);
                refresh();
            });
        choose = new Button(activity);
        choose.setTag("omi.notifications.apps");
        choose.setAllCaps(false);
        choose.setTextColor(INK);
        choose.setMinHeight(dp(50));
        choose.setOnClickListener(view -> showApps());
        parent.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        label(parent, "Patterns use your Omi motor, not the phone. Short, double and triple use one, two or three short pulses; long uses one longer pulse. Timing depends on firmware and BLE. Silent, ongoing, own-app and Do Not Disturb notifications are still skipped.", 13, MUTED);
        refresh();
    }

    /** Safe for the existing settings ticker: preserve widgets, focus and any open app chooser. */
    public void refresh() {
        binding = true;
        try {
            boolean all = NotificationVibrations.MODE_ALL.equals(NotificationVibrations.mode(activity));
            Set<String> selected = NotificationVibrations.selectedPackages(activity);
            mode.sync(all ? 0 : 1);
            defaultPattern.sync(patternIndex(NotificationVibrations.defaultPattern(activity)));
            String status = all ? "All eligible apps · " + selected.size() + " saved selections"
                : selected.isEmpty() ? "Selected apps only · 0 selected — no apps will vibrate"
                : "Selected apps only · " + selected.size() + " selected packages";
            summary.setText(status);
            choose.setText("Choose apps & patterns · " + selected.size() + " selected");
            if (dialogSummary != null) dialogSummary.setText(status + "\n"
                + (all ? "All apps mode is active. Checkboxes are saved for Selected apps only mode."
                    : "Checked packages may alert; unchecked packages cannot. An empty selection blocks every app.")
                + "\nChanges save immediately. Per-app patterns also apply in All apps mode.");
            for (AppRow row : rows) {
                row.selected.setChecked(selected.contains(row.app.packageName));
                String override = NotificationVibrations.appPatternOverride(activity, row.app.packageName);
                row.pattern.sync(override == null ? 0 : patternIndex(override) + 1);
            }
        } finally { binding = false; }
    }

    /** Discover only launchable packages under the existing MAIN/LAUNCHER visibility query. */
    static List<App> launchableApps(Activity activity) {
        PackageManager manager = activity.getPackageManager();
        Map<String, App> packages = new HashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo resolved : manager.queryIntentActivities(launcher, 0)) {
            if (resolved.activityInfo == null || !resolved.activityInfo.enabled
                    || !resolved.activityInfo.exported || resolved.activityInfo.applicationInfo == null
                    || !resolved.activityInfo.applicationInfo.enabled) continue;
            String packageName = resolved.activityInfo.packageName;
            if (!NotificationVibrations.validPackageName(packageName) || activity.getPackageName().equals(packageName)) continue;
            CharSequence name = resolved.activityInfo.applicationInfo.loadLabel(manager);
            packages.put(packageName, new App(packageName, name == null ? packageName : name.toString(), true));
        }
        List<App> result = new ArrayList<>(packages.values());
        sortApps(result);
        return result;
    }

    private void showApps() {
        if (appsDialog != null && appsDialog.isShowing()) return;
        rows.clear();
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(8), dp(18), dp(12));
        dialogSummary = label(list, "", 13, MUTED);
        dialogSummary.setTag("omi.notifications.apps.summary");
        List<App> apps;
        boolean unavailable = false;
        try { apps = launchableApps(activity); }
        catch (RuntimeException error) { apps = new ArrayList<>(); unavailable = true; }
        // Keep saved selections manageable even after uninstall / launcher visibility changes.
        Map<String, App> visible = new HashMap<>();
        for (App app : apps) visible.put(app.packageName, app);
        for (String name : NotificationVibrations.selectedPackages(activity)) {
            if (!visible.containsKey(name)) apps.add(new App(name, "Saved package · not currently launchable", false));
        }
        sortApps(apps);
        if (unavailable) label(list, "Installed app list is unavailable. Saved packages are shown below; no permissions were changed.", 13, MUTED);
        else if (apps.isEmpty()) label(list, "No other launchable apps are visible. Selected apps mode with no selections blocks all notifications.", 13, MUTED);
        for (App app : apps) {
            LinearLayout section = new LinearLayout(activity);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setPadding(0, dp(10), 0, dp(8));
            list.addView(section, new LinearLayout.LayoutParams(-1, -2));
            CheckBox selected = new CheckBox(activity);
            selected.setText(app.label + "\n" + app.packageName);
            selected.setTextColor(INK);
            selected.setMinHeight(dp(52));
            selected.setTag("omi.notifications.app." + app.packageName);
            selected.setContentDescription("Include " + app.label + " (" + app.packageName + ") in selected apps");
            selected.setSaveEnabled(false);
            section.addView(selected, new LinearLayout.LayoutParams(-1, -2));
            Choice pattern = choice(section, "Pattern for " + app.label,
                "omi.notifications.pattern." + app.packageName, OVERRIDE_LABELS, position -> {
                    NotificationVibrations.setAppPattern(activity, app.packageName,
                        position == 0 ? null : PATTERNS[position - 1]);
                    refresh();
                });
            selected.setOnCheckedChangeListener((button, checked) -> {
                if (binding) return;
                NotificationVibrations.setAppSelected(activity, app.packageName, checked);
                refresh();
            });
            rows.add(new AppRow(app, selected, pattern));
        }
        label(list, "Only installed launchable apps are discovered. Previously selected packages remain listed if unavailable; they do not produce notifications while uninstalled. This list never inspects notification text.", 12, MUTED);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        appsDialog = new AlertDialog.Builder(activity).setTitle("Notification apps & patterns")
            .setView(scroll).setPositiveButton("Done", null).create();
        appsDialog.setOnDismissListener(dialog -> { rows.clear(); dialogSummary = null; appsDialog = null; });
        refresh();
        appsDialog.show();
    }

    static final class App {
        final String packageName, label;
        final boolean launchable;
        App(String packageName, String label, boolean launchable) {
            this.packageName = packageName; this.label = label; this.launchable = launchable;
        }
    }

    private static final class AppRow {
        final App app;
        final CheckBox selected;
        final Choice pattern;
        AppRow(App app, CheckBox selected, Choice pattern) {
            this.app = app; this.selected = selected; this.pattern = pattern;
        }
    }

    private static void sortApps(List<App> apps) {
        apps.sort(Comparator.comparing((App app) -> app.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(app -> app.packageName));
    }

    private static int patternIndex(String pattern) {
        for (int i = 0; i < PATTERNS.length; i++) if (PATTERNS[i].equals(pattern)) return i;
        return 1;
    }

    private Choice choice(LinearLayout parent, String title, String tag, String[] labels, IntConsumer save) {
        TextView heading = label(parent, title, 14, INK);
        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());
        heading.setLabelFor(spinner.getId());
        spinner.setTag(tag);
        spinner.setContentDescription(title);
        spinner.setSaveEnabled(false);
        spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
        Choice choice = new Choice(spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> view, View selected, int position, long id) {
                if (binding || spinner.getSelectedItemPosition() != position || choice.boundPosition == position) return;
                choice.boundPosition = position;
                save.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> view) {}
        });
        return choice;
    }

    private static final class Choice {
        final Spinner spinner;
        int boundPosition = -1;
        Choice(Spinner spinner) { this.spinner = spinner; }
        void sync(int position) {
            boundPosition = position;
            if (spinner.getSelectedItemPosition() != position) spinner.setSelection(position, false);
        }
    }

    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView label = new TextView(activity);
        label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setPadding(0, dp(8), 0, dp(4));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
