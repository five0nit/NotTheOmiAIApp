package app.nottheomi.ai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Synthetic emulator only. Exercises actual Activity, widgets, SQLite and Keystore. */
@SuppressWarnings("deprecation")
public final class LibraryActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private MainActivity activity;
    private Recordings store;
    private final List<String> owned = new ArrayList<>();
    private LibraryBrowser isolated;
    private CountDownLatch release;
    public LibraryActivityTest() { super(MainActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Dedicated emulator required", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.active); assertFalse(OmiCaptureService.active);
        OmiSettingsActivity.preferences(getInstrumentation().getTargetContext()).edit().clear().putString("source", "omi").commit();
        store = Recordings.get(getInstrumentation().getTargetContext());
        activity = getActivity();
        await(() -> Boolean.TRUE.equals(field("ready")));
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (release != null) release.countDown();
            main(() -> { if (isolated != null) isolated.close(); activity.finish(); });
            getInstrumentation().waitForIdleSync();
            for (String id : owned) { store.finish(id, "saved"); store.delete(id); }
        } finally { super.tearDown(); }
    }

    public void testCalendarMarksDaysAndFiltersEmptyDayWithoutCapture() throws Exception {
        String today = saved("Synthetic today", "Today words", LocalDate.now());
        LocalDate previous = LocalDate.now().minusDays(1);
        String yesterday = saved("Synthetic yesterday", "Earlier words", previous);
        openLibrary(); await(() -> tag("library.recording." + today) != null);
        assertNull(tag("library.recording." + yesterday));
        main(() -> assertTrue(tag("library.day." + LocalDate.now()).getContentDescription().toString().contains("1 saved recording")));
        click("library.day." + previous); await(() -> tag("library.recording." + yesterday) != null);
        assertNull(tag("library.recording." + today));
        LocalDate empty = previous.minusDays(1);
        click("library.day." + empty); await(() -> text("library.notice").contains("No recordings on this day"));
        click("library.allDates"); await(() -> rowCount() == 2);
        click("library.today"); await(() -> rowCount() == 1 && tag("library.recording." + today) != null);
        assertFalse(CaptureService.active); assertFalse(OmiCaptureService.active);
    }

    public void testTwelveRowPagesRetainDateAndScrollThroughDetailAndBack() throws Exception {
        for (int i = 0; i < 13; i++) saved("Synthetic page " + i, "Bounded page excerpt " + i, LocalDate.now());
        openLibrary(); await(() -> rowCount() == 12);
        assertTrue(text("library.notice").contains("page 1 of 2"));
        click("library.nextPage"); await(() -> rowCount() == 1 && text("library.notice").contains("page 2 of 2"));
        final int[] scrollBefore = {0};
        main(() -> { ScrollView scroll = scroll(); scroll.scrollTo(0, 200); scrollBefore[0] = scroll.getScrollY();
            assertTrue(scrollBefore[0] > 0); ((ViewGroup)tag("library.rows")).getChildAt(0).performClick(); });
        await(() -> field("detailTranscript") != null);
        main(() -> activity.onBackPressed());
        await(() -> rowCount() == 1 && text("library.notice").contains("page 2 of 2") && scroll().getScrollY() == scrollBefore[0]);
        assertEquals(LocalDate.now(), ((LibraryBrowser.State)field("libraryState")).selectedDay);
        click("library.previousPage"); await(() -> rowCount() == 12);
    }

    public void testFullTextSearchFindsOmittedEarlyChunkAndScopeIsExplicit() throws Exception {
        String id = saved("Synthetic long recording", "buriedneedle only in the first chunk", LocalDate.now(), true);
        saved("Synthetic yesterday", "yesterdayneedle", LocalDate.now().minusDays(1));
        openLibrary(); await(() -> rowCount() == 1);
        main(() -> ((EditText)tag("library.search")).setText("buriedneedle"));
        await(() -> rowCount() == 1 && tag("library.recording." + id) != null && text("library.notice").contains("matching your search"));
        main(() -> ((EditText)tag("library.search")).setText("yesterdayneedle"));
        await(() -> text("library.notice").startsWith("No matches"));
        click("library.allDates"); await(() -> rowCount() == 1 && text("library.notice").contains("matching your search"));
        main(() -> { ((EditText)tag("library.search")).setText("nonexistent"); ((EditText)tag("library.search")).setText(""); });
        await(() -> rowCount() == 2 && !text("library.notice").contains("matching your search"));
    }

    public void testStateBundlePauseResumeAndRefinementTicksDoNotResetNavigation() throws Exception {
        saved("Synthetic remembered", "Remembered excerpt", LocalDate.now());
        openLibrary(); await(() -> rowCount() == 1);
        click("library.allDates"); await(() -> text("library.scope").startsWith("All dates"));
        main(() -> ((EditText)tag("library.search")).setText("Remembered"));
        await(() -> rowCount() == 1 && text("library.notice").contains("matching your search"));
        LibraryBrowser.State original = (LibraryBrowser.State)field("libraryState");
        Bundle bundle = original.save();
        LibraryBrowser.State restored = LibraryBrowser.State.restore(bundle);
        assertEquals(original.selectedDay, restored.selectedDay); assertEquals(original.visibleMonth, restored.visibleMonth);
        assertEquals(original.query, restored.query); assertTrue(restored.allDates); assertEquals(original.page, restored.page);
        call("onPause"); call("onResume"); await(() -> rowCount() == 1 && text("library.notice").contains("matching your search"));
        main(() -> { for (int i = 0; i < 10; i++) ((LibraryBrowser)field("libraryBrowser")).offerRefresh(); });
        assertEquals("Remembered", ((LibraryBrowser.State)field("libraryState")).query);
        assertEquals(1, rowCount());
        Bundle invalid = new Bundle(); invalid.putString("day", "invalid"); invalid.putString("month", "bad"); invalid.putInt("page", -5);
        LibraryBrowser.State safe = LibraryBrowser.State.restore(invalid);
        assertEquals(LocalDate.now(), safe.selectedDay); assertEquals(YearMonth.from(safe.selectedDay), safe.visibleMonth); assertEquals(0, safe.page);
    }

    public void testPausedRefinementReadIsRetriedForAlreadyRenderedDetail() throws Exception {
        String id = store.create().id; owned.add(id);
        store.appendText(id, "Synthetic original draft");
        main(() -> {
            Method detail = MainActivity.class.getDeclaredMethod("detail", String.class);
            detail.setAccessible(true); detail.invoke(activity, id);
        });
        await(() -> field("detailTranscript") != null);
        main(() -> invoke("refreshRefinement"));
        ExecutorService io = (ExecutorService) field("io");
        io.submit(() -> {}).get(30, TimeUnit.SECONDS);
        getInstrumentation().waitForIdleSync();
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        io.execute(() -> {
            entered.countDown();
            try { release.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(15, TimeUnit.SECONDS));
        main(() -> {
            RefinementJobService.revision++;
            invoke("refreshRefinement"); // queued behind the blocked IO worker
            activity.onPause();
        });
        store.appendText(id, "Synthetic completed transcript");
        store.finish(id, "saved");
        release.countDown();
        io.submit(() -> {}).get(30, TimeUnit.SECONDS);
        getInstrumentation().waitForIdleSync(); // old callback is discarded while paused
        main(() -> assertFalse(((TextView) field("detailTranscript")).getText().toString().contains("completed transcript")));
        call("onResume");
        await(() -> ((TextView) field("detailTranscript")).getText().toString().contains("completed transcript"));
    }

    public void testPendingRestoredDetailSurvivesPauseAndSaveBeforeRecoveryCompletes() throws Exception {
        String id = saved("Synthetic restoration", "Restored transcript", LocalDate.now());
        Bundle state = new Bundle();
        main(() -> {
            invoke("draw");
            setField("ready", false); setField("pendingRestoredDetail", id);
            activity.onPause();
            activity.onSaveInstanceState(state);
            assertEquals(id, state.getString("selectedId"));
            invoke("libraryReady"); // delayed recovery completes while paused
            assertEquals(id, field("selectedId"));
            assertNull(field("pendingRestoredDetail"));
            assertNull(field("detailTranscript"));
        });
        call("onResume");
        await(() -> field("detailTranscript") != null);
        assertEquals(id, field("selectedId"));
    }

    public void testExplicitNavigationDiscardsPendingRestoredDetail() throws Exception {
        String id = saved("Synthetic canceled restoration", "Do not reopen", LocalDate.now());
        main(() -> {
            setField("ready", false); setField("pendingRestoredDetail", id);
            setField("library", true); invoke("draw");
            assertNull(field("pendingRestoredDetail"));
            invoke("libraryReady");
            assertNull(field("selectedId"));
        });
        await(() -> tag("library.calendar") != null || tag("library.rows") != null);
        assertNull(field("selectedId"));
    }

    public void testDefaultBrowseNeverCallsFullReadAndLateCatalogCannotOverwriteDay() throws Exception {
        saved("Synthetic catalog", "Only today", LocalDate.now());
        Recordings.LibraryCatalog catalog = store.libraryCatalog(() -> false);
        AtomicInteger calls = new AtomicInteger(), fullReads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), returned = new CountDownLatch(1);
        release = new CountDownLatch(1);
        LibraryBrowser.Source source = new LibraryBrowser.Source() {
            public Recordings.LibraryCatalog catalog(BooleanSupplier cancelled) throws Exception {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    // Simulate an old platform IO call that does not respond to interruption.
                    while (release.getCount() > 0) try { release.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                    returned.countDown();
                }
                return catalog;
            }
            public Recordings.Session preview(String id) throws Exception { return store.libraryPreview(id); }
            public Recordings.Session find(String id) throws Exception { fullReads.incrementAndGet(); return store.find(id); }
        };
        main(() -> {
            LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(activity); scroll.addView(content); activity.setContentView(scroll);
            LibraryBrowser.State state = new LibraryBrowser.State();
            isolated = new LibraryBrowser(activity, content, scroll, state, id -> {}, source); isolated.start();
        });
        assertTrue(entered.await(15, TimeUnit.SECONDS));
        click("library.day." + LocalDate.now().minusDays(1));
        await(() -> text("library.notice").contains("No recordings on this day"));
        release.countDown(); assertTrue(returned.await(15, TimeUnit.SECONDS));
        getInstrumentation().waitForIdleSync();
        assertTrue(text("library.notice").contains("No recordings on this day")); assertEquals(0, rowCount()); assertEquals(0, fullReads.get());
        click("library.today"); await(() -> rowCount() == 1); assertEquals(0, fullReads.get());
        main(() -> isolated.pause()); main(() -> isolated.start()); await(() -> rowCount() == 1);
    }

    public void testSyntheticCalendarScreenshotAndMonthNavigation() throws Exception {
        saved("Design review", "Synthetic fixture: agreed actions and next steps for the prototype.", LocalDate.now());
        saved("Morning notes", "Synthetic fixture: calendar makes older recordings easy to find.", LocalDate.now().minusDays(2));
        openLibrary(); await(() -> rowCount() == 1);
        LibraryBrowser.State state = (LibraryBrowser.State)field("libraryState"); LocalDate selected = state.selectedDay;
        click("library.previousMonth"); assertEquals(YearMonth.from(selected).minusMonths(1), state.visibleMonth); assertEquals(selected, state.selectedDay);
        click("library.nextMonth");
        main(() -> {
            View root = activity.getWindow().getDecorView();
            Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "library-synthetic.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally { image.recycle(); }
        });
        assertTrue(new File(activity.getFilesDir(), "library-synthetic.png").length() > 0);
    }

    private String saved(String title, String transcript, LocalDate day) throws Exception { return saved(title, transcript, day, false); }
    private String saved(String title, String transcript, LocalDate day, boolean extraChunks) throws Exception {
        String id = store.create().id; owned.add(id); store.rename(id, title); store.appendText(id, transcript);
        if (extraChunks) for (int i = 0; i < 5; i++) store.appendText(id, "Later synthetic line " + i);
        store.finish(id, "saved");
        Field lock = Recordings.class.getDeclaredField("lock"); lock.setAccessible(true);
        Method load = Recordings.class.getDeclaredMethod("loadMetadata", String.class, boolean.class); load.setAccessible(true);
        synchronized (lock.get(store)) {
            Object metadata = load.invoke(store, id, false);
            Field created = metadata.getClass().getDeclaredField("createdAt"); created.setAccessible(true);
            created.setLong(metadata, day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            Method save = Recordings.class.getDeclaredMethod("saveMetadata", metadata.getClass(), boolean.class); save.setAccessible(true); save.invoke(store, metadata, false);
        }
        return id;
    }
    private void openLibrary() throws Exception { main(() -> { Field f = MainActivity.class.getDeclaredField("library"); f.setAccessible(true); f.setBoolean(activity, true); invoke("draw"); }); }
    private Object field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void setField(String name, Object value) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
    private void invoke(String name) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity); }
    private void call(String name) throws Exception { main(() -> invoke(name)); }
    private View tag(String value) { return activity.getWindow().getDecorView().findViewWithTag(value); }
    private String text(String value) { View view = tag(value); return view instanceof TextView ? ((TextView)view).getText().toString() : ""; }
    private int rowCount() { View view = tag("library.rows"); return view instanceof ViewGroup ? ((ViewGroup)view).getChildCount() : -1; }
    private ScrollView scroll() throws Exception { return (ScrollView)((View)field("content")).getParent(); }
    private void click(String value) throws Exception { main(() -> { assertNotNull(value, tag(value)); assertTrue(tag(value).performClick()); }); }
    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }
    private void main(Checked work) throws Exception {
        Throwable[] error = {null}; getInstrumentation().runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void await(Condition condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { boolean[] ok = {false}; main(() -> ok[0] = condition.get()); if (ok[0]) return; Thread.sleep(60); }
        fail("Library condition not reached: " + text("library.notice"));
    }
}
