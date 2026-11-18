package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule.HolidayPolicy;
import com.example.moneybug.entity.MonthSchedule.Priority;
import com.example.moneybug.service.ScheduleInsights.StaffInfo;
import com.example.moneybug.service.ScheduleInsights.Summary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleInsightsTest {
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    /** 월~: days 일 동안 매일 hours 시간(휴게 0). */
    private static List<FixedShiftPlanner.Planned> run(int id, LocalDate from, int days, int hours) {
        List<FixedShiftPlanner.Planned> l = new ArrayList<>();
        for (int i = 0; i < days; i++) l.add(new FixedShiftPlanner.Planned(id, from.plusDays(i), 600, 600 + hours * 60, "오픈", 0));
        return l;
    }

    @Test
    void costAndHoliday() {
        // 11/2(월)~11/6: 5일 × 4h = 20h = 1200분, 시급 10000
        var s = ScheduleInsights.summarize(NOV, run(1, LocalDate.of(2026, 11, 2), 5, 4),
                List.of(new StaffInfo(1, "가", true, 10000)), List.of(), HolidayPolicy.ALLOW, Priority.FAIRNESS);
        long hp = Math.round(1200 / 2400.0 * 8 * 10000); // 40000
        assertEquals(200000 + hp, s.laborCost());
        assertEquals(1, s.holidayEligible());
        assertEquals(hp, s.holidayPay());
        assertEquals("예상 인건비 약 240,000원 (주휴수당 40,000원 포함)", s.lines().get(0));
        assertEquals("주휴수당 대상 1명", s.lines().get(1));
        assertEquals("빈 시간은 이번 주 근무가 적은 직원부터 채웠어요", s.lines().get(2));
        assertEquals(3, s.lines().size()); // ALLOW → 4번 줄 없음
    }

    @Test
    void weekCrossingMonthEndCountsOnlyIfSundayInMonth() {
        // 10/26(월)~11/1(일) 주: 일요일 11/1 → 11월 대상. 11/23~11/29 일요일 11/29 → 대상. 11/30(월) 주 일요일 12/6 → 제외.
        var sh = new ArrayList<>(run(1, LocalDate.of(2026, 10, 26), 5, 4)); // 10/26~30
        sh.addAll(run(1, LocalDate.of(2026, 11, 30), 5, 4));
        var s = ScheduleInsights.summarize(NOV, sh, List.of(new StaffInfo(1, "가", true, 10000)),
                List.of(), HolidayPolicy.ALLOW, Priority.COST);
        assertEquals(1, s.holidayEligible());
        assertEquals(40000, s.holidayPay());
        assertEquals("빈 시간은 시급이 낮은 직원부터 채웠어요", s.lines().get(2));
    }

    @Test
    void noHolidayBelow900() {
        var s = ScheduleInsights.summarize(NOV, run(1, LocalDate.of(2026, 11, 2), 3, 4),
                List.of(new StaffInfo(1, "가", true, 10000)), List.of(), HolidayPolicy.ALLOW, Priority.FAIRNESS);
        assertEquals(0, s.holidayEligible());
        assertEquals("주휴수당 대상 없음", s.lines().get(1));
    }

    @Test
    void minimizeTipShowsHoursToCut() {
        // 16.5h = 990분 → 899 미만까지 91분 → 120분 = 2시간 줄이면 됨
        var sh = new ArrayList<>(run(1, LocalDate.of(2026, 11, 2), 3, 5)); // 900분
        sh.add(new FixedShiftPlanner.Planned(1, LocalDate.of(2026, 11, 6), 600, 690, "오픈", 0)); // +90 = 990
        var s = ScheduleInsights.summarize(NOV, sh, List.of(new StaffInfo(1, "가", true, 10000)),
                List.of(), HolidayPolicy.MINIMIZE, Priority.FAIRNESS);
        long pay = Math.round(990 / 2400.0 * 8 * 10000);
        assertEquals("가님 11/2주 주 16.5시간 → 2시간 줄이면 주휴수당 약 " + String.format("%,d", pay) + "원 아낄 수 있어요", s.lines().get(3));
    }

    @Test
    void minimizeHalfHourCut() {
        // 900분 → 30분(0.5) 줄이면 899
        var s = ScheduleInsights.summarize(NOV, run(1, LocalDate.of(2026, 11, 2), 3, 5),
                List.of(new StaffInfo(1, "가", true, 10000)), List.of(), HolidayPolicy.MINIMIZE, Priority.FAIRNESS);
        assertTrue(s.lines().get(3).contains("주 15시간 → 0.5시간 줄이면"), s.lines().get(3));
    }

    @Test
    void minimizeNoCandidates() {
        // 직원(알바 아님)은 대상 아님, 알바 20h 도 범위 밖
        var s = ScheduleInsights.summarize(NOV, run(1, LocalDate.of(2026, 11, 2), 5, 4),
                List.of(new StaffInfo(1, "가", true, 10000)), List.of(), HolidayPolicy.MINIMIZE, Priority.FAIRNESS);
        assertEquals("주 15시간 근처인 알바생이 없어 주휴수당을 더 줄일 여지는 없어요", s.lines().get(3));
    }

    @Test
    void unfilledLine() {
        var gaps = List.of(
                new GapFiller.Gap(LocalDate.of(2026, 11, 3), "오픈", 0, 60, 2),
                new GapFiller.Gap(LocalDate.of(2026, 11, 4), "마감", 0, 60, 1),
                new GapFiller.Gap(LocalDate.of(2026, 11, 5), "오픈", 0, 60, 1),
                new GapFiller.Gap(LocalDate.of(2026, 11, 6), "오픈", 0, 60, 1));
        var s = ScheduleInsights.summarize(NOV, List.of(), List.of(), gaps, HolidayPolicy.ALLOW, Priority.FAIRNESS);
        assertEquals("직원이 부족해 5칸을 못 채웠어요: 11/3 오픈 11/4 마감 11/5 오픈 외 1칸", s.lines().get(3));
    }

    @Test
    void minorLimitsWarned() {
        // 연소자 하루 8시간(휴게 60)×5일 = 주 40시간 → 하루·주 경고 둘 다.
        var shifts = new java.util.ArrayList<FixedShiftPlanner.Planned>();
        for (int i = 0; i < 5; i++) shifts.add(new FixedShiftPlanner.Planned(1, LocalDate.of(2026, 11, 2).plusDays(i), 540, 1080, "종일", 60));
        var s = ScheduleInsights.summarize(NOV, shifts, List.of(new StaffInfo(1, "가", true, 10000, true)), List.of(),
                HolidayPolicy.ALLOW, Priority.FAIRNESS);
        assertTrue(s.lines().contains("가님은 연소자라 22시~6시 근무와 하루 7시간 넘는 근무를 할 수 없어요"), s.lines().toString());
        assertTrue(s.lines().contains("가님 11/2주 40시간이에요. 연소자는 주 35시간까지예요"), s.lines().toString());
        var adult = ScheduleInsights.summarize(NOV, shifts, List.of(new StaffInfo(1, "가", true, 10000)), List.of(),
                HolidayPolicy.ALLOW, Priority.FAIRNESS);
        assertTrue(adult.lines().stream().noneMatch(l -> l.contains("연소자")));
    }
}
