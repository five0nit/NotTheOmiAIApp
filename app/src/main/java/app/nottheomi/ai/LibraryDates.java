package app.nottheomi.ai;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure local-date, calendar and bounded-preview helpers for the Library UI. */
final class LibraryDates {
    private LibraryDates() {}

    /** Preserve the original Library's Unicode-aware search matching. */
    static String foldSearch(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .toUpperCase(java.util.Locale.ROOT).toLowerCase(java.util.Locale.ROOT);
    }

    /** Groups an instant by the caller's zone, including daylight-saving changes. */
    static LocalDate localDay(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(Objects.requireNonNull(zone, "zone"))
                .toLocalDate();
    }

    /**
     * Returns the smallest complete-week grid containing this month (28, 35 or
     * 42 cells). Columns begin with firstDay; cells outside the month are null.
     */
    static List<LocalDate> monthCells(YearMonth month, DayOfWeek firstDay) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(firstDay, "firstDay");
        int leading = Math.floorMod(month.atDay(1).getDayOfWeek().getValue()
                - firstDay.getValue(), 7);
        int days = month.lengthOfMonth();
        int cellCount = ((leading + days + 6) / 7) * 7;
        List<LocalDate> cells = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) {
            int day = i - leading + 1;
            cells.add(day >= 1 && day <= days ? month.atDay(day) : null);
        }
        return Collections.unmodifiableList(cells);
    }

    /**
     * Counts pages without overflowing, retaining one page for an empty result.
     * Nonpositive counts are empty; pageSize must be positive.
     */
    static int pageCount(int count, int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        return count <= 0 ? 1 : 1 + (count - 1) / pageSize;
    }

    /** Clamps a zero-based page index; empty results always select page zero. */
    static int clampPage(int page, int count, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(count, pageSize) - 1));
    }

    /**
     * Bounds text by UTF-16 code units, including a single-character ellipsis
     * when cut. Never cuts between a high/low surrogate pair. Null values and
     * nonpositive budgets return an empty string; uncut text is unchanged.
     */
    static String bounded(String value, int maxChars) {
        if (value == null || maxChars <= 0) return "";
        if (value.length() <= maxChars) return value;
        int end = maxChars - 1; // Reserve one UTF-16 code unit for the ellipsis.
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end) + "\u2026";
    }
}
