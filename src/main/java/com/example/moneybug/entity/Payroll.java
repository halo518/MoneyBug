package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// [추가] 직원-월당 정산 1건
@Table(name = "payroll", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payroll_mapping_month", columnNames = {"mapping_id", "payroll_month"})
})
@Getter
@Setter
@NoArgsConstructor
public class Payroll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payroll_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private StoreWorker storeWorker;

    // 정산 연월 (예: 2026-10)
    @Column(name = "payroll_month", nullable = false, length = 7)
    private String payrollMonth;

    // 분 단위
    @Column(name = "total_work_time", nullable = false)
    private Integer totalWorkTime;

    // [추가] 기본급 = round(분 x 시급 / 60)
    @Column(name = "base_pay")
    private Integer basePay = 0;

    @Column(name = "total_holiday_allowance")
    private Integer totalHolidayAllowance = 0;

    // 가산수당(야간·8시간 초과분)
    @Column(name = "total_overtime_allowance")
    private Integer totalOvertimeAllowance = 0;

    @Column(name = "weekly_holiday_allowance")
    private Integer weeklyHolidayAllowance = 0;

    // [추가] 총액 = base + weeklyHoliday + premium (공제 전)
    @Column(name = "gross_pay")
    private Integer grossPay = 0;

    @Column(name = "deduction_amount")
    private Integer deductionAmount = 0;

    // 실수령액(net) = gross - deduction
    @Column(name = "final_salary", nullable = false)
    private Integer finalSalary;
}
