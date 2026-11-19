package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.StorePart.Days;
import com.example.moneybug.service.FixedShiftPlanner.*;
import com.example.moneybug.service.GapFiller.PartNeed;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 직원 파트 순환(그 달 같은 파트를 적게 맡은 직원부터)과 직원 고정 파트·고정 시간. */
class EmployeePartRotationTest {

    // 2026-10: 3일(토 개천절), 5일(월 대체공휴일), 9일(금 한글날) → 주말 취급.
    static final YearMonth YM = YearMonth.of(2026, 10);
    static final List<LocalDate> DATES = IntStream.rangeClosed(1, YM.lengthOfMonth()).mapToObj(YM::atDay).toList();
    static final StoreHours HOURS = new StoreHours(540, 1320, 540, 1320); // 09~22
    static final List<PartDef> DEFS = List.of(
            new PartDef("오픈", 420, 960, 60, true), new PartDef("주말 오픈", 420, 960, 60, true),
            new PartDef("미들", 660, 1200, 60, true), new PartDef("마감", 840, 1380, 60, true));
    static final List<PartNeed> NEEDS = List.of(
            new PartNeed("오픈", 420, 960, 1, 60, true, Days.WEEKDAY),
            new PartNeed("주말 오픈", 420, 960, 1, 60, true, Days.WEEKEND),
            new PartNeed("미들", 660, 1200, 1, 60, true, Days.ALL),
            new PartNeed("마감", 840, 1380, 1, 60, true, Days.ALL));

    static Person employee(int id) {
        return new Person(id, false, null, null);
    }

    static MonthScheduleService.Outcome compose(List<Person> people) {
        List<ScheduleInsights.StaffInfo> staff = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        for (Person p : people) {
            staff.add(new ScheduleInsights.StaffInfo(p.id(), "직원" + p.id(), p.partTimer(), 12000));
            names.put(p.id(), "직원" + p.id());
        }
        var prep = new MonthScheduleService.Prepared(HOURS, DEFS, NEEDS, people, staff, names, Set.of(), DATES);
        return MonthScheduleService.compose(prep, YM, MonthSchedule.HolidayPolicy.MINIMIZE,
                MonthSchedule.Priority.FAIRNESS);
    }

    static int work(Planned p) {
        return p.endMin() - p.startMin() - p.breakMin();
    }

    static void assertWeeklyCap(List<Planned> planned) {
        Map<String, Integer> week = new HashMap<>();
        for (Planned p : planned) {
            LocalDate mon = p.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            week.merge(p.staffId() + "|" + mon, work(p), Integer::sum);
        }
        week.forEach((k, v) -> assertTrue(v <= 2400, k + " 주 " + v + "분"));
    }

    /** 파트별 사람당 근무 수의 최대 - 최소(5명 모두 포함, 안 맡은 사람은 0). */
    static Map<String, Integer> spread(List<Planned> planned, List<Integer> ids) {
        Map<String, Integer> out = new HashMap<>();
        for (PartDef d : DEFS) {
            List<Long> counts = ids.stream().map(id -> planned.stream()
                    .filter(p -> p.staffId() == id && d.name().equals(p.part())).count()).toList();
            out.put(d.name(), (int) (Collections.max(counts) - Collections.min(counts)));
        }
        return out;
    }

    @Test
    void fiveEmployeesRotateFairlyOverWholeMonth() {
        List<Person> people = IntStream.rangeClosed(1, 5).mapToObj(EmployeePartRotationTest::employee).toList();
        var out = compose(people);
        assertNull(out.failure());
        assertEquals(List.of(), out.unfilled());
        assertEquals(DATES.size() * 3, out.planned().size()); // 날마다 3파트 x 1명
        // 날마다 그날 쓰는 파트마다 정확히 한 명, 한 사람 하루 한 근무.
        for (LocalDate d : DATES) {
            List<Planned> day = out.planned().stream().filter(p -> p.date().equals(d)).toList();
            assertEquals(3, day.stream().map(Planned::staffId).distinct().count(), d.toString());
            String open = day.stream().map(Planned::part).filter(n -> n.contains("오픈")).findFirst().orElseThrow();
            assertEquals(com.example.moneybug.util.Holidays.isWeekendOrHoliday(d) ? "주말 오픈" : "오픈", open, d.toString());
        }
        assertWeeklyCap(out.planned());
        spread(out.planned(), List.of(1, 2, 3, 4, 5)).forEach((part, s) -> assertTrue(s <= 1, part + " 차이 " + s));
    }

    @Test
    void everyMonthOf2026And2027HasNoGapAndSpreadAtMostTwo() {
        // 날짜순·파트순 탐욕 배정이라 "최대 1" 은 달에 따라 깨진다(2026-04·06·12, 2027-03 에 2). 2 는 24개월 모두 지킨다.
        List<Person> people = IntStream.rangeClosed(1, 5).mapToObj(EmployeePartRotationTest::employee).toList();
        List<ScheduleInsights.StaffInfo> staff = people.stream()
                .map(p -> new ScheduleInsights.StaffInfo(p.id(), "직원" + p.id(), false, 12000)).toList();
        for (int i = 0; i < 24; i++) {
            YearMonth ym = YearMonth.of(2026, 1).plusMonths(i);
            List<LocalDate> dates = IntStream.rangeClosed(1, ym.lengthOfMonth()).mapToObj(ym::atDay).toList();
            var prep = new MonthScheduleService.Prepared(HOURS, DEFS, NEEDS, people, staff, Map.of(), Set.of(), dates);
            var out = MonthScheduleService.compose(prep, ym, MonthSchedule.HolidayPolicy.MINIMIZE,
                    MonthSchedule.Priority.FAIRNESS);
            assertEquals(List.of(), out.unfilled(), ym.toString());
            assertWeeklyCap(out.planned());
            spread(out.planned(), List.of(1, 2, 3, 4, 5))
                    .forEach((part, s) -> assertTrue(s <= 2, ym + " " + part + " 차이 " + s));
        }
    }

    @Test
    void employeeFixedToWeekdayOpen() {
        List<List<String>> fixed = new ArrayList<>();
        for (int i = 0; i < 7; i++) fixed.add(i < 5 ? List.of("오픈") : List.of());
        List<Person> people = new ArrayList<>();
        people.add(new Person(1, false, fixed, null));
        IntStream.rangeClosed(2, 5).forEach(i -> people.add(employee(i)));
        assertFalse(people.get(0).flex());

        var out = compose(people);
        assertNull(out.failure());
        assertEquals(List.of(), out.unfilled());
        for (LocalDate d : DATES) {
            List<Planned> opens = out.planned().stream().filter(p -> p.date().equals(d) && "오픈".equals(p.part())).toList();
            if (d.getDayOfWeek().getValue() <= 5) {
                // 공휴일 평일에도 고정 파트는 요일 칸이 정한다(파트 days 안 봄). 오픈은 그날 한 개뿐.
                assertEquals(1, opens.size(), d.toString());
                assertEquals(1, opens.get(0).staffId(), d.toString());
                assertEquals(420, opens.get(0).startMin()); // 영업 09시 전 시작 그대로
                assertEquals(60, opens.get(0).breakMin());
            } else {
                assertEquals(0, opens.size(), d.toString());
            }
        }
        // 1번은 오픈만, 나머지 파트는 2~5번이 돈다.
        assertTrue(out.planned().stream().filter(p -> p.staffId() == 1).allMatch(p -> "오픈".equals(p.part())));
        assertTrue(out.planned().stream().filter(p -> !"오픈".equals(p.part())).allMatch(p -> p.staffId() != 1));
        assertWeeklyCap(out.planned());
        Map<String, Integer> s = spread(out.planned(), List.of(2, 3, 4, 5));
        for (String part : List.of("주말 오픈", "미들", "마감")) assertTrue(s.get(part) <= 1, part + " 차이 " + s.get(part));
    }

    @Test
    void employeeWithFixedHoursIsNotRotatedAndKeepsHoursOutsideBusiness() {
        List<WorkHours> wh = new ArrayList<>(Collections.nCopies(7, (WorkHours) null));
        wh.set(0, new WorkHours(360, 900)); // 월 06~15, 영업 09시 전
        Person fixedHours = new Person(1, false, null, wh);
        assertFalse(fixedHours.flex());
        List<Person> people = new ArrayList<>(List.of(fixedHours));
        IntStream.rangeClosed(2, 5).forEach(i -> people.add(employee(i)));

        var out = compose(people);
        List<Planned> mine = out.planned().stream().filter(p -> p.staffId() == 1).toList();
        Set<LocalDate> mondays = DATES.stream().filter(d -> d.getDayOfWeek() == DayOfWeek.MONDAY).collect(Collectors.toSet());
        assertEquals(mondays, mine.stream().map(Planned::date).collect(Collectors.toSet()));
        assertTrue(mine.stream().allMatch(p -> p.part() == null && p.startMin() == 360 && p.endMin() == 900));
    }
}
