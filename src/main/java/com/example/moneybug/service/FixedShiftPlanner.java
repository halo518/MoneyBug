package com.example.moneybug.service;

import com.example.moneybug.util.Holidays;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 고정 근무 계산(DB 없는 순수 로직). 레퍼런스 rules.ts fixedShifts + autofill.ts.
 * 알바생은 그 요일에 고른 알바 파트마다 파트 시간·휴게로, 직원은 그 요일 고정 직원 파트(파트 시간 그대로)나
 * workHours 로 근무를 만든다.
 */
public final class FixedShiftPlanner {

    /** 연소자: 06:00~22:00, 하루 7시간. GapFiller 와 같은 기준. */
    static final int MINOR_FROM = 360, MINOR_TO = 1320, MINOR_DAY_MIN = 420;

    private FixedShiftPlanner() {}

    /** 영업시간(분). 마감이 1440 을 넘으면 익일. */
    public record StoreHours(int openMin, int closeMin, int weekendOpenMin, int weekendCloseMin) {
        public int open(LocalDate d) { return Holidays.isWeekendOrHoliday(d) ? weekendOpenMin : openMin; }
        public int close(LocalDate d) { return Holidays.isWeekendOrHoliday(d) ? weekendCloseMin : closeMin; }
    }

    /** flex = 직원 파트(직원 고정 파트로만 쓰고 알바생에게는 안 줌). */
    public record PartDef(String name, int startMin, int endMin, int breakMin, boolean flex) {
        public PartDef(String name, int startMin, int endMin, int breakMin) {
            this(name, startMin, endMin, breakMin, false);
        }
    }

    public record WorkHours(int startMin, int endMin) {}

    /**
     * partTimer=true 면 알바생(fixedParts 사용), 아니면 직원(workHours 사용). 리스트는 월~일 7칸, 없으면 null.
     * availHours: 알바생이 고정 파트 밖에 추가로 일할 수 있는 요일별 시간(빈칸 채우기 후보 범위).
     * minor: 연소 근로자(1일 7시간·주 35시간, 22:00~06:00 금지).
     */
    public record Person(int id, boolean partTimer, List<List<String>> fixedParts, List<WorkHours> workHours,
                         List<WorkHours> availHours, boolean minor) {
        public Person(int id, boolean partTimer, List<List<String>> fixedParts, List<WorkHours> workHours) {
            this(id, partTimer, fixedParts, workHours, null, false);
        }

        public Person(int id, boolean partTimer, List<List<String>> fixedParts, List<WorkHours> workHours,
                      List<WorkHours> availHours) {
            this(id, partTimer, fixedParts, workHours, availHours, false);
        }

        /** 고정 파트·고정 시간이 하루도 없는 직원: 하루 종일 빈칸 후보(직원 파트 순환 대상). */
        public boolean flex() {
            return !partTimer && (workHours == null || workHours.stream().allMatch(w -> w == null))
                    && (fixedParts == null || fixedParts.stream().allMatch(d -> d == null || d.isEmpty()));
        }

        /** 그 날 [s,e] 근무를 맡을 수 있는 시간인지(flex 직원은 항상, 알바생은 availHours 안일 때). */
        public boolean availableFor(LocalDate date, int s, int e) {
            if (flex()) return true;
            if (!partTimer) return false;
            WorkHours w = at(availHours, date.getDayOfWeek().getValue() - 1);
            return w != null && w.startMin() <= s && w.endMin() >= e;
        }
    }

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
                    int dayWork = 0; // 연소자 하루 7시간 판단용
                    for (PartDef part : sorted) {
                        if (part.flex() || !names.contains(part.name())) continue;
                        // 그날 영업시간으로 자른다. 잘렸으면 휴게도 잘린 길이로 다시 정한다.
                        int s = Math.max(part.startMin(), hours.open(date)), e = Math.min(part.endMin(), hours.close(date));
                        if (e <= s) {
                            // 새벽 파트: 전날 야간 영업(마감 > 1440)의 이어지는 구간과 다시 자른다.
                            LocalDate prev = date.minusDays(1);
                            s = Math.max(part.startMin(), hours.open(prev) - 1440);
                            e = Math.min(part.endMin(), hours.close(prev) - 1440);
                        }
                        if (e <= s) continue;
                        if (p.minor()) {
                            // 연소자(근로기준법 제69·70조): 06:00~22:00 안으로, 하루 실근로 7시간까지 자른다.
                            s = Math.max(s, MINOR_FROM);
                            e = Math.min(e, MINOR_TO);
                            if (e <= s) continue;
                            int left = MINOR_DAY_MIN - dayWork;
                            if (left <= 0) continue;
                            boolean same = s == part.startMin() && e == part.endMin();
                            if (e - s - (same ? part.breakMin() : minBreakFor(e - s)) > left) {
                                e = s + left + requiredBreak(left);
                            }
                        }
                        boolean cut = s != part.startMin() || e != part.endMin();
                        int brk = cut ? minBreakFor(e - s) : part.breakMin();
                        dayWork += e - s - brk;
                        out.add(new Planned(p.id(), date, s, e, part.name(), brk));
                    }
                } else {
                    // 직원 고정 파트: 파트 시간·휴게 그대로(영업시간으로 안 자름), 파트 이름으로. days 는 안 본다.
                    List<String> names = at(p.fixedParts(), dow);
                    PartDef fixed = names == null ? null : sorted.stream()
                            .filter(x -> x.flex() && names.contains(x.name()) && x.endMin() > x.startMin())
                            .findFirst().orElse(null);
                    if (fixed != null) {
                        out.add(new Planned(p.id(), date, fixed.startMin(), fixed.endMin(), fixed.name(), fixed.breakMin()));
                        continue;
                    }
                    // 고정 시간은 영업시간 밖이어도 그대로 둔다.
                    WorkHours w = at(p.workHours(), dow);
                    if (w == null || w.endMin() <= w.startMin()) continue;
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
        java.util.Set<Integer> employees = new java.util.HashSet<>();
        people.stream().filter(p -> !p.partTimer()).forEach(p -> employees.add(p.id()));
        return fixedShifts(hours, parts, people, dates).stream()
                .filter(f -> existing.stream().noneMatch(e -> e.staffId() == f.staffId() && e.date().equals(f.date())
                        && (e.offDay() || employees.contains(f.staffId()) || f.part().equals(e.part()))))
                .toList();
    }

    private static <T> T at(List<T> week, int dow) {
        return week != null && dow < week.size() ? week.get(dow) : null;
    }
}
