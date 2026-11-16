package com.example.moneybug.service;

import com.example.moneybug.dto.LaborCostDto;
import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Payroll;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.PayrollRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 급여 · 인건비 (설계서 §4.10). 계산은 PayrollCalculator. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PayrollService {

    private final PayrollRepository payrollRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StoreAccess access;
    private final PayrollCalculator calculator;

    /** GET .../staff/{staffId}/payslips/{yyyy-MM}?projected= — 사장님, 본인. */
    public PayslipDto.Response payslip(Integer userId, Integer storeId, Integer staffId, YearMonth month,
                                       boolean projected) {
        Store store = access.store(storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (!access.isOwner(store, userId) && !sw.getWorker().getId().equals(userId)) {
            throw ApiException.forbidden("사장님 또는 본인만 급여명세를 볼 수 있어요");
        }
        return calculator.calculate(store, sw, month, projected, TimeUtil.now());
    }

    /**
     * GET /labor-cost — 과거 확정 월은 저장된 값(Payroll), 그 외는 status != PENDING 직원 전원의 gross 합.
     * (거절된 직원은 근무가 없으므로 제외)
     */
    public LaborCostDto.Response laborCost(Integer userId, Integer storeId, int year, int month, boolean projected) {
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym;
        try {
            ym = YearMonth.of(year, month);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_MONTH", "연·월이 올바르지 않아요");
        }

        if (!projected && ym.isBefore(YearMonth.from(TimeUtil.today()))) {
            List<Payroll> stored = payrollRepository.findByStoreWorkerStoreIdAndPayrollMonth(storeId, ym.toString());
            if (!stored.isEmpty()) {
                List<LaborCostDto.ByStaff> byStaff = stored.stream()
                        .map(p -> new LaborCostDto.ByStaff(p.getStoreWorker().getId(), storedGross(p)))
                        .toList();
                long total = byStaff.stream().mapToLong(LaborCostDto.ByStaff::gross).sum();
                return new LaborCostDto.Response(year, month, total, byStaff);
            }
        }

        LocalDateTime now = TimeUtil.now();
        List<LaborCostDto.ByStaff> byStaff = new ArrayList<>();
        long total = 0;
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatusIn(storeId,
                List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.LEFT))) {
            int gross = calculator.calculate(store, sw, ym, projected, now).gross();
            byStaff.add(new LaborCostDto.ByStaff(sw.getId(), gross));
            total += gross;
        }
        return new LaborCostDto.Response(year, month, total, byStaff);
    }

    /** GET /labor-cost/trend?months= — 지난달부터 거슬러 올라가며 확정(저장)된 월별 합계. 데이터 없는 달은 0. */
    public LaborCostDto.TrendResponse trend(Integer userId, Integer storeId, int months) {
        access.requireOwner(userId, storeId);
        if (months < 1 || months > 24) {
            throw ApiException.badRequest("INVALID_MONTHS", "months 는 1~24 사이여야 해요");
        }
        YearMonth current = YearMonth.from(TimeUtil.today());
        Map<String, Long> stored = new HashMap<>();
        for (Object[] row : payrollRepository.sumGrossByMonthBefore(storeId, current.toString())) {
            stored.put((String) row[0], row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        List<LaborCostDto.MonthTotal> items = new ArrayList<>();
        for (int i = months; i >= 1; i--) {
            String key = current.minusMonths(i).toString();
            items.add(new LaborCostDto.MonthTotal(key, stored.getOrDefault(key, 0L)));
        }
        return new LaborCostDto.TrendResponse(items);
    }

    private static int storedGross(Payroll p) {
        if (p.getGrossPay() != null && p.getGrossPay() > 0) return p.getGrossPay();
        int deduction = p.getDeductionAmount() != null ? p.getDeductionAmount() : 0;
        return p.getFinalSalary() + deduction;
    }
}
