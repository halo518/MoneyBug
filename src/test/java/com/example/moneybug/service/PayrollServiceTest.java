package com.example.moneybug.service;

import com.example.moneybug.dto.LaborCostDto;
import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Payroll;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.PayrollRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayrollServiceTest {

    private final PayrollRepository payrolls = mock(PayrollRepository.class);
    private final StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    private final StoreAccess access = mock(StoreAccess.class);
    private final PayrollCalculator calc = mock(PayrollCalculator.class);
    private final PayrollService service = new PayrollService(payrolls, workers, access, calc);
    private final Store store = new Store();

    private static StoreWorker sw(int id) {
        StoreWorker w = new StoreWorker();
        w.setId(id);
        return w;
    }

    private static PayslipDto.Response gross(int g) {
        return new PayslipDto.Response(0, 0, 0, 0, g, 0, 0, g, 0, g, false, 0, List.of());
    }

    /** 보고서 18: 74번만 저장 행(gross 0, 실지급 100,000 + 공제 3,300), 73번은 계산 156,000. */
    @Test
    void 저장_행이_없는_직원은_계산값으로_더하고_trend_와_같다() {
        store.setId(35);
        when(access.requireOwner(1, 35)).thenReturn(store);
        StoreWorker s73 = sw(73), s74 = sw(74);
        when(workers.findByStoreIdAndStatusIn(eq(35), anyList())).thenReturn(List.of(s73, s74));
        Payroll p = new Payroll();
        p.setStoreWorker(s74);
        p.setGrossPay(0);
        p.setDeductionAmount(3300);
        p.setFinalSalary(100000);
        String sep = YearMonth.from(TimeUtil.today()).minusMonths(2).toString();
        when(payrolls.findByStoreWorkerStoreIdAndPayrollMonth(anyInt(), anyString())).thenReturn(List.of());
        when(payrolls.findByStoreWorkerStoreIdAndPayrollMonth(35, sep)).thenReturn(List.of(p));
        when(calc.calculate(eq(store), eq(s73), any(), anyBoolean(), any())).thenReturn(gross(156000));
        when(calc.calculate(eq(store), eq(s74), any(), anyBoolean(), any())).thenReturn(gross(999));

        YearMonth ym = YearMonth.parse(sep);
        LaborCostDto.Response r = service.laborCost(1, 35, ym.getYear(), ym.getMonthValue(), false);
        assertEquals(259300, r.total());
        LaborCostDto.MonthTotal t = service.trend(1, 35, 3).items().get(1);
        assertEquals(sep, t.month());
        assertEquals(259300, t.total());
    }

    @Test
    void 연도_범위_밖은_400() {
        when(access.requireOwner(1, 35)).thenReturn(store);
        ApiException e = assertThrows(ApiException.class, () -> service.laborCost(1, 35, -5, 1, false));
        assertEquals("INVALID_MONTH", e.getCode());
        assertThrows(ApiException.class, () -> service.laborCost(1, 35, 2026, 13, false));
    }
}
