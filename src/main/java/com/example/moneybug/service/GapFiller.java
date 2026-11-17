package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파트별 필요 인원 부족분을 메운다(DB 없는 순수 로직). 후보 = 고정 시간 없는 직원(flex) + 그 시간이
 * 추가 근무 가능 시간(availHours) 안인 알바생. 주 1회 휴일: 그 주에 이미 6일 일한 사람은 넣지 않는다.
 */
public final class GapFiller {

    private static final int WEEK_CAP_MIN = 2400;
    private static final int MERGE_CAP_MIN = 600;
    private static final int WEEK_DAYS_CAP = 6;
    private static final int MINOR_DAY_MIN = 420;
    private static final int MINOR_WEEK_MIN = 2100;

    private GapFiller() {}

    public record PartNeed(String name, int startMin, int endMin, int need) {}

    /** 못 채운 칸. missing = 모자란 인원 수. */
    public record Gap(LocalDate date, String part, int startMin, int endMin, int missing) {}

    public record Result(List<Planned> shifts, List<Gap> unfilled) {}

    public static Result fill(StoreHours hours, List<PartNeed> parts, List<Planned> existing,
                              List<Person> people, Map<Integer, Integer> wages, Set<String> off,
                              List<LocalDate> dates, MonthSchedule.Priority priority) {
        List<Planned> all = new ArrayList<>(existing);
        List<Planned> created = new ArrayList<>();
        List<Gap> unfilled = new ArrayList<>();
        List<Person> flex = people.stream().sorted(Comparator.comparingInt(Person::id)).toList();
        List<PartNeed> byStart = parts.stream().sorted(Comparator.comparingInt(PartNeed::startMin)
                .thenComparingInt(PartNeed::endMin).thenComparing(PartNeed::name)).toList();

        for (LocalDate date : dates.stream().sorted().distinct().toList()) {
            int open = hours.open(date), close = hours.close(date);
            for (PartNeed p : byStart) {
                int s = Math.max(p.startMin(), open), e = Math.min(p.endMin(), close);
                if (e <= s) continue;
                while (missing(all, date, p, s, e) > 0) {
                    int cs = s, ce = e;
                    Planned pick = pick(flex, all, wages, off, date, cs, ce, priority);
                    if (pick == null) {
                        unfilled.add(new Gap(date, p.name(), s, e, missing(all, date, p, s, e)));
                        break;
                    }
                    // 뒤의 부족 파트가 겹치거나 맞닿으면 한 근무로 넓힌다.
                    for (PartNeed q : byStart) {
                        int qs = Math.max(q.startMin(), open), qe = Math.min(q.endMin(), close);
                        if (q == p || qe <= qs || qs < s || missing(all, date, q, qs, qe) <= 0) continue;
                        int ns = Math.min(cs, qs), ne = Math.max(ce, qe);
                        if (qs > ce || ne - ns > MERGE_CAP_MIN) continue;
                        Planned wide = pick(flex, all, wages, off, date, ns, ne, priority);
                        if (wide != null && wide.staffId() == pick.staffId()) {
                            cs = ns;
                            ce = ne;
                            pick = wide;
                        }
                    }
                    all.add(pick);
                    created.add(pick);
                }
            }
        }
        created.sort(Comparator.comparing(Planned::date).thenComparingInt(Planned::startMin)
                .thenComparingInt(Planned::endMin).thenComparingInt(Planned::staffId));
        return new Result(created, unfilled);
    }

    private static int missing(List<Planned> all, LocalDate date, PartNeed p, int s, int e) {
        long have = all.stream().filter(x -> x.date().equals(date)
                && (p.name().equals(x.part()) || (x.part() == null && x.startMin() <= s && x.endMin() >= e))).count();
        return (int) Math.max(0, p.need() - have);
    }

    /** [s,e] 근무를 맡을 직원을 골라 근무를 돌려준다. 없으면 null. */
    private static Planned pick(List<Person> flex, List<Planned> all, Map<Integer, Integer> wages, Set<String> off,
                                LocalDate date, int s, int e, MonthSchedule.Priority priority) {
        int brk = FixedShiftPlanner.minBreakFor(e - s);
        int work = e - s - brk;
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        YearMonth ym = YearMonth.from(date);
        int bestId = -1;
        long[] best = null;
        for (Person person : flex) {
            int id = person.id();
            if (!person.availableFor(date, s, e) || off.contains(id + "|" + date)) continue;
            // 연소자: 1일 7시간, 22:00~06:00 금지(s 는 0~1439, e 는 다음 날까지 갈 수 있음).
            if (person.minor() && (work > MINOR_DAY_MIN || s < 360 || e > 1320)) continue;
            if (all.stream().anyMatch(x -> x.staffId() == id && x.date().equals(date))) continue;
            long days = all.stream().filter(x -> x.staffId() == id
                    && x.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(monday))
                    .map(Planned::date).distinct().count();
            if (days >= WEEK_DAYS_CAP) continue;
            long week = sum(all, id, d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(monday));
            if (week + work > (person.minor() ? MINOR_WEEK_MIN : WEEK_CAP_MIN)) continue;
            long month = sum(all, id, d -> YearMonth.from(d).equals(ym));
            long wage = priority == MonthSchedule.Priority.COST ? wages.getOrDefault(id, 0) : 0;
            long[] key = {wage, week, month, id};
            if (best == null || java.util.Arrays.compare(key, best) < 0) {
                best = key;
                bestId = id;
            }
        }
        return best == null ? null : new Planned(bestId, date, s, e, null, brk);
    }

    private static long sum(List<Planned> all, int id, java.util.function.Predicate<LocalDate> in) {
        return all.stream().filter(x -> x.staffId() == id && in.test(x.date()))
                .mapToLong(x -> x.endMin() - x.startMin() - x.breakMin()).sum();
    }
}
