package app.nottheomi.ai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Calendar and bounded pages for the private Library. No recording or Activity IO queue work. */
final class LibraryBrowser implements AutoCloseable {
    static final int PAGE_SIZE = 12;
    private static final int INK = 0xff191c18, MUTED = 0xff646a61, ACCENT = 0xffbd432b;
    private static final int SOFT = 0xffe5e8df;

    /** Small, payload-free navigation model survives detail, tab changes and recreation. */
    static final class State {
        LocalDate selectedDay = LocalDate.now();
        YearMonth visibleMonth = YearMonth.from(selectedDay);
        boolean allDates;
        String query = "";
        int page, scrollY;

        static State restore(Bundle bundle) {
            State state = new State();
            if (bundle == null) return state;
            try { state.selectedDay = LocalDate.parse(bundle.getString("day", state.selectedDay.toString())); }
            catch (RuntimeException ignored) { /* Invalid old state falls back to today. */ }
            try { state.visibleMonth = YearMonth.parse(bundle.getString("month", YearMonth.from(state.selectedDay).toString())); }
            catch (RuntimeException ignored) { state.visibleMonth = YearMonth.from(state.selectedDay); }
            state.allDates = bundle.getBoolean("allDates");
            state.query = LibraryDates.bounded(bundle.getString("query", ""), 256);
            state.page = Math.max(0, bundle.getInt("page"));
            state.scrollY = Math.max(0, bundle.getInt("scrollY"));
            return state;
        }

        Bundle save() {
            Bundle bundle = new Bundle();
            bundle.putString("day", selectedDay.toString());
            bundle.putString("month", visibleMonth.toString());
            bundle.putBoolean("allDates", allDates);
            bundle.putString("query", query);
            bundle.putInt("page", page);
            bundle.putInt("scrollY", scrollY);
            return bundle;
        }
    }

    /** Also permits deterministic synthetic race tests without reading a private database. */
    interface Source {
        Recordings.LibraryCatalog catalog(BooleanSupplier cancelled) throws Exception;
        Recordings.Session preview(String id) throws Exception;
        Recordings.Session find(String id) throws Exception;
    }

    private final Context context;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final State state;
    private final Source source;
    private final Consumer<String> open;
    private final Handler main = new Handler(Looper.getMainLooper());
    // A canceled full-text read can finish independently; it never queues ahead of detail.
    // Purge removes superseded queued requests, and every loop checks cancellation.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private final LinearLayout calendar, rows, pager;
    private final TextView monthLabel, scope, notice, damage, changes;
    private final EditText search;
    private final Button allDates;
    private volatile int generation;
    private volatile boolean active, closed;
    private Future<?> pending;
    private Runnable debounce;
    private Recordings.LibraryCatalog catalog;
    private Matches cachedMatches;
    private Map<LocalDate, Integer> dayCounts = new HashMap<>();
    private ZoneId zone = ZoneId.systemDefault();
    private boolean loaded, loading;

    LibraryBrowser(Context context, LinearLayout content, ScrollView scroll, State state,
                   Consumer<String> open) {
        this(context, content, scroll, state, open, new Source() {
            public Recordings.LibraryCatalog catalog(BooleanSupplier cancelled) throws Exception {
                return Recordings.get(context).libraryCatalog(cancelled);
            }
            public Recordings.Session preview(String id) throws Exception {
                return Recordings.get(context).libraryPreview(id);
            }
            public Recordings.Session find(String id) throws Exception {
                return Recordings.get(context).find(id);
            }
        });
    }

    LibraryBrowser(Context context, LinearLayout content, ScrollView scroll, State state,
                   Consumer<String> open, Source source) {
        this.context = context;
        this.content = content;
        this.scroll = scroll;
        this.state = state;
        this.open = open;
        this.source = source;
        worker.allowCoreThreadTimeOut(true);
        content.setFocusableInTouchMode(true); // Do not open the keyboard just to browse dates.
        content.addView(text("Your library", 30, INK, true));
        content.addView(text("Encrypted on this phone · no sync · kept until you delete", 12, MUTED, false));
        LinearLayout monthBar = new LinearLayout(context);
        Button previous = button("‹", () -> moveMonth(-1));
        previous.setContentDescription("Previous month"); previous.setTag("library.previousMonth");
        Button next = button("›", () -> moveMonth(1));
        next.setContentDescription("Next month"); next.setTag("library.nextMonth");
        monthBar.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        monthLabel = text("", 18, INK, true); monthLabel.setGravity(Gravity.CENTER);
        monthBar.addView(monthLabel, new LinearLayout.LayoutParams(0, -1, 1));
        monthBar.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        add(monthBar, 10);
        calendar = column(); calendar.setTag("library.calendar"); add(calendar, 4);
        LinearLayout dateActions = new LinearLayout(context);
        Button today = button("Today", () -> selectDay(LocalDate.now(ZoneId.systemDefault())));
        today.setTag("library.today"); half(dateActions, today);
        allDates = button("All dates", () -> { state.allDates = true; changedScope(); });
        allDates.setTag("library.allDates"); half(dateActions, allDates); add(dateActions, 8);
        scope = text("", 15, INK, true); scope.setTag("library.scope"); add(scope, 10);
        search = new EditText(context); search.setTag("library.search");
        search.setSingleLine(true); search.setTextSize(15); search.setTextColor(INK);
        search.setBackground(box(SOFT)); search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        search.setSaveEnabled(false);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        search.setText(state.query); add(search, 4);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                state.query = s.toString(); state.page = 0; state.scrollY = 0;
                cancel(); // Fence immediately, not after the debounce interval.
                showLoading();
                debounce = () -> { debounce = null; request(); };
                if (active) main.postDelayed(debounce, 300);
            }
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            cancel(); request(); return true;
        });
        changes = text("", 12, ACCENT, false); changes.setVisibility(View.GONE); add(changes, 4);
        Button refresh = button("Refresh saved recordings", this::refresh);
        refresh.setTag("library.refresh"); add(refresh, 4);
        notice = text("Opening private library…", 14, MUTED, false);
        notice.setTag("library.notice"); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(notice, 8);
        damage = text("", 12, ACCENT, false); damage.setVisibility(View.GONE); add(damage, 2);
        rows = column(); rows.setTag("library.rows"); add(rows, 4);
        pager = new LinearLayout(context); pager.setTag("library.pager"); add(pager, 8);
        renderCalendar(); renderScope();
    }

    /** Start once after recovery; duplicate ready/onResume callbacks do not restart work. */
    void start() {
        if (closed || active) return;
        active = true;
        ZoneId current = ZoneId.systemDefault();
        if (!current.equals(zone)) { zone = current; dayCounts.clear(); }
        catalog = null; cachedMatches = null; loaded = false;
        request();
    }

    void pause() {
        if (closed) return;
        rememberScroll(); active = false; cancel();
    }

    void rememberScroll() {
        // During loading the short layout can clamp the real scroll position to zero.
        if (loaded && !loading) state.scrollY = scroll.getScrollY();
    }

    /** Refinement ticks never cancel/restart a catalog/search or create a refresh loop. */
    void offerRefresh() {
        if (closed) return;
        changes.setText("Saved transcripts changed. Refresh when you’re ready.");
        changes.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        rememberScroll(); catalog = null; cachedMatches = null;
        changes.setVisibility(View.GONE); request();
    }

    private void selectDay(LocalDate day) {
        state.selectedDay = day; state.visibleMonth = YearMonth.from(day); state.allDates = false;
        changedScope();
    }

    private void changedScope() {
        state.page = 0; state.scrollY = 0;
        renderCalendar(); renderScope(); request();
    }

    private void moveMonth(int delta) {
        try { state.visibleMonth = state.visibleMonth.plusMonths(delta); }
        catch (java.time.DateTimeException ignored) { return; }
        renderCalendar(); // Browsing another month does not silently change the selected day.
    }

    private void renderScope() {
        String label = state.allDates ? "All dates" : dayLabel(state.selectedDay);
        scope.setText(label + " · " + zone.getId());
        search.setHint(state.allDates ? "Search all titles & transcripts" : "Search this day’s titles & transcripts");
        search.setContentDescription("Search titles and full transcripts: " + label);
        allDates.setSelected(state.allDates);
        allDates.setText(state.allDates ? "All dates ✓" : "All dates");
    }

    private void renderCalendar() {
        monthLabel.setText(state.visibleMonth.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.getDefault())));
        calendar.removeAllViews();
        DayOfWeek first = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
        LinearLayout labels = new LinearLayout(context);
        for (int i = 0; i < 7; i++) {
            DayOfWeek day = first.plus(i);
            TextView label = text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), 11, MUTED, true);
            label.setGravity(Gravity.CENTER); label.setContentDescription(day.getDisplayName(TextStyle.FULL, Locale.getDefault()));
            labels.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        calendar.addView(labels);
        LinearLayout week = null;
        List<LocalDate> cells = LibraryDates.monthCells(state.visibleMonth, first);
        for (int i = 0; i < cells.size(); i++) {
            if (i % 7 == 0) { week = new LinearLayout(context); calendar.addView(week); }
            LocalDate day = cells.get(i);
            if (day == null) { week.addView(new View(context), new LinearLayout.LayoutParams(0, dp(54), 1)); continue; }
            int count = dayCounts.getOrDefault(day, 0);
            boolean selected = !state.allDates && day.equals(state.selectedDay);
            Button cell = button(Integer.toString(day.getDayOfMonth()) + (count > 0 ? "\n• " + count : ""), () -> selectDay(day));
            cell.setTextSize(12); cell.setPadding(0, 0, 0, 0); cell.setMinWidth(0); cell.setMinimumWidth(0);
            cell.setTag("library.day." + day); cell.setSelected(selected);
            cell.setTextColor(selected ? Color.WHITE : INK);
            cell.setBackground(box(selected ? INK : count > 0 ? SOFT : Color.TRANSPARENT));
            cell.setContentDescription(dayLabel(day) + ", " + (catalog == null ? "recording count loading" :
                    count + (count == 1 ? " saved recording" : " saved recordings")) + (selected ? ", selected" : ""));
            week.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
    }

    private void cancel() {
        generation++;
        if (debounce != null) { main.removeCallbacks(debounce); debounce = null; }
        if (pending != null) { pending.cancel(true); pending = null; }
        worker.purge();
        loading = false;
    }

    private void showLoading() {
        loading = true; rows.removeAllViews(); pager.removeAllViews(); damage.setVisibility(View.GONE);
        notice.setText(state.query.trim().isEmpty() ? "Loading saved recordings…" : "Searching full transcripts in " +
                (state.allDates ? "all dates…" : "the selected day…"));
    }

    private void request() {
        if (!active || closed) return;
        cancel(); showLoading();
        final int token = generation;
        final LocalDate day = state.selectedDay;
        final boolean all = state.allDates;
        final String term = LibraryDates.foldSearch(state.query.trim());
        final int requestedPage = state.page;
        final ZoneId requestZone = zone;
        final Recordings.LibraryCatalog retained = catalog;
        final Matches previous = cachedMatches;
        final BooleanSupplier cancelled = () -> closed || !active || token != generation || Thread.currentThread().isInterrupted();
        pending = worker.submit(() -> {
            try {
                check(cancelled);
                Recordings.LibraryCatalog current = retained == null ? source.catalog(cancelled) : retained;
                check(cancelled);
                Map<LocalDate, Integer> counts = new HashMap<>();
                for (Recordings.LibraryEntry item : current.entries) {
                    check(cancelled);
                    LocalDate local = LibraryDates.localDay(item.createdAt, requestZone);
                    counts.put(local, counts.getOrDefault(local, 0) + 1);
                }
                Matches matches = previous != null && previous.matches(current, day, all, term, requestZone) ? previous :
                        findMatches(current, day, all, term, requestZone, cancelled);
                int page = LibraryDates.clampPage(requestedPage, matches.entries.size(), PAGE_SIZE);
                List<Row> result = new ArrayList<>();
                int unavailable = current.unavailableCount + matches.unavailable;
                int end = Math.min(matches.entries.size(), page * PAGE_SIZE + PAGE_SIZE);
                for (int i = page * PAGE_SIZE; i < end; i++) {
                    check(cancelled);
                    Recordings.LibraryEntry entry = matches.entries.get(i);
                    String excerpt;
                    try {
                        Recordings.Session preview = source.preview(entry.id);
                        check(cancelled);
                        if (preview == null) { unavailable++; excerpt = "Preview unavailable. Tap to try opening."; }
                        else if (preview.text.isEmpty()) excerpt = "No speech transcribed · saved audio available";
                        else excerpt = LibraryDates.bounded(preview.text, 320);
                    } catch (CancellationException e) { throw e; }
                    catch (Exception e) { check(cancelled); unavailable++; excerpt = "Preview unavailable. Tap to try opening."; }
                    result.add(new Row(entry, excerpt));
                }
                check(cancelled);
                final int unreadable = unavailable;
                main.post(() -> {
                    if (!valid(token)) return;
                    catalog = current; cachedMatches = matches; dayCounts = counts; state.page = page;
                    loading = false; loaded = true; pending = null;
                    renderCalendar(); renderScope(); renderRows(result, matches.entries.size(), unreadable);
                    final int restore = state.scrollY;
                    scroll.post(() -> { if (valid(token)) scroll.scrollTo(0, restore); });
                });
            } catch (CancellationException ignored) { /* Superseded screen/query; no stale error. */ }
            catch (Exception failure) {
                main.post(() -> {
                    if (!valid(token)) return;
                    loading = false; pending = null;
                    rows.removeAllViews(); pager.removeAllViews();
                    notice.setText("Could not read the encrypted library. Your files were not changed. Tap Refresh to retry.");
                });
            }
        });
    }

    private Matches findMatches(Recordings.LibraryCatalog current, LocalDate day, boolean all,
                                String term, ZoneId requestZone, BooleanSupplier cancelled) throws Exception {
        List<Recordings.LibraryEntry> result = new ArrayList<>();
        int unavailable = 0;
        for (Recordings.LibraryEntry entry : current.entries) {
            check(cancelled);
            if (!all && !LibraryDates.localDay(entry.createdAt, requestZone).equals(day)) continue;
            // Default/day browsing never materializes full transcripts. Only an explicit term does.
            if (!term.isEmpty() && !LibraryDates.foldSearch(entry.title).contains(term)) {
                try {
                    Recordings.Session session = source.find(entry.id);
                    check(cancelled);
                    if (session == null) { unavailable++; continue; }
                    if (!LibraryDates.foldSearch(session.text).contains(term)) continue;
                } catch (CancellationException e) { throw e; }
                catch (Exception e) { check(cancelled); unavailable++; continue; }
            }
            result.add(entry);
        }
        return new Matches(current, day, all, term, requestZone, result, unavailable);
    }

    private void renderRows(List<Row> result, int total, int unavailable) {
        rows.removeAllViews(); pager.removeAllViews();
        if (unavailable > 0) {
            damage.setText(unavailable + " saved " + (unavailable == 1 ? "recording could" : "recordings could") +
                    " not be read. Results may be incomplete; encrypted originals were kept.");
            damage.setVisibility(View.VISIBLE);
        } else damage.setVisibility(View.GONE);
        if (total == 0) {
            notice.setText(!state.query.trim().isEmpty() ? "No matches in " + (state.allDates ? "all dates." : "the selected day. Try All dates or a different word.") :
                    state.allDates ? "No saved recordings yet. Start from Home." : "No recordings on this day. Choose a marked date or All dates.");
            return;
        }
        notice.setText(total + (total == 1 ? " saved recording" : " saved recordings") +
                (state.query.trim().isEmpty() ? "" : " matching your search") + " · page " + (state.page + 1) +
                " of " + LibraryDates.pageCount(total, PAGE_SIZE));
        for (Row row : result) {
            Recordings.LibraryEntry entry = row.entry;
            LinearLayout card = column(); card.setBackground(box(Color.WHITE)); card.setPadding(dp(16), dp(12), dp(16), dp(12));
            String title = LibraryDates.bounded(entry.title, 160);
            TextView titleView = text(title, 18, INK, true); titleView.setMaxLines(2); titleView.setEllipsize(TextUtils.TruncateAt.END); card.addView(titleView);
            card.addView(text(java.time.Instant.ofEpochMilli(entry.createdAt).atZone(zone)
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) + " · " + duration(entry.durationMs), 12, MUTED, false));
            TextView excerpt = text(row.excerpt, 14, MUTED, false); excerpt.setMaxLines(3); excerpt.setEllipsize(TextUtils.TruncateAt.END); card.addView(excerpt);
            card.addView(text(LibraryDates.bounded(entry.status, 64), 11, ACCENT, true));
            card.setTag("library.recording." + entry.id); card.setFocusable(true);
            card.setContentDescription("Open recording " + title);
            card.setOnClickListener(v -> { rememberScroll(); open.accept(entry.id); });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.bottomMargin = dp(8); rows.addView(card, layout);
        }
        Button previous = button("‹ Previous", () -> page(-1)); previous.setTag("library.previousPage"); previous.setEnabled(state.page > 0);
        Button next = button("Next ›", () -> page(1)); next.setTag("library.nextPage"); next.setEnabled(state.page + 1 < LibraryDates.pageCount(total, PAGE_SIZE));
        half(pager, previous); half(pager, next);
    }

    private void page(int delta) { state.page += delta; state.scrollY = 0; request(); }
    private boolean valid(int token) { return !closed && active && generation == token; }
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException(); }
    @Override public void close() { if (closed) return; rememberScroll(); active = false; closed = true; cancel(); worker.shutdownNow(); }

    private static final class Row {
        final Recordings.LibraryEntry entry;
        final String excerpt;
        Row(Recordings.LibraryEntry entry, String excerpt) { this.entry = entry; this.excerpt = excerpt; }
    }
    private static final class Matches {
        final Recordings.LibraryCatalog catalog;
        final LocalDate day;
        final boolean all;
        final String term;
        final ZoneId zone;
        final List<Recordings.LibraryEntry> entries;
        final int unavailable;
        Matches(Recordings.LibraryCatalog catalog, LocalDate day, boolean all, String term, ZoneId zone,
                List<Recordings.LibraryEntry> entries, int unavailable) {
            this.catalog = catalog; this.day = day; this.all = all; this.term = term;
            this.zone = zone; this.entries = entries; this.unavailable = unavailable;
        }
        boolean matches(Recordings.LibraryCatalog catalog, LocalDate day, boolean all, String term, ZoneId zone) {
            return this.catalog == catalog && this.day.equals(day) && this.all == all && this.term.equals(term) && this.zone.equals(zone);
        }
    }

    private int dp(float value) { return (int) (context.getResources().getDisplayMetrics().density * value + .5f); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(context); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private GradientDrawable box(int color) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(10)); return result; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(context); result.setText(value); result.setTextSize(size); result.setTextColor(color);
        result.setPadding(0, dp(4), 0, dp(4));
        if (bold) result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return result;
    }
    private Button button(String label, Runnable click) {
        Button result = new Button(context); result.setText(label); result.setAllCaps(false); result.setTextSize(14); result.setTextColor(INK);
        result.setMinHeight(dp(48)); result.setMinimumHeight(dp(48)); result.setPadding(dp(6), dp(6), dp(6), dp(6));
        result.setBackground(box(SOFT)); result.setOnClickListener(v -> click.run()); return result;
    }
    private void add(View view, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); content.addView(view, p); }
    private void half(LinearLayout row, View view) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(2), 0, dp(2), 0); row.addView(view, p); }
    private static String dayLabel(LocalDate day) { return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)); }
    private static String duration(long millis) { long seconds = Math.max(0, millis / 1000); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
}
