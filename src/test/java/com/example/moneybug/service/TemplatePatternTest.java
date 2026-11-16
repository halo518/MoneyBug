package com.example.moneybug.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemplatePatternTest {

    @Test
    void firstFullWeek() {
        // 2026-11-01 = 일 → 첫 완전한 주는 11/2~11/8
        List<LocalDate> w = TemplatePattern.firstFullWeek(YearMonth.of(2026, 11));
        assertEquals(LocalDate.of(2026, 11, 2), w.get(0));
        assertEquals(LocalDate.of(2026, 11, 8), w.get(6));
        // 2026-06-01 = 월
        assertEquals(LocalDate.of(2026, 6, 1), TemplatePattern.firstFullWeek(YearMonth.of(2026, 6)).get(0));
    }

    @Test
    void datesFor() {
        List<LocalDate> target = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 11, 10); d.getMonthValue() == 11; d = d.plusDays(1)) target.add(d);
        // 화요일(1): 11/10, 17, 24
        assertEquals(3, TemplatePattern.datesFor(1, false, target).size());
        assertEquals(List.of(LocalDate.of(2026, 11, 10)), TemplatePattern.datesFor(1, true, target));
        assertEquals(0, TemplatePattern.datesFor(0, false, List.of(LocalDate.of(2026, 11, 10))).size());
    }
}
