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

    static MonthScheduleService.Plan plan(MonthScheduleService.Prepared p, MonthScheduleService.Outcome out) {
        return new MonthScheduleService.Plan("id", YM, MonthSchedule.HolidayPolicy.MINIMIZE,
                MonthSchedule.Priority.FAIRNESS, out.planned(), p.prior(), out.unfilled(), p.staff(), p.names(),
                String.join("\n", out.summary().lines()), java.time.Instant.now());
    }

    static MonthScheduleService.Outcome compose(MonthScheduleService.Prepared p) {
        return MonthScheduleService.compose(p, YM, MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS);
    }

    @Test
    void composeFillsGapsWithFlexAndSummarizes() {
        var out = compose(prep(true, Set.of()));
        assertNull(out.failure());
        assertTrue(out.planned().stream().anyMatch(s -> s.staffId() == 7)); // 직원이 부족분 충원
        assertTrue(out.summary().laborCost() > 0);
        assertTrue(out.summary().lines().get(0).startsWith("예상 인건비"));
    }

    @Test
    void partTimerOnlyStoreUsesAvailHours() {
        // 알바만 있는 매장: 김알바(월 고정 오픈) + 9번 알바(매일 08~12 추가 가능). 오픈 2명 필요.
        var p = prep(false, Set.of());
        List<WorkHours> avail = java.util.Collections.nCopies(7, new WorkHours(480, 720));
        List<Person> people = new ArrayList<>(p.people());
        people.add(new Person(9, true, null, null, avail));
        var p2 = new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), people, p.staff(), p.names(),
                p.off(), p.dates());
        var out = compose(p2);
        assertEquals(6, out.planned().stream().filter(s -> s.staffId() == 9).count()); // 7일째는 주 1회 휴일
        var sunday = out.unfilled().get(out.unfilled().size() - 1);
        assertEquals(MON.plusDays(6), sunday.date());
        assertEquals(2, sunday.missing());
    }

    @Test
    void priorDaysOfFirstWeekCountForWeeklyRest() {
        // 대상은 수~일. 박직원(7)은 같은 주 월·화에 이미 근무 → 수~토 4일만 더, 일요일은 못 채움.
        var p = prep(true, Set.of());
        List<Planned> prior = List.of(new Planned(7, MON, 480, 720, "직원", 30),
                new Planned(7, MON.plusDays(1), 480, 720, "직원", 30));
        var p2 = new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), p.people(), p.staff(), p.names(),
                p.off(), p.dates().subList(2, 7), prior);
        var out = compose(p2);
        var mine = out.planned().stream().filter(s -> s.staffId() == 7).map(Planned::date).toList();
        assertEquals(List.of(MON.plusDays(2), MON.plusDays(3), MON.plusDays(4), MON.plusDays(5)), mine);
        assertTrue(out.planned().stream().noneMatch(s -> s.date().isBefore(MON.plusDays(2)))); // prior 는 결과에 없음
        assertTrue(out.summary().lines().stream().noneMatch(l -> l.contains("7일 근무")));
    }

    @Test
    void sevenFixedDaysGetWarningLine() {
        var p = prep(false, Set.of());
        var everyDay = new Person(8, true, FixedShiftPlannerTest.week("오픈", "오픈", "오픈", "오픈", "오픈", "오픈", "오픈"), null);
        var out = compose(new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), List.of(everyDay),
                p.staff(), p.names(), p.off(), p.dates()));
        assertTrue(out.summary().lines().contains("김알바님 11/2주 7일 근무예요. 주 1회 휴일이 필요해요"));
    }

    @Test
    void briefIsAnonymizedThenNamedAndPrecedesLines() {
        var p = prep(true, Set.of());
        var pl = plan(p, compose(p));
        String c = MonthScheduleService.briefed(pl, l -> {
            assertTrue(l.stream().noneMatch(x -> x.contains("김알바") || x.contains("박직원")));
            return "#7 님이 많이 일해요.";
        });
        assertEquals("박직원 님이 많이 일해요.\n\n" + pl.comment(), c);
        assertNull(MonthScheduleService.briefed(pl, l -> " "));
    }

    @Test
    void offDaysDropFixedShifts() {
        var out = compose(prep(false, Set.of("8|" + MON)));
        assertTrue(out.planned().stream().noneMatch(s -> s.date().equals(MON)));
    }

    @Test
    void failureMessages() {
        var none = new MonthScheduleService.Prepared(new StoreHours(480, 1320, 480, 1320), List.of(), List.of(),
                List.of(), List.of(), Map.of(), Set.of(), List.of(MON));
        assertEquals("직원·알바생을 먼저 등록하세요", MonthScheduleService.compose(none, YM,
                MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS).failure());
        var empty = new MonthScheduleService.Prepared(new StoreHours(480, 1320, 480, 1320), List.of(), List.of(),
                List.of(new Person(8, true, null, null)), List.of(), Map.of(), Set.of(), List.of(MON));
        assertEquals("고정 파트·근무시간을 먼저 정하세요", MonthScheduleService.compose(empty, YM,
                MonthSchedule.HolidayPolicy.MINIMIZE, MonthSchedule.Priority.FAIRNESS).failure());
    }

    @Test
    void attendedShiftsAreContextOnly() {
        // 월요일 박직원(7)의 출근 기록 있는 오픈 근무가 남는다 → 오픈 2명이 차서 충원 없음, 결과에도 안 들어감.
        var p = prep(true, Set.of());
        var kept = List.of(new Planned(7, MON, 480, 720, "오픈", 30));
        var out = compose(new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), p.people(), p.staff(),
                p.names(), p.off(), p.dates(), kept));
        assertEquals(List.of(8), out.planned().stream().filter(s -> s.date().equals(MON)).map(Planned::staffId).toList());
        assertTrue(out.unfilled().stream().noneMatch(g -> g.date().equals(MON)));
    }

    @Test
    void insertableDropsDaysBeforeFromAndOffDays() {
        var planned = List.of(new Planned(7, MON, 480, 720, null, 30), new Planned(7, MON.plusDays(1), 480, 720, null, 30),
                new Planned(8, MON.plusDays(1), 480, 720, "오픈", 30), new Planned(8, MON.plusDays(2), 480, 720, "오픈", 30));
        var out = MonthScheduleService.insertable(planned, MON.plusDays(1), null, Set.of("8|" + MON.plusDays(2)));
        assertEquals(List.of(planned.get(1), planned.get(2)), out);
        // 화요일 08:00 이 지났으면 그날 08:00 근무는 버린다.
        var later = MonthScheduleService.insertable(planned, MON.plusDays(1), MON.plusDays(1).atTime(8, 0), Set.of());
        assertEquals(List.of(planned.get(3)), later);
    }

    @Test
    void startedSlotsTodayAreNotPlanned() {
        // 월요일 10시: 월요일 오픈(8~12)은 이미 시작 → 근무·못 채운 칸 모두 없음. 화요일부터는 그대로.
        var p = prep(true, Set.of());
        var out = compose(new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), p.people(), p.staff(),
                p.names(), p.off(), p.dates(), List.of(), MON.atTime(10, 0)));
        assertTrue(out.planned().stream().noneMatch(s -> s.date().equals(MON)));
        assertTrue(out.unfilled().stream().noneMatch(g -> g.date().equals(MON)));
        assertTrue(out.planned().stream().anyMatch(s -> s.date().equals(MON.plusDays(1))));
    }

    @Test
    void fixedShiftOverlappingKeptShiftIsDropped() {
        // 김알바(8)의 월요일 8~11 출근 기록 근무가 남는다 → 같은 사람 오픈 고정 근무는 미리보기에 다시 안 들어간다.
        var p = prep(true, Set.of());
        var kept = List.of(new Planned(8, MON, 480, 660, "오픈", 0));
        var out = compose(new MonthScheduleService.Prepared(p.hours(), p.parts(), p.needs(), p.people(), p.staff(),
                p.names(), p.off(), p.dates(), kept));
        assertTrue(out.planned().stream().noneMatch(s -> s.staffId() == 8 && s.date().equals(MON)));
        assertEquals(List.of(7), out.planned().stream().filter(s -> s.date().equals(MON)).map(Planned::staffId).toList());
    }
}
