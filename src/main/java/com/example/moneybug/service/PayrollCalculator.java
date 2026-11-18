package com.example.moneybug.service;

import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StaffWage;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StaffWageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 급여 규칙의 기준은 FE lib/data/payroll.dart computePayslip 과 app_store.dart payslip(). 규칙·반올림 위치까지 같게 둔다.
 *
 * 1. 조회 범위: 그 달 1일이 속한 주의 월요일 ~ 그 달 말일이 속한 주의 일요일 (주휴 계산 때문에 앞뒤 주 포함)
 * 2. 항목: 연차(LEAVE)는 소정 시간만큼 유급(지난 연차 확정, 앞으로의 연차는 projected 일 때만),
 *    isWork 근무는 퇴근 기록이 있으면 실근무, 없으면 projected 이고 안 끝난 근무만 '예정'(planned).
 *    항목의 날짜는 시작 시각의 날짜(FE WorkEntry.day).
 * 3. 실근무 분 = max(0, (퇴근 - max(출근, 소정시작)) - 휴게분)
 * 4. 시급은 날짜별 이력(staff_wage, FE wageOn). 기본급 = Σ 항목별 round(분 x 그날 시급 / 60)
 * 5. 주휴수당(주 월~일, 일요일이 속한 달에 지급): 소정 분(계약서 주 소정시간, 그 주 예정 근무가 없거나 계약서가 없으면
 *    그 주 대타 제외 근무 + 연차의 소정 분) ≥ 15h, 개근(끝난 대타 제외 근무에 출근 기록 있음), 계속근로(퇴사일 ≥ 일요일),
 *    확정(projected=false)이면 주가 끝났을 때만. round(min(소정,40h)/40 x 8 x 일요일 시급)
 * 6. 가산수당(5인 이상 && premium, 연차 제외): 항목별 야간 분(실근로 분 이하) x 0.5, 날짜별 480분 초과 x 0.5,
 *    주 40h 초과분 중 일별 8h 초과와 겹치지 않는 부분 x 0.5(일요일 시급, 일요일이 속한 달). 각각 round 뒤 정수 나눗셈.
 * 7. 공제: BUSINESS_TAX 3.3%, FOUR_INSURANCE 9.4%(근사), NONE 0 — 모두 내림
 * 8. gross = base + weeklyHoliday + premium, net = gross - deduction. 합은 long 으로 하고 int 범위로 포화(음수 없음).
 */
@Component
@RequiredArgsConstructor
public class PayrollCalculator {

    private static final int WEEKLY_HOLIDAY_MIN_MINUTES = 15 * 60;
    private static final int WEEKLY_HOLIDAY_CAP_MINUTES = 40 * 60;
    private static final int DAILY_REGULAR_MINUTES = 480;

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final StaffWageRepository staffWageRepository;
    private final ContractRepository contractRepository;

    /** 계산 대상 한 건 (실근무, 연차, 예정 근무). */
    private record Item(Schedule schedule, LocalDateTime start, LocalDateTime end, int minutes,
                        boolean planned, boolean leave) {
        LocalDate date() {
            return start.toLocalDate(); // FE WorkEntry.day = dayOf(start)
        }
    }

    public PayslipDto.Response calculate(Store store, StoreWorker sw, YearMonth ym, boolean projected,
                                         LocalDateTime now) {
        List<StaffWage> wageRows = sw.getId() == null ? List.of()
                : staffWageRepository.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(sw.getId());
        Function<LocalDate, Integer> wageOn = d -> StaffWageService.wageOn(wageRows, d, sw.getHourlyWage());
        Integer contractWeeklyHours = sw.getId() == null ? null
                : contractRepository.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                        .map(Contract::getWeeklyHours).orElse(null);
        LocalDate rangeStart = TimeUtil.monday(ym.atDay(1));
        LocalDate rangeEndExclusive = TimeUtil.monday(ym.atEndOfMonth()).plusDays(7);

        List<Schedule> shifts = scheduleRepository.findByWorkerBetween(sw.getId(), rangeStart,
                rangeEndExclusive.minusDays(1));
        Map<Integer, Attendance> attendance = shifts.isEmpty() ? Map.of()
                : attendanceRepository.findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSchedule().getId(), Function.identity()));

        List<Item> items = new ArrayList<>();
        // 주(월요일 키)별 주휴 근거: 예정 소정 분, 끝난 근무 결근 여부 (FE payslip() 와 같음)
        Map<LocalDate, Integer> stdMin = new HashMap<>();
        Set<LocalDate> absent = new HashSet<>();
        int missingClockOut = 0;
        for (Schedule s : shifts) {
            LocalDate monday = TimeUtil.monday(s.getWorkDate());
            Attendance a = attendance.get(s.getId());
            if (s.getKind() == Schedule.Kind.LEAVE) {
                stdMin.merge(monday, s.scheduledMinutes(), Integer::sum);
            } else if (s.isWork() && !s.isSubstitute()) {
                stdMin.merge(monday, s.scheduledMinutes(), Integer::sum);
                if (TimeUtil.end(s).isBefore(now) && a == null) absent.add(monday);
            }
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
            if (a != null && a.getActualStart() != null && a.getActualEnd() != null) {
                LocalDateTime start = TimeUtil.start(s);
                LocalDateTime from = a.getActualStart().isAfter(start) ? a.getActualStart() : start;
                long minutes = Duration.between(from, a.getActualEnd()).toMinutes() - s.getBreakMin();
                items.add(new Item(s, from, a.getActualEnd(), (int) Math.max(0, minutes), false, false));
            } else if (a != null && TimeUtil.end(s).isBefore(now)) {
                if (YearMonth.from(s.getWorkDate()).equals(ym)) missingClockOut++; // 출근만 찍고 퇴근 미기록: 0원 처리, 경고용
            } else if (projected && !TimeUtil.end(s).isBefore(now)) {
                items.add(new Item(s, TimeUtil.start(s), TimeUtil.end(s), Math.max(0, s.scheduledMinutes()),
                        true, false));
            }
        }

        // 해당 월에 귀속되는 항목 (시작 날짜 기준)
        List<Item> inMonth = items.stream()
                .filter(i -> YearMonth.from(i.date()).equals(ym))
                .sorted(Comparator.comparing(Item::start))
                .toList();

        int minutes = inMonth.stream().mapToInt(Item::minutes).sum();
        long base = 0;
        for (Item i : inMonth) base += TimeUtil.pay(i.minutes(), wageOn.apply(i.date()));
        long weeklyHoliday = 0;
        if (sw.isWeeklyHolidayAuto()) {
            for (LocalDate monday = rangeStart; monday.isBefore(rangeEndExclusive); monday = monday.plusDays(7)) {
                LocalDate sunday = monday.plusDays(6);
                if (!YearMonth.from(sunday).equals(ym)) continue;
                // 계약서 소정시간이 우선이지만, 그 주에 예정 근무가 하나도 없으면 주휴 없음.
                int std = contractWeeklyHours != null && stdMin.containsKey(monday)
                        ? contractWeeklyHours * 60 : stdMin.getOrDefault(monday, 0);
                boolean stays = sw.getLeftAt() == null || !sw.getLeftAt().isBefore(sunday);
                boolean over = !monday.plusDays(7).atStartOfDay().isAfter(now);
                if (std >= WEEKLY_HOLIDAY_MIN_MINUTES && !absent.contains(monday) && stays && (projected || over)) {
                    weeklyHoliday += weeklyHolidayPay(std, wageOn.apply(sunday));
                }
            }
        }
        long premium = premium(store, sw, items, inMonth, ym, wageOn);
        int gross = saturate(base + weeklyHoliday + premium);
        int deduction = deduction(sw.getTaxType(), gross);

        List<PayslipDto.Entry> entries = inMonth.stream()
                .map(i -> new PayslipDto.Entry(i.date(), i.start(), i.end(), i.schedule().getBreakMin(),
                        i.schedule().isSubstitute(), i.planned(), i.minutes(),
                        i.leave() ? 0 : Math.min(nightMinutes(i.start(), i.end()), i.minutes())))
                .toList();

        return new PayslipDto.Response(sw.getId(), ym.getYear(), ym.getMonthValue(), minutes, saturate(base),
                saturate(weeklyHoliday), saturate(premium), gross, deduction, gross - deduction, projected,
                missingClockOut, entries);
    }

    /** FE weeklyHolidayPay: round(min(소정,40h)/40 x 8 x 시급). 소정 15h 미만은 0. */
    static long weeklyHolidayPay(int weekMinutes, int wage) {
        if (weekMinutes < WEEKLY_HOLIDAY_MIN_MINUTES) return 0;
        double h = Math.min(weekMinutes, WEEKLY_HOLIDAY_CAP_MINUTES) / 60.0;
        return Math.round(h / 40 * 8 * wage);
    }

    /** 가산수당: 5인 이상 매장 && premium 직원만. FE computePayslip 의 premiumApplies 블록과 같은 순서·반올림. 휴일가산 없음. */
    private long premium(Store store, StoreWorker sw, List<Item> items, List<Item> inMonth, YearMonth ym,
                         Function<LocalDate, Integer> wageOn) {
        // shortcut: 지금 재직 인원 기준(store.isUnderFive) — 월별 규모 이력이 없어 FE 와 같이 소급된다. 보고서 18 넘김 참고.
        if (store.isUnderFive() || !sw.isPremium()) {
            return 0;
        }
        long premium = 0;
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        for (Item i : inMonth) {
            if (i.leave()) continue; // 연차는 가산 대상이 아님
            // 휴게 시각을 모르므로 야간 분은 실근로 분(휴게 제외)을 넘지 않게 자른다.
            premium += TimeUtil.pay(Math.min(nightMinutes(i.start(), i.end()), i.minutes()), wageOn.apply(i.date())) / 2;
            perDay.merge(i.date(), i.minutes(), Integer::sum);
        }
        for (Map.Entry<LocalDate, Integer> d : perDay.entrySet()) {
            if (d.getValue() > DAILY_REGULAR_MINUTES) {
                premium += TimeUtil.pay(d.getValue() - DAILY_REGULAR_MINUTES, wageOn.apply(d.getKey())) / 2;
            }
        }
        // 주 40h 초과 중 일별 8h 초과와 겹치지 않는 부분 (조회 범위 전체 항목, 일요일이 이 달인 주만)
        Map<LocalDate, Integer> days = new TreeMap<>();
        for (Item i : items) {
            if (!i.leave()) days.merge(i.date(), i.minutes(), Integer::sum);
        }
        Map<LocalDate, Integer> weekMin = new TreeMap<>();
        Map<LocalDate, Integer> weekDailyOver = new HashMap<>();
        days.forEach((d, m) -> {
            LocalDate w = TimeUtil.monday(d);
            weekMin.merge(w, m, Integer::sum);
            weekDailyOver.merge(w, Math.max(0, m - DAILY_REGULAR_MINUTES), Integer::sum);
        });
        for (Map.Entry<LocalDate, Integer> w : weekMin.entrySet()) {
            LocalDate sunday = w.getKey().plusDays(6);
            if (!YearMonth.from(sunday).equals(ym)) continue;
            int extra = w.getValue() - WEEKLY_HOLIDAY_CAP_MINUTES - weekDailyOver.get(w.getKey());
            if (extra > 0) premium += TimeUtil.pay(extra, wageOn.apply(sunday)) / 2;
        }
        return premium;
    }

    /** 금액 합계를 int 범위로 포화 (비현실적 시급에서도 음수·오버플로 없음). */
    static int saturate(long v) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
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
