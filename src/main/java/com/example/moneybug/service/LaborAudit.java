package com.example.moneybug.service;

import com.example.moneybug.dto.AiDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.LaborContext.Staff;
import com.example.moneybug.service.LaborContext.StoreFacts;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 노무 점검 규칙. DB 를 읽지 않는다. 규칙 하나당 finding 하나(해당 직원 전원을 staffIds 에), 높음 → 낮음 순.
 * 직원은 Staff.label(별칭)로 적는다. 이름 복원은 AiLaborService 가 한다.
 */
public final class LaborAudit {

    /** 근로기준법 제53조: 주 40시간 + 연장 12시간. */
    static final int OVER_HOURS_MIN = 52 * 60;
    /** detail 에 적는 항목 수. 넘으면 "외 n건". */
    static final int MAX_ITEMS = 10;

    static final String HIGH = "high", MEDIUM = "medium", LOW = "low";
    private static final List<String> SEVERITY_ORDER = List.of(HIGH, MEDIUM, LOW);

    private LaborAudit() {}

    /** shifts: WORK 근무(endMin 은 다음 날이면 +1440). 주 단위 규칙은 받은 근무 전부, 근무 단위 규칙은 today 이후만 본다. */
    static List<AiDto.Finding> run(StoreFacts store, List<Staff> staff, List<Planned> shifts, LocalDate today) {
        Map<Integer, List<Planned>> byStaff = new HashMap<>();
        shifts.stream().sorted(Comparator.comparing(Planned::date).thenComparingInt(Planned::startMin))
                .forEach(p -> byStaff.computeIfAbsent(p.staffId(), k -> new ArrayList<>()).add(p));

        Rule minWage = new Rule(), noContract = new Rule(), unsigned = new Rule(),
                certExpired = new Rule(), certMissing = new Rule(), certSoon = new Rule(),
                minorNight = new Rule(), minorHours = new Rule(), holidayOff = new Rule(), sevenDays = new Rule(),
                overHours = new Rule(), over40 = new Rule(), breakShort = new Rule(), premiumOff = new Rule();

        for (Staff s : staff) {
            String n = s.label();
            if (s.wage() < StoreWorkerDto.MIN_WAGE_2026) minWage.add(s, n + " " + LaborContext.won(s.wage()));

            if (s.contract() == null) noContract.add(s, n);
            else if (!s.contract().signed()) {
                String who = !s.contract().ownerSigned() && !s.contract().employeeSigned() ? "사장님·직원 모두"
                        : !s.contract().ownerSigned() ? "사장님" : "직원";
                unsigned.add(s, n + "(" + who + " 서명 전)");
            }

            if (s.certExpiry() == null) certMissing.add(s, n);
            else {
                long d = ChronoUnit.DAYS.between(today, s.certExpiry());
                if (d < 0) certExpired.add(s, n + "(" + s.certExpiry() + " 만료)");
                else if (d <= DashboardService.CERT_ALERT_DAYS) certSoon.add(s, n + "(D-" + d + ")");
            }

            if (!store.underFive() && !s.premium()) premiumOff.add(s, n);

            Map<LocalDate, Integer> weekMin = new TreeMap<>(), dayMin = new TreeMap<>();
            Map<LocalDate, Set<LocalDate>> weekDays = new TreeMap<>();
            for (Planned p : byStaff.getOrDefault(s.id(), List.of())) {
                int work = work(p);
                LocalDate monday = TimeUtil.monday(p.date());
                weekMin.merge(monday, work, Integer::sum);
                weekDays.computeIfAbsent(monday, k -> new HashSet<>()).add(p.date());
                if (p.date().isBefore(today)) continue;
                dayMin.merge(p.date(), work, Integer::sum);
                if (s.minor() && (p.startMin() < FixedShiftPlanner.MINOR_FROM || p.endMin() > FixedShiftPlanner.MINOR_TO)) {
                    minorNight.add(s, n + " " + md(p.date()) + " " + TimeUtil.hhmm(p.startMin()) + "~" + TimeUtil.hhmm(p.endMin()));
                }
                int need = FixedShiftPlanner.requiredBreak(work);
                if (p.breakMin() < need) {
                    breakShort.add(s, n + " " + md(p.date()) + " " + LaborContext.hours(work) + " 근무에 휴게 "
                            + p.breakMin() + "분(필요 " + need + "분)");
                }
            }
            if (s.minor()) {
                dayMin.forEach((d, m) -> {
                    if (m > FixedShiftPlanner.MINOR_DAY_MIN) minorHours.add(s, n + " " + md(d) + " 하루 " + LaborContext.hours(m));
                });
            }
            weekMin.forEach((monday, m) -> {
                String w = n + " " + md(monday) + "주 " + LaborContext.hours(m);
                if (s.minor() && m > ScheduleInsights.MINOR_WEEK_MIN) minorHours.add(s, w);
                if (!s.weeklyHolidayAuto() && m >= ScheduleInsights.HOLIDAY_MIN) holidayOff.add(s, w);
                if (!store.underFive() && m > OVER_HOURS_MIN) overHours.add(s, w);
                if (m > GapFiller.WEEK_CAP_MIN) over40.add(s, w);
            });
            weekDays.forEach((monday, days) -> {
                if (days.size() >= 7) sevenDays.add(s, n + " " + md(monday) + "주");
            });
        }

        List<AiDto.Finding> out = new ArrayList<>();
        minWage.emit(out, "MIN_WAGE", HIGH, "최저임금보다 낮은 시급",
                "2026년 최저임금은 시간당 " + LaborContext.won(StoreWorkerDto.MIN_WAGE_2026) + "이에요. ",
                "시급을 " + LaborContext.won(StoreWorkerDto.MIN_WAGE_2026) + " 이상으로 올리고 근로계약서도 고쳐 주세요.",
                List.of("최저임금법 제6조"));
        noContract.emit(out, "CONTRACT_MISSING", HIGH, "근로계약서 없음", "근로계약서가 없어요: ",
                "근로계약서를 써서 양쪽이 서명하고 한 부를 직원에게 주세요.", List.of("근로기준법 제17조"));
        unsigned.emit(out, "CONTRACT_UNSIGNED", MEDIUM, "서명이 끝나지 않은 근로계약서", "서명이 끝나지 않았어요: ",
                "사장님과 직원 모두 서명을 마쳐 주세요.", List.of("근로기준법 제17조"));
        certExpired.emit(out, "CERT_EXPIRED", HIGH, "보건증 만료", "보건증이 만료됐어요: ",
                "보건증을 새로 발급받을 때까지 식품을 다루는 일은 빼 주세요.", List.of("식품위생법 제40조"));
        certMissing.emit(out, "CERT_MISSING", MEDIUM, "보건증 미등록", "보건증이 등록되지 않았어요: ",
                "보건증을 받아 앱에 등록하게 해 주세요.", List.of("식품위생법 제40조"));
        certSoon.emit(out, "CERT_SOON", LOW, "보건증 만료 임박", DashboardService.CERT_ALERT_DAYS + "일 안에 보건증이 만료돼요: ",
                "만료 전에 다시 발급받게 알려 주세요.", List.of("식품위생법 제40조"));
        minorNight.emit(out, "MINOR_NIGHT", HIGH, "연소자 야간 근무", "연소자가 22시~6시에 일하는 근무가 있어요: ",
                "22시 전에 끝나고 6시 이후에 시작하도록 바꿔 주세요. 꼭 필요하면 본인 동의와 고용노동부 장관 인가를 받아야 해요.",
                List.of("근로기준법 제70조"));
        minorHours.emit(out, "MINOR_HOURS", HIGH, "연소자 근로시간 초과", "연소자는 하루 7시간, 주 35시간까지예요: ",
                "근무를 줄이거나 다른 직원에게 나눠 주세요.", List.of("근로기준법 제69조"));
        holidayOff.emit(out, "WEEKLY_HOLIDAY_OFF", MEDIUM, "주휴수당 자동 계산 꺼짐",
                "주 15시간 이상 일하는데 주휴수당 자동 계산이 꺼져 있어요: ",
                "주 15시간 이상 일하면 주휴수당을 줘야 해요. 직원 설정에서 주휴수당 자동을 켜 주세요.",
                List.of("근로기준법 제55조"));
        sevenDays.emit(out, "SEVEN_DAYS", MEDIUM, "주 7일 근무", "한 주 7일 모두 근무가 잡혀 있어요: ",
                "한 주에 하루 이상은 쉬는 날을 넣어 주세요.", List.of("근로기준법 제55조"));
        overHours.emit(out, "OVER_HOURS", HIGH, "주 52시간 초과", "한 주 52시간을 넘는 근무가 있어요: ",
                "연장근로는 주 12시간까지라 주 52시간을 넘길 수 없어요. 근무를 줄여 주세요.", List.of("근로기준법 제53조"));
        over40.emit(out, "OVER_40", LOW, "주 40시간 초과", "한 주 40시간을 넘는 근무가 있어요: ",
                store.underFive() ? "5인 미만 매장은 연장근로 가산수당 의무는 없지만, 직원과 합의한 시간인지 확인해 주세요."
                        : "40시간을 넘는 시간은 연장근로라 직원 동의가 필요하고 가산수당(50%)을 줘야 해요.",
                store.underFive() ? List.of() : List.of("근로기준법 제50조", "근로기준법 제56조"));
        breakShort.emit(out, "BREAK_SHORT", MEDIUM, "휴게시간 부족", "휴게시간이 모자란 근무가 있어요: ",
                "4시간 일하면 30분, 8시간 일하면 1시간 이상 휴게를 넣어 주세요.", List.of("근로기준법 제54조"));
        premiumOff.emit(out, "PREMIUM_OFF", LOW, "가산수당 꺼짐", "5인 이상 매장인데 가산수당이 꺼져 있어요: ",
                "연장·야간·휴일 근로에 가산수당이 붙도록 직원 설정에서 가산수당을 켜 주세요.", List.of("근로기준법 제56조"));

        boolean under = store.activeCount() < 5;
        if ((store.sizeMode() == Store.SizeMode.UNDER5 && !under) || (store.sizeMode() == Store.SizeMode.OVER5 && under)) {
            out.add(new AiDto.Finding("SIZE_MISMATCH", LOW, "사업장 규모 설정 확인",
                    "규모를 " + (under ? "5인 이상" : "5인 미만") + "으로 직접 정했는데 재직 인원이 " + store.activeCount() + "명이에요.",
                    "규모 설정을 '자동'으로 바꾸거나 실제 상시 근로자 수(한 달 평균)를 확인해 주세요.",
                    List.of(), List.of("근로기준법 제11조")));
        }

        out.sort(Comparator.comparingInt(f -> SEVERITY_ORDER.indexOf(f.severity())));
        return out;
    }

    static int work(Planned p) {
        return Math.max(0, p.endMin() - p.startMin() - p.breakMin());
    }

    private static String md(LocalDate d) {
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    /** 한 규칙에 걸린 직원 id(순서 유지·중복 없음)와 detail 항목. */
    private static final class Rule {
        final List<Integer> ids = new ArrayList<>();
        final List<String> items = new ArrayList<>();

        void add(Staff s, String item) {
            if (!ids.contains(s.id())) ids.add(s.id());
            items.add(item);
        }

        void emit(List<AiDto.Finding> out, String code, String severity, String title, String prefix,
                  String action, List<String> citations) {
            if (ids.isEmpty()) return;
            String detail = prefix + String.join(", ", items.subList(0, Math.min(items.size(), MAX_ITEMS)))
                    + (items.size() > MAX_ITEMS ? " 외 " + (items.size() - MAX_ITEMS) + "건" : "");
            out.add(new AiDto.Finding(code, severity, title, detail, action, List.copyOf(ids), citations));
        }
    }
}
