package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.LaborCostDto;
import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.service.PayrollService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/** 급여 · 인건비 (설계서 §4.10). */
@Tag(name = "급여·인건비", description = "급여명세와 매장 인건비 (설계서 4.10)")
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollService payrollService;

    /** {month} 는 yyyy-MM */
    @Operation(summary = "급여명세 조회", description = "{month} 는 yyyy-MM. projected=true 면 오늘 이후 예정 근무까지 포함한 예상 급여. 권한: 사장님, 본인")
    @GetMapping("/staff/{staffId}/payslips/{month}")
    public PayslipDto.Response payslip(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                       @PathVariable Integer staffId,
                                       @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                       @RequestParam(defaultValue = "false") boolean projected) {
        return payrollService.payslip(userId.intValue(), storeId, staffId, month, projected);
    }

    @Operation(summary = "월 인건비 집계", description = "과거 확정 월은 저장된 값, 그 외는 재직 직원 총액 합계. 권한: 사장님")
    @GetMapping("/labor-cost")
    public LaborCostDto.Response laborCost(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                           @RequestParam int year, @RequestParam int month,
                                           @RequestParam(defaultValue = "false") boolean projected) {
        return payrollService.laborCost(userId.intValue(), storeId, year, month, projected);
    }

    @Operation(summary = "인건비 추이", description = "지난달부터 거슬러 올라간 확정 월별 합계(months 1~24). 권한: 사장님")
    @GetMapping("/labor-cost/trend")
    public LaborCostDto.TrendResponse trend(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                            @RequestParam(defaultValue = "6") int months) {
        return payrollService.trend(userId.intValue(), storeId, months);
    }
}
