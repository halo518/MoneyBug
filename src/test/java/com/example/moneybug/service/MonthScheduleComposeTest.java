package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.service.FixedShiftPlanner.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MonthScheduleComposeTest {

    static final LocalDate MON = LocalDate.of(2026, 11, 2);
    static final YearMonth YM = YearMonth.of(2026, 11);

    static MonthScheduleService.Prepared prep(boolean withFlex, Set<String> off) {
        List<Person> people = new ArrayList<>();
        List<ScheduleInsights.StaffInfo> staff = new ArrayList<>();
        people.add(new Person(8, true, FixedShiftPlannerTest.week("오픈"), null));
        staff.add(new ScheduleInsights.StaffInfo(8, "김알바", true, 10000));
        if (withFlex) {
            people.add(new Person(7, false, null, null));
            staff.add(new ScheduleInsights.StaffInfo(7, "박직원", false, 12000));
        }
        return new MonthScheduleService.Prepared(new StoreHours(480, 1320, 480, 1320),
                List.of(new PartDef("오픈", 480, 720, 30)),
                List.of(new GapFiller.PartNeed("오픈", 480, 720, 2)),
                people, staff, Map.of(7, "박직원", 8, "김알바"), off,
                IntStream.range(0, 7).mapToObj(MON::plusDays).toList());
    }

    @Test
    void aiFailureStillSucceedsWithLinesOnly() {
        var out = MonthScheduleService.compose(prep(true, Set.of()), YM, MonthSchedule.HolidayPolicy.MINIMIZE,
                MonthSchedule.Priority.FAIRNESS, l -> { throw new IllegalStateException("down"); });
        assertNull(out.failure());
        assertFalse(out.planned().isEmpty());
        assertTrue(out.planned().stream().anyMatch(s -> s.staffId() == 7)); // 직원이 부족분 충원
        assertFalse(out.comment().isBlank());
        assertFalse(out.comment().contains("#"));
    }

    @Test
    void aiSentenceGetsNamesAndPrecedesLines() {
        var out = MonthScheduleService.compose(prep(true, Set.of()), YM, MonthSchedule.HolidayPolicy.MINIMIZE,
                MonthSchedule.Priority.FAIRNESS, l -> {
                    assertTrue(l.stream().noneMatch(x -> x.contains("김알바") || x.contains("박직원")));
                    return "#7 님이 많이 일해요.";
                });
        assertTrue(out.comment().startsWith("박직원 님이 많이 일해요.\n\n"));
    }

    @Test
    void blankAiMeansLinesOnly() {
        var out = MonthScheduleService.compose(prep(true, Set.of()), YM, MonthSchedule.HolidayPolicy.MINIMIZE,
                MonthSchedule.Priority.FAIRNESS, l -> " ");
        assertFalse(out.comment().startsWith("\n"));
        assertNull(out.failure());
    }

    @Test
    void offDaysDropFixedShifts() {
        var out = MonthScheduleService.compose(prep(false, Set.of("8|" + MON)), YM,
                MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS, l -> "");
        assertTrue(out.planned().stream().noneMatch(s -> s.date().equals(MON)));
    }

    @Test
    void failureMessages() {
        var none = new MonthScheduleService.Prepared(new StoreHours(480, 1320, 480, 1320), List.of(), List.of(),
                List.of(), List.of(), Map.of(), Set.of(), List.of(MON));
        assertEquals("직원·알바생을 먼저 등록하세요", MonthScheduleService.compose(none, YM,
                MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS, l -> "").failure());
        var empty = new MonthScheduleService.Prepared(new StoreHours(480, 1320, 480, 1320), List.of(), List.of(),
                List.of(new Person(8, true, null, null)), List.of(), Map.of(), Set.of(), List.of(MON));
        assertEquals("고정 파트·근무시간을 먼저 정하세요", MonthScheduleService.compose(empty, YM,
                MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS, l -> "").failure());
    }
}
