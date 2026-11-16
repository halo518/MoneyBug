package com.example.moneybug.dto;

import com.example.moneybug.entity.Payroll;
import com.example.moneybug.entity.StoreWorker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class PayrollDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        private Integer mappingId;

        @NotBlank
        @Pattern(regexp = "\\d{4}-\\d{2}", message = "yyyy-MM 형식이어야 합니다")
        private String payrollMonth;

        /** 분 단위 */
        @NotNull
        private Integer totalWorkTime;

        private Integer basePay = 0;
        private Integer totalHolidayAllowance = 0;
        private Integer totalOvertimeAllowance = 0;
        private Integer weeklyHolidayAllowance = 0;
        private Integer grossPay = 0;
        private Integer deductionAmount = 0;

        /** 실수령액(net) */
        @NotNull
        private Integer finalSalary;

        /** 연관 엔티티(매장-직원 매핑)는 서비스에서 조회해 넘겨준다. */
        public Payroll toEntity(StoreWorker storeWorker) {
            Payroll payroll = new Payroll();
            payroll.setStoreWorker(storeWorker);
            payroll.setPayrollMonth(payrollMonth);
            payroll.setTotalWorkTime(totalWorkTime);
            payroll.setBasePay(basePay);
            payroll.setTotalHolidayAllowance(totalHolidayAllowance);
            payroll.setTotalOvertimeAllowance(totalOvertimeAllowance);
            payroll.setWeeklyHolidayAllowance(weeklyHolidayAllowance);
            payroll.setGrossPay(grossPay);
            payroll.setDeductionAmount(deductionAmount);
            payroll.setFinalSalary(finalSalary);
            return payroll;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer mappingId;
        private String payrollMonth;
        private Integer totalWorkTime;
        private Integer basePay;
        private Integer totalHolidayAllowance;
        private Integer totalOvertimeAllowance;
        private Integer weeklyHolidayAllowance;
        private Integer grossPay;
        private Integer deductionAmount;
        private Integer finalSalary;

        public static Response from(Payroll payroll) {
            return Response.builder()
                    .id(payroll.getId())
                    .mappingId(payroll.getStoreWorker().getId())
                    .payrollMonth(payroll.getPayrollMonth())
                    .totalWorkTime(payroll.getTotalWorkTime())
                    .basePay(payroll.getBasePay())
                    .totalHolidayAllowance(payroll.getTotalHolidayAllowance())
                    .totalOvertimeAllowance(payroll.getTotalOvertimeAllowance())
                    .weeklyHolidayAllowance(payroll.getWeeklyHolidayAllowance())
                    .grossPay(payroll.getGrossPay())
                    .deductionAmount(payroll.getDeductionAmount())
                    .finalSalary(payroll.getFinalSalary())
                    .build();
        }
    }
}
