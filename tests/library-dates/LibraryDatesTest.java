package app.nottheomi.ai;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Standalone host regressions: no Android, JUnit, clock or network required. */
public final class LibraryDatesTest {
    private static int assertions;
    private static int tests;

    private LibraryDatesTest() {}

    public static void main(String[] args) {
        run("New York spring DST", LibraryDatesTest::newYorkSpring);
        run("New York fall DST", LibraryDatesTest::newYorkFall);
        run("Sydney spring DST", LibraryDatesTest::sydneySpring);
        run("Sydney fall DST", LibraryDatesTest::sydneyFall);
        run("local midnight versus UTC and epoch", LibraryDatesTest::localMidnight);
        run("leap February and December year boundary", LibraryDatesTest::monthBoundaries);
        run("Sunday/Monday 28/35/42-cell weeks", LibraryDatesTest::weekStarts);
        run("calendar invariants for every week start", LibraryDatesTest::calendarInvariants);
        run("calendar range boundaries and null inputs", LibraryDatesTest::calendarEdges);
        run("page counts and zero-based clamping", LibraryDatesTest::pagination);
        run("overflow-safe huge counts", LibraryDatesTest::hugeCounts);
        run("invalid page sizes", LibraryDatesTest::invalidPageSizes);
        run("Unicode search compatibility", () -> {
            equal(LibraryDates.foldSearch("STRASSE"), LibraryDates.foldSearch("Straße"), "sharp-s");
            equal(LibraryDates.foldSearch("CAFÉ"), LibraryDates.foldSearch("cafe\u0301"), "NFC accents");
            equal(LibraryDates.foldSearch("Σ"), LibraryDates.foldSearch("ς"), "final sigma");
            equal("", LibraryDates.foldSearch(""), "empty query");
        });
        run("bounded text and ellipsis budget", LibraryDatesTest::boundedText);
        run("bounded emoji and surrogate boundaries", LibraryDatesTest::boundedEmoji);
        System.out.println("LibraryDatesTest: PASS (" + tests + " tests, "
                + assertions + " assertions; default zone=" + ZoneId.systemDefault() + ")");
    }

    private static void newYorkSpring() {
        day("2024-03-10T04:59:59.999Z", "America/New_York", "2024-03-09");
        day("2024-03-10T05:00:00Z", "America/New_York", "2024-03-10");
        day("2024-03-10T06:59:59.999Z", "America/New_York", "2024-03-10");
        day("2024-03-10T07:00:00Z", "America/New_York", "2024-03-10");
        // This local day lasts 23 hours, not a fixed 86,400,000 milliseconds.
        day("2024-03-11T03:59:59.999Z", "America/New_York", "2024-03-10");
        day("2024-03-11T04:00:00Z", "America/New_York", "2024-03-11");
    }

    private static void newYorkFall() {
        day("2024-11-03T03:59:59.999Z", "America/New_York", "2024-11-02");
        day("2024-11-03T04:00:00Z", "America/New_York", "2024-11-03");
        // Both occurrences of the repeated 01:30 belong to the same local date.
        day("2024-11-03T05:30:00Z", "America/New_York", "2024-11-03");
        day("2024-11-03T06:30:00Z", "America/New_York", "2024-11-03");
        day("2024-11-03T05:59:59.999Z", "America/New_York", "2024-11-03");
        day("2024-11-03T06:00:00Z", "America/New_York", "2024-11-03");
        // The following midnight is 25 hours after the preceding midnight.
        day("2024-11-04T04:59:59.999Z", "America/New_York", "2024-11-03");
        day("2024-11-04T05:00:00Z", "America/New_York", "2024-11-04");
    }

    private static void sydneySpring() {
        day("2024-10-05T13:59:59.999Z", "Australia/Sydney", "2024-10-05");
        day("2024-10-05T14:00:00Z", "Australia/Sydney", "2024-10-06");
        day("2024-10-05T15:59:59.999Z", "Australia/Sydney", "2024-10-06");
        day("2024-10-05T16:00:00Z", "Australia/Sydney", "2024-10-06");
        day("2024-10-06T12:59:59.999Z", "Australia/Sydney", "2024-10-06");
        day("2024-10-06T13:00:00Z", "Australia/Sydney", "2024-10-07");
    }

    private static void sydneyFall() {
        day("2024-04-06T12:59:59.999Z", "Australia/Sydney", "2024-04-06");
        day("2024-04-06T13:00:00Z", "Australia/Sydney", "2024-04-07");
        // The 02:30 hour repeats when Sydney leaves daylight saving time.
        day("2024-04-06T15:30:00Z", "Australia/Sydney", "2024-04-07");
        day("2024-04-06T16:30:00Z", "Australia/Sydney", "2024-04-07");
        day("2024-04-06T15:59:59.999Z", "Australia/Sydney", "2024-04-07");
        day("2024-04-06T16:00:00Z", "Australia/Sydney", "2024-04-07");
        day("2024-04-07T13:59:59.999Z", "Australia/Sydney", "2024-04-07");
        day("2024-04-07T14:00:00Z", "Australia/Sydney", "2024-04-08");
    }

    private static void localMidnight() {
        day("2024-01-01T00:00:00Z", "UTC", "2024-01-01");
        day("2024-01-01T00:00:00Z", "America/New_York", "2023-12-31");
        day("2024-12-31T13:00:00Z", "Australia/Sydney", "2025-01-01");
        day("2024-12-31T13:00:00Z", "UTC", "2024-12-31");
        day("2024-01-01T04:59:59.999Z", "America/New_York", "2023-12-31");
        day("2024-01-01T05:00:00Z", "America/New_York", "2024-01-01");
        day("2024-12-31T12:59:59.999Z", "Australia/Sydney", "2024-12-31");
        equal(LocalDate.of(1970, 1, 1), LibraryDates.localDay(0, ZoneOffset.UTC), "epoch");
        equal(LocalDate.of(1969, 12, 31), LibraryDates.localDay(-1, ZoneOffset.UTC), "pre-epoch");
        throwsType(NullPointerException.class, () -> LibraryDates.localDay(0, null), "null zone");
    }

    private static void monthBoundaries() {
        grid(YearMonth.of(2024, 2), DayOfWeek.MONDAY, 3, 35);
        grid(YearMonth.of(2024, 2), DayOfWeek.SUNDAY, 4, 35);
        List<LocalDate> leap = LibraryDates.monthCells(YearMonth.of(2024, 2), DayOfWeek.MONDAY);
        equal(LocalDate.of(2024, 2, 29), leap.get(31), "leap day present");
        equal(null, leap.get(32), "March is a placeholder");
        grid(YearMonth.of(2023, 12), DayOfWeek.MONDAY, 4, 35);
        grid(YearMonth.of(2023, 12), DayOfWeek.SUNDAY, 5, 42);
        grid(YearMonth.of(2024, 1), DayOfWeek.MONDAY, 0, 35);
        grid(YearMonth.of(2024, 1), DayOfWeek.SUNDAY, 1, 35);
        List<LocalDate> december = LibraryDates.monthCells(YearMonth.of(2023, 12), DayOfWeek.SUNDAY);
        equal(LocalDate.of(2023, 12, 31), december.get(35), "December last day");
        equal(null, december.get(36), "January is a placeholder");
    }

    private static void weekStarts() {
        grid(YearMonth.of(2021, 2), DayOfWeek.MONDAY, 0, 28);
        grid(YearMonth.of(2021, 2), DayOfWeek.SUNDAY, 1, 35);
        grid(YearMonth.of(2015, 2), DayOfWeek.SUNDAY, 0, 28);
        grid(YearMonth.of(2015, 2), DayOfWeek.MONDAY, 6, 35);
        grid(YearMonth.of(2024, 9), DayOfWeek.SUNDAY, 0, 35);
        grid(YearMonth.of(2024, 9), DayOfWeek.MONDAY, 6, 42);
        grid(YearMonth.of(2024, 3), DayOfWeek.SUNDAY, 5, 42);
        grid(YearMonth.of(2024, 3), DayOfWeek.MONDAY, 4, 35);
    }

    private static void calendarInvariants() {
        // Include common years, leap years, and both leap/non-leap century rules.
        for (int year : new int[] {1900, 2000, 2023, 2024, 2025, 2100}) {
            for (int month = 1; month <= 12; month++) {
                for (DayOfWeek firstDay : DayOfWeek.values()) {
                    verifyCalendar(YearMonth.of(year, month), firstDay);
                }
            }
        }
    }

    private static void calendarEdges() {
        // Placeholders must not require dates outside java.time's supported range.
        for (DayOfWeek firstDay : DayOfWeek.values()) {
            verifyCalendar(YearMonth.of(LocalDate.MIN.getYear(), 1), firstDay);
            verifyCalendar(YearMonth.of(LocalDate.MAX.getYear(), 12), firstDay);
        }
        throwsType(NullPointerException.class,
                () -> LibraryDates.monthCells(null, DayOfWeek.MONDAY), "null month");
        throwsType(NullPointerException.class,
                () -> LibraryDates.monthCells(YearMonth.of(2024, 1), null), "null first day");
    }

    private static void pagination() {
        equal(1, LibraryDates.pageCount(0, 10), "empty has one page");
        equal(1, LibraryDates.pageCount(-1, 10), "negative count treated as empty");
        equal(1, LibraryDates.pageCount(Integer.MIN_VALUE, 10), "minimum count");
        equal(1, LibraryDates.pageCount(1, 10), "one record");
        equal(1, LibraryDates.pageCount(10, 10), "full first page");
        equal(2, LibraryDates.pageCount(11, 10), "partial second page");
        equal(2, LibraryDates.pageCount(20, 10), "full second page");
        equal(3, LibraryDates.pageCount(21, 10), "partial third page");
        equal(0, LibraryDates.clampPage(-1, 21, 10), "negative page");
        equal(0, LibraryDates.clampPage(Integer.MIN_VALUE, 21, 10), "minimum page");
        equal(0, LibraryDates.clampPage(0, 21, 10), "first page");
        equal(1, LibraryDates.clampPage(1, 21, 10), "middle page");
        equal(2, LibraryDates.clampPage(2, 21, 10), "last page");
        equal(2, LibraryDates.clampPage(3, 21, 10), "past last page");
        equal(2, LibraryDates.clampPage(Integer.MAX_VALUE, 21, 10), "maximum page");
        equal(0, LibraryDates.clampPage(7, 0, 10), "empty selection");
        equal(0, LibraryDates.clampPage(7, -10, 10), "negative count selection");
        equal(0, LibraryDates.clampPage(2, 5, 10), "selection shrinks to one page");
    }

    private static void hugeCounts() {
        equal(Integer.MAX_VALUE, LibraryDates.pageCount(Integer.MAX_VALUE, 1), "huge unit pages");
        equal(1_073_741_824, LibraryDates.pageCount(Integer.MAX_VALUE, 2), "huge pair pages");
        equal(214_748_365, LibraryDates.pageCount(Integer.MAX_VALUE, 10), "huge ten pages");
        equal(1, LibraryDates.pageCount(Integer.MAX_VALUE, Integer.MAX_VALUE), "huge exact page");
        equal(2, LibraryDates.pageCount(Integer.MAX_VALUE, Integer.MAX_VALUE - 1), "huge remainder");
        equal(1, LibraryDates.pageCount(1, Integer.MAX_VALUE), "huge size tiny count");
        equal(Integer.MAX_VALUE - 1,
                LibraryDates.clampPage(Integer.MAX_VALUE, Integer.MAX_VALUE, 1), "huge page clamp");
        equal(1, LibraryDates.clampPage(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1), "huge size clamp");
        int[] counts = {Integer.MIN_VALUE, -1, 0, 1, 2, 9, 10, 11, 127,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        int[] sizes = {1, 2, 3, 10, 127, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int count : counts) {
            for (int size : sizes) {
                int expected = (int) Math.max(1L, (Math.max(0L, count) + size - 1L) / size);
                equal(expected, LibraryDates.pageCount(count, size), "long arithmetic oracle");
                equal(expected - 1, LibraryDates.clampPage(Integer.MAX_VALUE, count, size),
                        "last valid zero-based page");
            }
        }
    }

    private static void invalidPageSizes() {
        for (int size : new int[] {0, -1, Integer.MIN_VALUE}) {
            for (int count : new int[] {0, 1, -1, Integer.MAX_VALUE}) {
                throwsType(IllegalArgumentException.class,
                        () -> LibraryDates.pageCount(count, size), "invalid pageCount size");
                throwsType(IllegalArgumentException.class,
                        () -> LibraryDates.clampPage(0, count, size), "invalid clampPage size");
            }
        }
    }

    private static void boundedText() {
        equal("", LibraryDates.bounded(null, 10), "null");
        equal("", LibraryDates.bounded(null, -1), "null with negative budget");
        equal("", LibraryDates.bounded("", 1), "empty");
        equal("", LibraryDates.bounded("hello", 0), "zero budget");
        equal("", LibraryDates.bounded("hello", -1), "negative budget");
        equal("", LibraryDates.bounded("hello", Integer.MIN_VALUE), "minimum budget");
        equal("\u2026", LibraryDates.bounded("hello", 1), "ellipsis only");
        equal("h\u2026", LibraryDates.bounded("hello", 2), "ellipsis counts in budget");
        equal("hell\u2026", LibraryDates.bounded("hello!", 5), "truncation");
        equal("hello", LibraryDates.bounded("hello", 5), "exact fit");
        equal("hello", LibraryDates.bounded("hello", 6), "under budget");
        equal("hello", LibraryDates.bounded("hello", Integer.MAX_VALUE), "maximum budget");
        equal("a\n\u2026", LibraryDates.bounded("a\nbc", 3), "preserve whitespace");
    }

    private static void boundedEmoji() {
        String smile = "\uD83D\uDE00";
        equal("\u2026", LibraryDates.bounded(smile, 1), "emoji cannot fit");
        equal(smile, LibraryDates.bounded(smile, 2), "emoji exact fit");
        equal("\u2026", LibraryDates.bounded(smile + "ab", 2), "no dangling high surrogate");
        equal(smile + "\u2026", LibraryDates.bounded(smile + "ab", 3), "whole emoji plus ellipsis");
        equal("A\u2026", LibraryDates.bounded("A" + smile + "BC", 3), "back up before pair");
        equal("A" + smile + "\u2026", LibraryDates.bounded("A" + smile + "BC", 4),
                "pair ends before ellipsis");
        String[] samples = {"", "text", smile, smile + smile + "x", "A" + smile + "BC",
                "\uD83D\uDC69\u200D\uD83D\uDCBB notes", "e\u0301", "\uD840\uDC00\uD840\uDC01"};
        for (String sample : samples) {
            for (int limit = -2; limit <= sample.length() + 2; limit++) {
                String result = LibraryDates.bounded(sample, limit);
                check(result.length() <= Math.max(0, limit), "UTF-16 length never exceeds budget");
                wellFormedUtf16(result);
                if (limit <= 0) {
                    equal("", result, "nonpositive budget invariant");
                } else if (sample.length() <= limit) {
                    equal(sample, result, "uncut text unchanged");
                } else {
                    check(result.endsWith("\u2026"), "cut text ends in ellipsis");
                    check(sample.startsWith(result.substring(0, result.length() - 1)),
                            "cut text retains original prefix");
                }
            }
        }
    }

    private static void day(String instant, String zone, String expected) {
        equal(LocalDate.parse(expected), LibraryDates.localDay(Instant.parse(instant).toEpochMilli(),
                ZoneId.of(zone)), instant + " in " + zone);
    }

    private static void grid(YearMonth month, DayOfWeek firstDay, int leading, int size) {
        List<LocalDate> cells = LibraryDates.monthCells(month, firstDay);
        equal(size, cells.size(), "grid size " + month + " " + firstDay);
        for (int i = 0; i < size; i++) {
            int day = i - leading + 1;
            LocalDate expected = day >= 1 && day <= month.lengthOfMonth() ? month.atDay(day) : null;
            equal(expected, cells.get(i), "cell " + i + " of " + month + " " + firstDay);
        }
    }

    private static void verifyCalendar(YearMonth month, DayOfWeek firstDay) {
        List<LocalDate> cells = LibraryDates.monthCells(month, firstDay);
        check(cells.size() == 28 || cells.size() == 35 || cells.size() == 42, "full-week grid size");
        int first = cells.indexOf(month.atDay(1));
        check(first >= 0 && first < 7, "first date lies in first week");
        int last = cells.lastIndexOf(month.atEndOfMonth());
        check(last >= cells.size() - 7, "last date lies in last week");
        int dates = 0;
        for (int i = 0; i < cells.size(); i++) {
            LocalDate date = cells.get(i);
            if (i < first || i > last) {
                equal(null, date, "outside month uses null placeholders");
            } else {
                equal(month.atDay(++dates), date, "in-month dates are complete and ordered");
                equal(firstDay.plus(i % 7), date.getDayOfWeek(), "weekday column");
            }
        }
        equal(month.lengthOfMonth(), dates, "exactly one cell per date");
    }

    private static void wellFormedUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                check(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
                        "high surrogate has matching low surrogate");
                i++;
            } else {
                check(!Character.isLowSurrogate(current), "no unmatched low surrogate");
            }
        }
    }

    private static void run(String name, Runnable test) {
        test.run();
        tests++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static void equal(Object expected, Object actual, String label) {
        assertions++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void throwsType(Class<? extends Throwable> expected, Runnable action, String label) {
        assertions++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(label + ": wrong exception", error);
        }
        throw new AssertionError(label + ": expected " + expected.getSimpleName());
    }
}
