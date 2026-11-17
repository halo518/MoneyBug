package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 생성된 근무표의 인건비·주휴수당 요약과 사장님용 문장. */
public final class ScheduleInsights {
    private static final int HOLIDAY_MIN = 900;
    private static final int HOLIDAY_CAP = 2400;

    public record StaffInfo(int id, String name, boolean partTimer, int wage) {}
    public record Summary(long laborCost, int holidayEligible, long holidayPay, List<String> lines) {}

    private ScheduleInsights() {}

    public static Summary summarize(YearMonth month, List<FixedShiftPlanner.Planned> shifts, List<StaffInfo> staff,
                                    List<GapFiller.Gap> unfilled, MonthSchedule.HolidayPolicy policy,
                                    MonthSchedule.Priority priority) {
        // 직원별 → 주(월요일) → 실근로 분
        Map<Integer, Map<LocalDate, Integer>> weekly = new HashMap<>();
        Map<Integer, Long> totalMin = new HashMap<>();
        for (FixedShiftPlanner.Planned p : shifts) {
            int m = Math.max(0, p.endMin() - p.startMin() - p.breakMin());
            LocalDate monday = p.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weekly.computeIfAbsent(p.staffId(), k -> new HashMap<>()).merge(monday, m, Integer::sum);
            totalMin.merge(p.staffId(), (long) m, Long::sum);
        }
        Map<Integer, StaffInfo> byId = new HashMap<>();
        for (StaffInfo s : staff) byId.put(s.id(), s);

        long wagePart = 0, holidayPay = 0;
        Set<Integer> eligible = new HashSet<>();
        record Tip(String text, long saved) {}
        List<Tip> tips = new ArrayList<>();
        for (var e : totalMin.entrySet()) {
            StaffInfo s = byId.get(e.getKey());
            int wage = s == null ? 0 : s.wage();
            wagePart += Math.round(e.getValue() * wage / 60.0);
        }
        for (var e : weekly.entrySet()) {
            StaffInfo s = byId.get(e.getKey());
            int wage = s == null ? 0 : s.wage();
            for (var w : e.getValue().entrySet()) {
                int m = w.getValue();
                if (m < HOLIDAY_MIN || !month.equals(YearMonth.from(w.getKey().plusDays(6)))) continue;
                long pay = holiday(m, wage);
                holidayPay += pay;
                eligible.add(e.getKey());
                if (s != null && s.partTimer() && m <= 1080) {
                    int cut = (int) Math.ceil((m - HOLIDAY_MIN + 1) / 30.0) * 30;
                    LocalDate d = w.getKey();
                    tips.add(new Tip(s.name() + "님 " + d.getMonthValue() + "/" + d.getDayOfMonth() + "주 주 " + hours(m)
                            + "시간 → " + hours(cut) + "시간 줄이면 주휴수당 약 " + won(pay) + "원 아낄 수 있어요", pay));
                }
            }
        }
        long cost = wagePart + holidayPay;

        List<String> lines = new ArrayList<>();
        lines.add("예상 인건비 약 " + won(cost) + "원 (주휴수당 " + won(holidayPay) + "원 포함)");
        lines.add(eligible.isEmpty() ? "주휴수당 대상 없음" : "주휴수당 대상 " + eligible.size() + "명");
        lines.add(priority == MonthSchedule.Priority.COST
                ? "빈 시간은 시급이 낮은 직원부터 채웠어요" : "빈 시간은 이번 주 근무가 적은 직원부터 채웠어요");
        if (policy == MonthSchedule.HolidayPolicy.MINIMIZE) {
            if (tips.isEmpty()) {
                lines.add("주 15시간 근처인 알바생이 없어 주휴수당을 더 줄일 여지는 없어요");
            } else {
                tips.sort(Comparator.comparingLong(Tip::saved).reversed());
                tips.stream().limit(5).forEach(t -> lines.add(t.text()));
            }
        }
        if (unfilled != null && !unfilled.isEmpty()) {
            int missing = unfilled.stream().mapToInt(GapFiller.Gap::missing).sum();
            StringBuilder sb = new StringBuilder("직원이 부족해 " + missing + "칸을 못 채웠어요:");
            unfilled.stream().limit(3).forEach(g -> sb.append(' ').append(g.date().getMonthValue()).append('/')
                    .append(g.date().getDayOfMonth()).append(' ').append(g.part()));
            if (unfilled.size() > 3) sb.append(" 외 ").append(unfilled.size() - 3).append('칸');
            lines.add(sb.toString());
        }
        return new Summary(cost, eligible.size(), holidayPay, lines);
    }

    private static long holiday(int weekMin, int wage) {
        return Math.round(Math.min(weekMin, HOLIDAY_CAP) / (double) HOLIDAY_CAP * 8 * wage);
    }

    private static String hours(int min) {
        return min % 60 == 0 ? String.valueOf(min / 60) : (min / 60.0 + "");
    }

    private static String won(long v) {
        return String.format("%,d", v);
    }
}
