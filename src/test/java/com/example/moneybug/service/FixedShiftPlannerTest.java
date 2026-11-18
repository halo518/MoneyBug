package com.example.moneybug.service;

import com.example.moneybug.service.FixedShiftPlanner.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FixedShiftPlannerTest {

    // 2026-11-02 = 월
    static final LocalDate MON = LocalDate.of(2026, 11, 2);
    static final StoreHours HOURS = new StoreHours(480, 1320, 540, 1260);
    static final List<PartDef> PARTS = List.of(new PartDef("마감", 1020, 1320, 30), new PartDef("오픈", 480, 720, 30));

    static List<List<String>> week(String... perDay) {
        List<List<String>> w = new ArrayList<>();
        for (int i = 0; i < 7; i++) w.add(i < perDay.length && !perDay[i].isEmpty() ? List.of(perDay[i].split(",")) : List.of());
        return w;
    }

    static List<LocalDate> days(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(MON::plusDays).toList();
    }

    @Test
    void partTimerGetsPartsOfThatWeekday() {
        Person p = new Person(1, true, week("오픈,마감", "", "마감"), null);
        List<Planned> out = FixedShiftPlanner.fixedShifts(HOURS, PARTS, List.of(p), days(7));
        assertEquals(3, out.size());
        assertEquals("오픈", out.get(0).part());
        assertEquals(480, out.get(0).startMin());
        assertEquals(30, out.get(0).breakMin());
        assertEquals("마감", out.get(1).part());
        assertEquals(MON.plusDays(2), out.get(2).date());
    }

    @Test
    void staffUsesWorkHoursEvenOutsideBusinessHours() {
        List<WorkHours> wh = new ArrayList<>();
        wh.add(new WorkHours(540, 1080));          // 월 9~18 ok
        wh.add(new WorkHours(420, 900));           // 화 7시 시작 → 영업 전이어도 그대로
        wh.add(null);                              // 수 쉼
        wh.add(new WorkHours(480, 1380));          // 목 23시 종료 → 영업 후여도 그대로
        wh.add(null); wh.add(new WorkHours(540, 1260)); wh.add(null); // 토 주말 영업시간 9~21 ok
        Person p = new Person(2, false, null, wh);
        List<Planned> out = FixedShiftPlanner.fixedShifts(HOURS, PARTS, List.of(p), days(7));
        assertEquals(4, out.size());
        assertNull(out.get(0).part());
        assertEquals(60, out.get(0).breakMin());   // 9시간 체류 → 60
        assertEquals(420, out.get(1).startMin());
        assertEquals(1380, out.get(2).endMin());
        assertEquals(MON.plusDays(5), out.get(3).date());
    }

    @Test
    void minBreak() {
        assertEquals(0, FixedShiftPlanner.minBreakFor(230));
        assertEquals(30, FixedShiftPlanner.minBreakFor(270));
        assertEquals(60, FixedShiftPlanner.minBreakFor(540));
        assertEquals(30, FixedShiftPlanner.minBreakFor(450));
    }

    @Test
    void autofillSkipsExistingPartLeaveAndAnyForStaff() {
        Person pt = new Person(1, true, week("오픈,마감"), null);
        List<WorkHours> wh = new ArrayList<>();
        for (int i = 0; i < 7; i++) wh.add(new WorkHours(540, 1080));
        Person st = new Person(2, false, null, wh);
        List<Existing> ex = List.of(
                new Existing(1, MON, "오픈", false),      // 같은 파트 있음 → 오픈만 건너뜀
                new Existing(2, MON, "직원", false),      // 직원 근무 있음
                new Existing(2, MON.plusDays(1), null, true)); // 연차
        List<Planned> out = FixedShiftPlanner.autofill(HOURS, PARTS, List.of(pt, st), days(3), ex);
        assertEquals(1, out.stream().filter(p -> p.staffId() == 1).count());
        assertEquals("마감", out.stream().filter(p -> p.staffId() == 1).findFirst().orElseThrow().part());
        assertEquals(List.of(MON.plusDays(2)), out.stream().filter(p -> p.staffId() == 2).map(Planned::date).toList());
    }

    @Test
    void partTimerPartCutToStoreHours() {
        // 주말 영업 09~21 (540~1260): 오픈 08~12 → 09~12(잘림, 휴게 0), 마감 17~22 → 17~21(잘림, 휴게 30).
        // 평일은 그대로 파트 휴게 30.
        Person p = new Person(1, true, week("오픈", "", "", "", "", "오픈,마감"), null);
        List<Planned> out = FixedShiftPlanner.fixedShifts(HOURS, PARTS, List.of(p), days(7));
        assertEquals(3, out.size());
        assertEquals(480, out.get(0).startMin());
        assertEquals(30, out.get(0).breakMin());
        Planned open = out.get(1), close = out.get(2);
        assertEquals(540, open.startMin());
        assertEquals(720, open.endMin());
        assertEquals(0, open.breakMin());
        assertEquals(1020, close.startMin());
        assertEquals(1260, close.endMin());
        assertEquals(30, close.breakMin());
    }

    @Test
    void partOutsideHoursDropped() {
        StoreHours shortDay = new StoreHours(720, 1000, 720, 1000);
        Person p = new Person(1, true, week("오픈,마감"), null);
        assertTrue(FixedShiftPlanner.fixedShifts(shortDay, PARTS, List.of(p), days(1)).isEmpty());
    }

    @Test
    void dawnPartUsesPreviousNightHours() {
        // 평일 18:00~익일 06:00, 주말 18:00~익일 04:00. 새벽 파트 00~06 은 전날 영업의 이어지는 구간으로 자른다.
        StoreHours night = new StoreHours(1080, 1800, 1080, 1680);
        Person p = new Person(1, true, week("새벽", "새벽"), null);
        List<Planned> out = FixedShiftPlanner.fixedShifts(night, List.of(new PartDef("새벽", 0, 360, 30)),
                List.of(p), days(2));
        assertEquals(2, out.size());
        Planned mon = out.get(0), tue = out.get(1); // 월: 전날(일) 마감 04:00 까지로 잘림, 화: 전날(월) 06:00 까지 그대로
        assertEquals(0, mon.startMin());
        assertEquals(240, mon.endMin());
        assertEquals(FixedShiftPlanner.minBreakFor(240), mon.breakMin());
        assertEquals(0, tue.startMin());
        assertEquals(360, tue.endMin());
        assertEquals(30, tue.breakMin());
    }

    @Test
    void minorFixedPartsKeepNightBanAndSevenHours() {
        // 연소자: 야간(22~02) 파트는 빠지고, 12시간 종일 파트는 하루 7시간으로 잘리고, 22시를 넘는 마감은 22시에 끝난다.
        var hours = new StoreHours(480, 1560, 480, 1560);
        var parts = List.of(new PartDef("종일", 540, 1320, 60), new PartDef("야간", 1320, 1560, 30),
                new PartDef("늦은마감", 1080, 1380, 30));
        Person minor = new Person(5, true, week("야간", "종일", "늦은마감", "종일,늦은마감"), null, null, true);
        List<Planned> out = FixedShiftPlanner.fixedShifts(hours, parts, List.of(minor), days(4));
        assertTrue(out.stream().noneMatch(p -> p.date().equals(MON)));
        Planned day = out.stream().filter(p -> p.date().equals(MON.plusDays(1))).findFirst().orElseThrow();
        assertEquals(540, day.startMin());
        assertEquals(420, day.endMin() - day.startMin() - day.breakMin());
        Planned late = out.stream().filter(p -> p.date().equals(MON.plusDays(2))).findFirst().orElseThrow();
        assertEquals(1320, late.endMin());
        // 같은 날 두 파트: 종일이 7시간을 다 써서 늦은마감은 빠진다.
        assertEquals(1, out.stream().filter(p -> p.date().equals(MON.plusDays(3))).count());
        // 연소자가 아니면 그대로.
        Person adult = new Person(6, true, week("야간"), null);
        assertEquals(1, FixedShiftPlanner.fixedShifts(hours, parts, List.of(adult), days(1)).size());
    }
}
