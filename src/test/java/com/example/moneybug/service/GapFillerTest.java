package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule.Priority;
import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.WorkHours;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import com.example.moneybug.service.GapFiller.PartNeed;
import com.example.moneybug.service.GapFiller.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GapFillerTest {

    private static final StoreHours HOURS = new StoreHours(540, 1320, 540, 1320);
    private static final LocalDate MON = LocalDate.of(2026, 6, 8);
    private static final PartNeed AM = new PartNeed("오전", 540, 780, 1);   // 09-13
    private static final PartNeed PM = new PartNeed("오후", 780, 1080, 1);  // 13-18

    private static Result run(List<PartNeed> parts, List<Planned> existing, List<Integer> flex,
                              Map<Integer, Integer> wages, Set<String> off, List<LocalDate> dates, Priority pr) {
        return GapFiller.fill(HOURS, parts, existing,
                flex.stream().map(id -> new Person(id, false, null, null)).toList(), wages, off, dates, pr);
    }

    private static Result run(List<PartNeed> parts, List<Planned> existing, List<Integer> flex, List<LocalDate> dates) {
        return run(parts, existing, flex, Map.of(), Set.of(), dates, Priority.FAIRNESS);
    }

    @Test
    void fillsMissing() {
        Result r = run(List.of(AM), List.of(), List.of(1), List.of(MON));
        assertEquals(1, r.shifts().size());
        Planned p = r.shifts().get(0);
        assertEquals(1, p.staffId());
        assertEquals(540, p.startMin());
        assertEquals(780, p.endMin());
        assertEquals(30, p.breakMin());
        assertTrue(r.unfilled().isEmpty());
    }

    @Test
    void needTwoGetsTwoPeople() {
        Result r = run(List.of(new PartNeed("오전", 540, 780, 2)), List.of(), List.of(1, 2, 3), List.of(MON));
        assertEquals(List.of(1, 2), r.shifts().stream().map(Planned::staffId).toList());
    }

    @Test
    void oneShiftPerDay() {
        // 오전·오후가 맞닿지 않게 떼어 놓으면 한 사람이 둘 다 못 맡는다.
        Result r = run(List.of(new PartNeed("a", 540, 600, 1), new PartNeed("b", 900, 960, 1)),
                List.of(), List.of(1), List.of(MON));
        assertEquals(1, r.shifts().size());
        assertEquals(1, r.unfilled().size());
        assertEquals("b", r.unfilled().get(0).part());
    }

    @Test
    void neverExceedsFortyHoursPerWeek() {
        // 5일 × 9h(540분, 휴게 60 → 480분) = 2400분. 6일째는 못 한다.
        PartNeed full = new PartNeed("종일", 540, 1080, 1);
        List<LocalDate> week = List.of(MON, MON.plusDays(1), MON.plusDays(2), MON.plusDays(3), MON.plusDays(4),
                MON.plusDays(5));
        Result r = run(List.of(full), List.of(), List.of(1), week);
        assertEquals(5, r.shifts().size());
        assertEquals(1, r.unfilled().size());
        assertEquals(MON.plusDays(5), r.unfilled().get(0).date());
    }

    @Test
    void skipsOffDay() {
        Result r = run(List.of(AM), List.of(), List.of(1, 2), Map.of(), Set.of("1|" + MON),
                List.of(MON), Priority.FAIRNESS);
        assertEquals(2, r.shifts().get(0).staffId());
    }

    @Test
    void fairnessPrefersLessWorked() {
        List<Planned> existing = List.of(new Planned(1, MON, 540, 780, null, 30));
        Result r = run(List.of(AM), existing, List.of(1, 2), List.of(MON.plusDays(1)));
        assertEquals(2, r.shifts().get(0).staffId());
    }

    @Test
    void costPrefersLowerWage() {
        Result r = run(List.of(AM), List.of(), List.of(1, 2), Map.of(1, 12000, 2, 10000), Set.of(),
                List.of(MON), Priority.COST);
        assertEquals(2, r.shifts().get(0).staffId());
    }

    @Test
    void mergesTouchingPartsIntoOneShift() {
        Result r = run(List.of(AM, PM), List.of(), List.of(1, 2), List.of(MON));
        assertEquals(1, r.shifts().size());
        Planned p = r.shifts().get(0);
        assertEquals(540, p.startMin());
        assertEquals(1080, p.endMin());
        assertEquals(60, p.breakMin());
        assertEquals(null, p.part());
        assertTrue(r.unfilled().isEmpty());
    }

    @Test
    void unfilledWhenNoCandidate() {
        Result r = run(List.of(AM), List.of(), List.of(), List.of(MON));
        assertTrue(r.shifts().isEmpty());
        assertEquals(1, r.unfilled().size());
        GapFillerGap(r, 1);
    }

    private static void GapFillerGap(Result r, int missing) {
        assertEquals(missing, r.unfilled().get(0).missing());
        assertEquals("오전", r.unfilled().get(0).part());
    }

    @Test
    void skipsPartAlreadyFilledByPartTimer() {
        List<Planned> existing = List.of(new Planned(9, MON, 540, 780, "오전", 30));
        Result r = run(List.of(AM, PM), existing, List.of(1), List.of(MON));
        assertEquals(1, r.shifts().size());
        assertEquals(780, r.shifts().get(0).startMin());
        assertEquals(1080, r.shifts().get(0).endMin());
    }

    @Test
    void partTimerFillsOnlyInsideAvailHours() {
        // 월: 09~13 가능 → 오전만, 화: 가능 시간 없음 → 못 채움.
        List<WorkHours> avail = new java.util.ArrayList<>(java.util.Collections.nCopies(7, (WorkHours) null));
        avail.set(0, new WorkHours(540, 780));
        Person alba = new Person(5, true, null, null, avail);
        Result r = GapFiller.fill(HOURS, List.of(AM, PM), List.of(), List.of(alba), Map.of(), Set.of(),
                List.of(MON, MON.plusDays(1)), Priority.FAIRNESS);
        assertEquals(1, r.shifts().size());
        assertEquals(5, r.shifts().get(0).staffId());
        assertEquals(MON, r.shifts().get(0).date());
        assertEquals(780, r.shifts().get(0).endMin()); // 오후까지 넓히지 않는다
        assertEquals(3, r.unfilled().size());
    }

    @Test
    void partTimerWithoutAvailIsNotCandidate() {
        Result r = GapFiller.fill(HOURS, List.of(AM), List.of(), List.of(new Person(5, true, null, null)),
                Map.of(), Set.of(), List.of(MON), Priority.FAIRNESS);
        assertTrue(r.shifts().isEmpty());
    }

    @Test
    void neverSevenDaysInAWeek() {
        // 짧은 파트(1시간)라 40시간 한도에 안 걸려도 7일째는 넣지 않는다.
        PartNeed shortPart = new PartNeed("짧게", 540, 600, 1);
        List<LocalDate> week = java.util.stream.IntStream.range(0, 7).mapToObj(MON::plusDays).toList();
        Result r = run(List.of(shortPart), List.of(), List.of(1), week);
        assertEquals(6, r.shifts().size());
        assertEquals(MON.plusDays(6), r.unfilled().get(0).date());
    }

    @Test
    void fixedDaysCountTowardWeeklyRest() {
        // 고정 근무 6일(월~토)인 사람은 일요일 빈칸 후보가 아니다.
        List<Planned> fixed = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new Planned(1, MON.plusDays(i), 1080, 1140, "저녁", 0)).toList();
        Result r = run(List.of(AM), fixed, List.of(1, 2), List.of(MON.plusDays(6)));
        assertEquals(2, r.shifts().get(0).staffId());
    }

    private static Result runMinor(PartNeed part, List<Planned> existing) {
        Person minor = new Person(5, true, null, null, java.util.Collections.nCopies(7, new WorkHours(0, 1440)), true);
        return GapFiller.fill(new StoreHours(0, 1440, 0, 1440), List.of(part), existing, List.of(minor),
                Map.of(), Set.of(), List.of(MON.plusDays(4)), Priority.FAIRNESS);
    }

    @Test
    void minorLimits() {
        assertEquals(1, runMinor(AM, List.of()).shifts().size());                                // 4시간 OK
        assertTrue(runMinor(new PartNeed("길게", 540, 1080, 1), List.of()).shifts().isEmpty());     // 실근로 480 > 420
        assertTrue(runMinor(new PartNeed("밤", 1200, 1380, 1), List.of()).shifts().isEmpty());     // 22시 넘김
        assertTrue(runMinor(new PartNeed("새벽", 300, 540, 1), List.of()).shifts().isEmpty());     // 06시 전
        // 월~목 4일 각 500분 = 2000 + 210 > 2100(성인 2400 이면 들어감)
        List<Planned> week = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new Planned(5, MON.plusDays(i), 540, 1070, "오전", 30)).toList();
        assertTrue(runMinor(AM, week).shifts().isEmpty());
    }
}
