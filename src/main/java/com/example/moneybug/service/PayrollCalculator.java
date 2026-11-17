package com.example.moneybug.service;

import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 급여 규칙의 기준은 FE lib/data/payroll.dart.
 * 급여 계산 엔진 (설계서 §4.10 payslip 계산 규칙 1~8, payroll.dart 와 동일한 규칙).
 *
 * 1. 조회 범위: 그 달 1일이 속한 주의 월요일 ~ 그 달 말일이 속한 주의 일요일 (주휴 계산 때문에 앞뒤 주 포함)
 * 2. 항목: 연차(LEAVE)는 소정 시간만큼 유급(지난 연차 확정, 앞으로의 연차는 projected 일 때만),
 *    isWork 근무는 퇴근 기록이 있으면 실근무, 없으면 projected 이고 안 끝난 근무만 '예정'(planned)
 * 3. 실근무 분 = max(0, (퇴근 - max(출근, 소정시작)) - 휴게분)
 * 4. 기본급 = round(분 x 시급 / 60)
 * 5. 주휴수당: 그 주 대타 제외 근무가 15시간 이상이면 round(min(주시간,40)/40 x 8 x 시급), 그 주 일요일이 속한 달에 지급
 * 6. 가산수당(5인 이상 && premium): 야간(22~06시) 분과 하루 480분 초과분 각각 시급 x 0.5 (정수 나눗셈)
 * 7. 공제: BUSINESS_TAX 3.3%, FOUR_INSURANCE 9.4%(근사), NONE 0 — 모두 내림
 * 8. gross = base + weeklyHoliday + premium, net = gross - deduction
 */
@Component
@RequiredArgsConstructor
public class PayrollCalculator {

    private static final int WEEKLY_HOLIDAY_MIN_MINUTES = 15 * 60;
    private static final int WEEKLY_HOLIDAY_CAP_MINUTES = 40 * 60;
    private static final int DAILY_REGULAR_MINUTES = 480;

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;

    /** 계산 대상 한 건 (실근무, 연차, 예정 근무). */
    private record Item(Schedule schedule, LocalDateTime start, LocalDateTime end, int minutes,
                        boolean planned, boolean leave) {
        LocalDate date() {
            return schedule.getWorkDate();
        }
    }

    public PayslipDto.Response calculate(Store store, StoreWorker sw, YearMonth ym, boolean projected,
                                         LocalDateTime now) {
        int wage = sw.getHourlyWage();
        LocalDate rangeStart = TimeUtil.monday(ym.atDay(1));
        LocalDate rangeEndExclusive = TimeUtil.monday(ym.atEndOfMonth()).plusDays(7);

        List<Schedule> shifts = scheduleRepository.findByWorkerBetween(sw.getId(), rangeStart,
                rangeEndExclusive.minusDays(1));
        Map<Integer, Attendance> attendance = shifts.isEmpty() ? Map.of()
                : attendanceRepository.findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSchedule().getId(), Function.identity()));

        List<Item> items = new ArrayList<>();
        int missingClockOut = 0;
        for (Schedule s : shifts) {
            if (s.getKind() == Schedule.Kind.LEAVE) {
                boolean past = TimeUtil.end(s).isBefore(now); // FE 와 같은 기준(오늘 이미 끝난 연차도 확정)
                if (past || projected) {
                    items.add(new Item(s, TimeUtil.start(s), TimeUtil.end(s), Math.max(0, s.scheduledMinutes()),
                            !past, true));
                }
                continue;
            }
            if (!s.isWork()) {
                continue; // 휴무·시간 미정: 0원
            }
            Attendance a = attendance.get(s.getId());
            if (a != null && a.getActualStart() != null && a.getActualEnd() != null) {
                LocalDateTime start = TimeUtil.start(s);
                LocalDateTime from = a.getActualStart().isAfter(start) ? a.getActualStart() : start;
                long minutes = Duration.between(from, a.getActualEnd()).toMinutes() - s.getBreakMin();
                items.add(new Item(s, from, a.getActualEnd(), (int) Math.max(0, minutes), false, false));
            } else if (a != null && a.getActualStart() != null && TimeUtil.end(s).isBefore(now)) {
                if (YearMonth.from(s.getWorkDate()).equals(ym)) missingClockOut++; // 출근만 찍고 퇴근 미기록: 0원 처리, 경고용
            } else if (projected && !TimeUtil.end(s).isBefore(now)) {
                items.add(new Item(s, TimeUtil.start(s), TimeUtil.end(s), Math.max(0, s.scheduledMinutes()),
                        true, false));
            }
        }

        // 해당 월에 귀속되는 항목 (근무일 기준)
        List<Item> inMonth = items.stream()
                .filter(i -> YearMonth.from(i.date()).equals(ym))
                .sorted(Comparator.comparing(Item::start))
                .toList();

        int minutes = inMonth.stream().mapToInt(Item::minutes).sum();
        int base = TimeUtil.pay(minutes, wage);
        int weeklyHoliday = weeklyHoliday(sw, items, ym, wage);
        int premium = premium(store, sw, inMonth, wage);
        int gross = base + weeklyHoliday + premium;
        int deduction = deduction(sw.getTaxType(), gross);

        List<PayslipDto.Entry> entries = inMonth.stream()
                .map(i -> new PayslipDto.Entry(i.date(), i.start(), i.end(), i.schedule().getBreakMin(),
                        i.schedule().isSubstitute(), i.planned(), i.minutes(),
                        i.leave() ? 0 : Math.min(nightMinutes(i.start(), i.end()), i.minutes())))
                .toList();

        return new PayslipDto.Response(sw.getId(), ym.getYear(), ym.getMonthValue(), minutes, base, weeklyHoliday,
                premium, gross, deduction, gross - deduction, projected, missingClockOut, entries);
    }

    /** 주휴수당: 대타 제외, 주 15시간 이상, 그 주 일요일이 속한 달에 지급. */
    private int weeklyHoliday(StoreWorker sw, List<Item> items, YearMonth ym, int wage) {
        if (!sw.isWeeklyHolidayAuto()) {
            return 0;
        }
        Map<LocalDate, Integer> minutesByWeek = new TreeMap<>();
        for (Item i : items) {
            if (!i.schedule().isSubstitute()) {
                minutesByWeek.merge(TimeUtil.monday(i.date()), i.minutes(), Integer::sum);
            }
        }
        int total = 0;
        for (Map.Entry<LocalDate, Integer> e : minutesByWeek.entrySet()) {
            LocalDate sunday = e.getKey().plusDays(6);
            int weekMinutes = e.getValue();
            if (YearMonth.from(sunday).equals(ym) && weekMinutes >= WEEKLY_HOLIDAY_MIN_MINUTES) {
                total += (int) Math.round(
                        Math.min(weekMinutes, WEEKLY_HOLIDAY_CAP_MINUTES) / (double) WEEKLY_HOLIDAY_CAP_MINUTES
                                * 8 * wage);
            }
        }
        return total;
    }

    /** 가산수당: 5인 이상 매장 && premium 직원만. 야간 분 x 0.5 + 하루 480분 초과분 x 0.5 (정수 나눗셈). 휴일가산 없음. */
    private int premium(Store store, StoreWorker sw, List<Item> inMonth, int wage) {
        if (store.isUnderFive() || !sw.isPremium()) {
            return 0;
        }
        int night = 0;
        Map<LocalDate, Integer> minutesByDay = new HashMap<>();
        for (Item i : inMonth) {
            if (i.leave()) continue; // 연차는 가산 대상이 아님
            // 휴게 시각을 모르므로 야간 분은 실근로 분(휴게 제외)을 넘지 않게 자른다. FE payroll.dart 와 같은 규칙.
            night += Math.min(nightMinutes(i.start(), i.end()), i.minutes());
            minutesByDay.merge(i.date(), i.minutes(), Integer::sum);
        }
        int over = minutesByDay.values().stream().mapToInt(m -> Math.max(0, m - DAILY_REGULAR_MINUTES)).sum();
        return TimeUtil.pay(night, wage) / 2 + TimeUtil.pay(over, wage) / 2;
    }

    /** 공제: 총액 기준 내림. 4대보험 요율 0.094 는 근사값(화면에 '예상' 표기). */
    static int deduction(StoreWorker.TaxType taxType, int gross) {
        if (taxType == null) return 0;
        return switch (taxType) {
            case BUSINESS_TAX -> (int) (gross * 33L / 1000);
            case FOUR_INSURANCE -> (int) (gross * 94L / 1000);
            case NONE -> 0;
        };
    }

    /** [start, end) 와 야간 시간대(22:00~익일 06:00)가 겹치는 분. */
    static int nightMinutes(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) return 0;
        long total = 0;
        for (LocalDate d = start.toLocalDate().minusDays(1); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
            // 전날 22시 ~ 당일 06시, 그리고 당일 22시 ~ 익일 06시 (경계 중복 없이 한 날짜의 22~30시 구간만 센다)
            total += overlap(start, end, d.atTime(22, 0), d.plusDays(1).atTime(6, 0));
        }
        return (int) total;
    }

    private static long overlap(LocalDateTime a1, LocalDateTime a2, LocalDateTime b1, LocalDateTime b2) {
        LocalDateTime from = a1.isAfter(b1) ? a1 : b1;
        LocalDateTime to = a2.isBefore(b2) ? a2 : b2;
        return to.isAfter(from) ? Duration.between(from, to).toMinutes() : 0;
    }
}
