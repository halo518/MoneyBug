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

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return calculator.calculate(store, sw, validMonth(month.getYear(), month.getMonthValue()), projected,
                TimeUtil.now());
    }

    /**
     * GET /labor-cost — status != PENDING 직원 전원의 gross 합. 지난 달(확정 조회)은 저장된 정산(Payroll)이 있는 직원은 그 값,
     * 없는 직원은 계산 값. (거절된 직원은 근무가 없으므로 제외)
     */
    public LaborCostDto.Response laborCost(Integer userId, Integer storeId, int year, int month, boolean projected) {
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = validMonth(year, month);
        List<LaborCostDto.ByStaff> byStaff = byStaff(store, ym, projected, TimeUtil.now());
        long total = byStaff.stream().mapToLong(LaborCostDto.ByStaff::gross).sum();
        return new LaborCostDto.Response(year, month, total, byStaff);
    }

    /** 연도 2000~2100, 월 1~12 만 받는다. */
    private static YearMonth validMonth(int year, int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw ApiException.badRequest("INVALID_MONTH", "연·월이 올바르지 않아요");
        }
        return YearMonth.of(year, month);
    }

    /** 직원별 그 달 gross. /labor-cost·/trend 가 같은 규칙을 쓴다. */
    private List<LaborCostDto.ByStaff> byStaff(Store store, YearMonth ym, boolean projected, LocalDateTime now) {
        Map<Integer, Integer> stored = new LinkedHashMap<>();
        if (!projected && ym.isBefore(YearMonth.from(now))) {
            for (Payroll p : payrollRepository.findByStoreWorkerStoreIdAndPayrollMonth(store.getId(), ym.toString())) {
                stored.put(p.getStoreWorker().getId(), storedGross(p));
            }
        }
        List<LaborCostDto.ByStaff> byStaff = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatusIn(store.getId(),
                List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.LEFT))) {
            Integer fixed = stored.remove(sw.getId());
            byStaff.add(new LaborCostDto.ByStaff(sw.getId(),
                    fixed != null ? fixed : calculator.calculate(store, sw, ym, projected, now).gross()));
        }
        stored.forEach((id, gross) -> byStaff.add(new LaborCostDto.ByStaff(id, gross))); // 대기 등 목록 밖 직원의 저장 행
        return byStaff;
    }

    /** 그 달 매장 인건비(확정 조회). /trend 와 같은 값. */
    public long monthTotal(Store store, YearMonth ym) {
        return byStaff(store, ym, false, TimeUtil.now()).stream().mapToLong(LaborCostDto.ByStaff::gross).sum();
    }

    /** GET /labor-cost/trend?months= — 지난달부터 거슬러 올라가는 월별 합계. /labor-cost 확정 조회와 같은 값. */
    public LaborCostDto.TrendResponse trend(Integer userId, Integer storeId, int months) {
        Store store = access.requireOwner(userId, storeId);
        if (months < 1 || months > 24) {
            throw ApiException.badRequest("INVALID_MONTHS", "months 는 1~24 사이여야 해요");
        }
        YearMonth current = YearMonth.from(TimeUtil.today());
        List<LaborCostDto.MonthTotal> items = new ArrayList<>();
        for (int i = months; i >= 1; i--) {
            YearMonth ym = current.minusMonths(i);
            items.add(new LaborCostDto.MonthTotal(ym.toString(), monthTotal(store, ym)));
        }
        return new LaborCostDto.TrendResponse(items);
    }

    /** 저장된 정산의 총액. gross_pay 가 비어 있으면(0) 실지급+공제. PayrollRepository.sumGrossByMonthBefore 와 같은 규칙. */
    static int storedGross(Payroll p) {
        if (p.getGrossPay() != null && p.getGrossPay() > 0) return p.getGrossPay();
        int deduction = p.getDeductionAmount() != null ? p.getDeductionAmount() : 0;
        return p.getFinalSalary() + deduction;
    }
}
