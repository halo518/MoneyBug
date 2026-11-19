package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule.Priority;
import com.example.moneybug.entity.StorePart.Days;
import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import com.example.moneybug.service.FixedShiftPlanner.WorkHours;
import com.example.moneybug.service.GapFiller.PartNeed;
import com.example.moneybug.service.GapFiller.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 직원 파트(flex): 직원만, 파트 시간 그대로, 파트 이름, 합치지 않음, days 적용. */
class GapFillerEmployeePartTest {

    static final StoreHours HOURS = new StoreHours(540, 1320, 540, 1320); // 09~22
    static final LocalDate MON = LocalDate.of(2026, 6, 8);
    static final LocalDate SAT = LocalDate.of(2026, 6, 13);
    static final LocalDate SUN = LocalDate.of(2026, 6, 14);
    static final LocalDate HOLIDAY_WED = LocalDate.of(2026, 6, 3); // 지방선거일

    static PartNeed emp(String name, int s, int e, Days days) {
        return new PartNeed(name, s, e, 1, 60, true, days);
    }

    static Person employee(int id) {
        return new Person(id, false, null, null);
    }

    static Person partTimerAllDay(int id) {
        return new Person(id, true, null, null, Collections.nCopies(7, new WorkHours(0, 1440)));
    }

    static Result run(List<PartNeed> parts, List<Planned> existing, List<Person> people, List<LocalDate> dates) {
        return GapFiller.fill(HOURS, parts, existing, people, Map.of(), Set.of(), dates, Priority.FAIRNESS);
    }

    @Test
    void employeePartUsesOnlyEmployeesPartTimesAndName() {
        PartNeed open = emp("오픈직원", 420, 960, Days.ALL); // 07~16, 영업 09시보다 일찍 시작
        Result r = run(List.of(open), List.of(), List.of(partTimerAllDay(9), employee(1)), List.of(MON));
        assertEquals(1, r.shifts().size());
        Planned p = r.shifts().get(0);
        assertEquals(1, p.staffId());
        assertEquals(420, p.startMin()); // 영업시간으로 안 자름
        assertEquals(960, p.endMin());
        assertEquals(60, p.breakMin());
        assertEquals("오픈직원", p.part());

        // 알바생만 있으면 못 채우고 빈칸으로 남는다.
        Result onlyPt = run(List.of(open), List.of(), List.of(partTimerAllDay(9)), List.of(MON));
        assertTrue(onlyPt.shifts().isEmpty());
        assertEquals(1, onlyPt.unfilled().size());
        assertEquals("오픈직원", onlyPt.unfilled().get(0).part());
        assertEquals(420, onlyPt.unfilled().get(0).startMin());
    }

    @Test
    void daysWeekdayWeekendAndHoliday() {
        List<Person> people = List.of(employee(1), employee(2), employee(3));
        Result weekday = run(List.of(emp("평일", 600, 900, Days.WEEKDAY)), List.of(), people,
                List.of(MON, SAT, HOLIDAY_WED));
        assertEquals(List.of(MON), weekday.shifts().stream().map(Planned::date).toList());
        assertTrue(weekday.unfilled().isEmpty());

        Result weekend = run(List.of(emp("주말", 600, 900, Days.WEEKEND)), List.of(), people,
                List.of(MON, SAT, HOLIDAY_WED));
        assertEquals(List.of(HOLIDAY_WED, SAT), weekend.shifts().stream().map(Planned::date).toList());

        // 알바 파트도 days 를 따른다: 안 맞는 날엔 부족으로 안 센다.
        PartNeed ptWeekend = new PartNeed("주말알바", 600, 900, 1, 0, false, Days.WEEKEND);
        Result pt = run(List.of(ptWeekend), List.of(), List.of(), List.of(MON, SAT));
        assertEquals(List.of(SAT), pt.unfilled().stream().map(GapFiller.Gap::date).toList());
    }

    @Test
    void overlappingEmployeePartsAreNeverMerged() {
        List<PartNeed> parts = List.of(emp("미들1", 660, 1200, Days.ALL), emp("미들2", 720, 1260, Days.ALL));
        Result r = run(parts, List.of(), List.of(employee(1), employee(2)), List.of(MON));
        assertEquals(2, r.shifts().size());
        assertEquals(List.of("미들1", "미들2"), r.shifts().stream().map(Planned::part).toList());
        assertEquals(List.of(660, 720), r.shifts().stream().map(Planned::startMin).toList());
        assertNotEquals(r.shifts().get(0).staffId(), r.shifts().get(1).staffId());

        // 한 사람뿐이면 첫 파트만 맡고 둘째는 빈칸(합친 근무 없음).
        Result one = run(parts, List.of(), List.of(employee(1)), List.of(MON));
        assertEquals(1, one.shifts().size());
        assertEquals("미들1", one.shifts().get(0).part());
        assertEquals(1200, one.shifts().get(0).endMin());
        assertEquals("미들2", one.unfilled().get(0).part());
    }

    @Test
    void partTimerPartShortageStillFilledByFreeEmployeeAsUnnamedShift() {
        PartNeed empPart = emp("오픈직원", 420, 960, Days.ALL);
        PartNeed ptPart = new PartNeed("마감", 1020, 1320, 1);
        Result r = run(List.of(ptPart, empPart), List.of(), List.of(employee(1), employee(2)), List.of(MON));
        assertEquals(2, r.shifts().size());
        Planned emp = r.shifts().stream().filter(s -> "오픈직원".equals(s.part())).findFirst().orElseThrow();
        Planned close = r.shifts().stream().filter(s -> s.part() == null).findFirst().orElseThrow();
        assertEquals(1, emp.staffId()); // 직원 파트가 먼저
        assertEquals(2, close.staffId());
        assertEquals(1020, close.startMin());
    }

    @Test
    void employeeOnEmployeePartIsNotReusedSameDay() {
        PartNeed empPart = emp("오픈직원", 420, 960, Days.ALL);
        PartNeed ptPart = new PartNeed("마감", 1020, 1320, 1);
        Result r = run(List.of(empPart, ptPart), List.of(), List.of(employee(1)), List.of(MON));
        assertEquals(1, r.shifts().size());
        assertEquals("오픈직원", r.shifts().get(0).part());
        assertEquals("마감", r.unfilled().get(0).part());
    }

    @Test
    void existingNamedOrCoveringUnnamedShiftCountsForEmployeePart() {
        PartNeed empPart = emp("오픈직원", 420, 960, Days.ALL);
        Planned named = new Planned(5, MON, 420, 960, "오픈직원", 60);
        assertTrue(run(List.of(empPart), List.of(named), List.of(employee(1)), List.of(MON)).shifts().isEmpty());
        Planned covering = new Planned(5, MON, 400, 1000, null, 60);
        assertTrue(run(List.of(empPart), List.of(covering), List.of(employee(1)), List.of(MON)).shifts().isEmpty());
    }

    @Test
    void weeklyLimitsStillApply() {
        // 월~토 6일 일한 직원은 일요일 직원 파트를 못 맡는다.
        List<Planned> week = new ArrayList<>();
        for (int i = 0; i < 6; i++) week.add(new Planned(1, MON.plusDays(i), 600, 840, "x", 30));
        Result r = run(List.of(emp("오픈직원", 420, 960, Days.ALL)), week, List.of(employee(1)), List.of(SUN));
        assertTrue(r.shifts().isEmpty());
        assertEquals(1, r.unfilled().size());

        // 주 40시간: 이미 36시간이면 8시간 파트는 못 맡는다.
        List<Planned> hours = new ArrayList<>();
        for (int i = 0; i < 4; i++) hours.add(new Planned(1, MON.plusDays(i), 0, 540, "x", 0));
        Result h = run(List.of(emp("오픈직원", 420, 960, Days.ALL)), hours, List.of(employee(1)), List.of(MON.plusDays(4)));
        assertTrue(h.shifts().isEmpty());
    }
}
