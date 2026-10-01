package com.ruoyi.dstokencheck.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether DeepSeek is billing peak or off-peak rates right now.
 *
 * <p>Straight from the published pricing page: off-peak rates are half of peak rates, peak hours are
 * 01:00-04:00 and 06:00-10:00 UTC, Monday through Friday, excluding Chinese public holidays, and
 * every other hour is off-peak — weekends and Chinese public holidays in full.
 *
 * <p>Both windows fall on 09:00-12:00 and 14:00-18:00 Beijing time on the <em>same</em> calendar day
 * (UTC 01:00 Monday is Beijing 09:00 Monday), so the rule can be evaluated on a Beijing clock. That
 * is also the calendar the holiday list is published in, which keeps the two halves of the rule in
 * one time zone instead of comparing a UTC window against a Beijing date.
 */
public final class PeakHours {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** Peak windows as half-open Beijing-time ranges: [start, end). */
    private static final LocalTime[][] PEAK_WINDOWS = {
        {LocalTime.of(9, 0), LocalTime.of(12, 0)},
        {LocalTime.of(14, 0), LocalTime.of(18, 0)},
    };

    /**
     * Statutory holidays, from the State Council's annual notice.
     *
     * <p>Only years already announced are listed. The notice also names make-up working days, but
     * those fall on weekends and the rule grants weekends off-peak in full, so they change nothing.
     * An unlisted year simply falls back to the weekday windows, which can call a holiday peak —
     * {@link #holidaysKnownFor(int)} tells the caller so it can say as much.
     */
    private static final Set<String> HOLIDAYS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(
                    // 2026 — 国办发明电〔2025〕7号
                    "2026-01-01", "2026-01-02", "2026-01-03",
                    "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
                    "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
                    "2026-04-04", "2026-04-05", "2026-04-06",
                    "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
                    "2026-06-19", "2026-06-20", "2026-06-21",
                    "2026-09-25", "2026-09-26", "2026-09-27",
                    "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05",
                    "2026-10-06", "2026-10-07")));

    private PeakHours() {
    }

    /** True when off-peak rates apply at this instant. */
    public static boolean isOffPeak(long epochMillis) {
        return !isPeak(epochMillis);
    }

    /** True when peak rates apply at this instant. */
    public static boolean isPeak(long epochMillis) {
        return isPeak(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), BEIJING));
    }

    /** The rule itself, on a Beijing clock: a weekday, outside the holidays, inside a peak window. */
    static boolean isPeak(LocalDateTime beijing) {
        DayOfWeek day = beijing.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        if (isHoliday(beijing.toLocalDate())) {
            return false;
        }
        LocalTime time = beijing.toLocalTime();
        for (LocalTime[] window : PEAK_WINDOWS) {
            if (!time.isBefore(window[0]) && time.isBefore(window[1])) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date.toString());
    }

    /** False for a year whose holiday notice is not in this build yet. */
    public static boolean holidaysKnownFor(int year) {
        String prefix = year + "-";
        for (String holiday : HOLIDAYS) {
            if (holiday.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The line shown where the balance normally is. */
    public static String label(long epochMillis) {
        return isOffPeak(epochMillis) ? "现在是空闲时段" : "现在是繁忙时段";
    }

    /** The peak windows in Beijing time, for the tooltip. */
    public static String windowsInBeijingTime() {
        return "\u5468\u4e00\u81f3\u5468\u4e94 09:00-12:00\u300114:00-18:00";
    }
}
