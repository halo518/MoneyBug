package com.example.moneybug.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** 한국 공휴일. shortcut: 2026~2027년 공휴일·대체공휴일만 하드코딩, 다른 해는 주말만 구분 — 다른 해가 필요하면 특일정보 API 로 바꾼다. */
public final class Holidays {

    private static final Set<LocalDate> HOLIDAYS = Set.of(
            "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-01", "2026-03-02",
            "2026-05-01", "2026-05-05", "2026-05-24", "2026-05-25", "2026-06-03", "2026-06-06",
            "2026-07-17", "2026-08-15", "2026-08-17", "2026-09-24", "2026-09-25", "2026-09-26",
            "2026-10-03", "2026-10-05", "2026-10-09", "2026-12-25",
            "2027-01-01", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-03-01", "2027-05-01",
            "2027-05-05", "2027-05-13", "2027-06-06", "2027-07-17", "2027-08-15", "2027-08-16", "2027-09-14",
            "2027-09-15", "2027-09-16", "2027-10-03", "2027-10-04", "2027-10-09", "2027-10-11", "2027-12-25",
            "2027-12-27")
            .stream().map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private Holidays() {}

    public static boolean isHoliday(LocalDate d) {
        return HOLIDAYS.contains(d);
    }

    /** 토·일 또는 공휴일 → 주말 영업시간을 쓴다. */
    public static boolean isWeekendOrHoliday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || isHoliday(d);
    }
}
