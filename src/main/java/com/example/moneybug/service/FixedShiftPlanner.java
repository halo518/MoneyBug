package com.example.moneybug.service;

import com.example.moneybug.util.Holidays;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 고정 근무 계산(DB 없는 순수 로직). 레퍼런스 rules.ts fixedShifts + autofill.ts.
 * 알바생은 그 요일에 고른 파트마다 파트 시간·휴게로, 직원은 요일별 workHours 로 근무를 만든다.
 */
public final class FixedShiftPlanner {

    private FixedShiftPlanner() {}

    /** 영업시간(분). 마감이 1440 을 넘으면 익일. */
    public record StoreHours(int openMin, int closeMin, int weekendOpenMin, int weekendCloseMin) {
        public int open(LocalDate d) { return Holidays.isWeekendOrHoliday(d) ? weekendOpenMin : openMin; }
        public int close(LocalDate d) { return Holidays.isWeekendOrHoliday(d) ? weekendCloseMin : closeMin; }
    }

    public record PartDef(String name, int startMin, int endMin, int breakMin) {}

    public record WorkHours(int startMin, int endMin) {}

    /** partTimer=true 면 알바생(fixedParts 사용), 아니면 직원(workHours 사용). 리스트는 월~일 7칸, 없으면 null. */
    public record Person(int id, boolean partTimer, List<List<String>> fixedParts, List<WorkHours> workHours) {}

    /** part == null 이면 직원 유동 근무. */
    public record Planned(int staffId, LocalDate date, int startMin, int endMin, String part, int breakMin) {}

    /** 이미 있는 근무 한 칸. part 는 DB 문자열 그대로. */
    public record Existing(int staffId, LocalDate date, String part, boolean offDay) {}

    /** 근로기준법 제54조: 4시간 이상 30분, 8시간 이상 1시간. */
    public static int requiredBreak(int workMin) {
        return workMin >= 480 ? 60 : workMin >= 240 ? 30 : 0;
    }

    /** 체류 시간에 필요한 최소 휴게. */
    public static int minBreakFor(int duration) {
        for (int b : new int[]{0, 30, 60}) {
            if (b >= requiredBreak(duration - b)) return b;
        }
        return 60;
    }

    public static List<Planned> fixedShifts(StoreHours hours, List<PartDef> parts, List<Person> people,
                                            List<LocalDate> dates) {
        List<Planned> out = new ArrayList<>();
        List<PartDef> sorted = parts.stream().sorted(Comparator.comparingInt(PartDef::startMin)).toList();
        for (Person p : people) {
            for (LocalDate date : dates) {
                int dow = date.getDayOfWeek().getValue() - 1;
                if (p.partTimer()) {
                    List<String> names = at(p.fixedParts(), dow);
                    if (names == null) continue;
                    for (PartDef part : sorted) {
                        if (names.contains(part.name())) {
                            out.add(new Planned(p.id(), date, part.startMin(), part.endMin(), part.name(), part.breakMin()));
                        }
                    }
                } else {
                    WorkHours w = at(p.workHours(), dow);
                    if (w == null || w.startMin() < hours.open(date) || w.endMin() > hours.close(date)
                            || w.endMin() <= w.startMin()) continue;
                    out.add(new Planned(p.id(), date, w.startMin(), w.endMin(), null,
                            minBreakFor(w.endMin() - w.startMin())));
                }
            }
        }
        out.sort(Comparator.comparing(Planned::date).thenComparingInt(Planned::startMin)
                .thenComparingInt(Planned::endMin));
        return out;
    }

    /** 이미 있는 근무는 두고 빠진 고정 근무만 돌려준다. 연차·휴무인 날, 같은 파트(알바)·아무 근무(직원)가 있으면 건너뜀. */
    public static List<Planned> autofill(StoreHours hours, List<PartDef> parts, List<Person> people,
                                         List<LocalDate> dates, List<Existing> existing) {
        return fixedShifts(hours, parts, people, dates).stream()
                .filter(f -> existing.stream().noneMatch(e -> e.staffId() == f.staffId() && e.date().equals(f.date())
                        && (e.offDay() || f.part() == null || f.part().equals(e.part()))))
                .toList();
    }

    private static <T> T at(List<T> week, int dow) {
        return week != null && dow < week.size() ? week.get(dow) : null;
    }
}
