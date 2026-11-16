package com.example.moneybug.dto;

import java.util.List;

/** 인건비 집계 (설계서 §4.10). */
public class LaborCostDto {

    public record ByStaff(Integer staffId, int gross) {}

    /** GET /labor-cost?year=&month=&projected= */
    public record Response(int year, int month, long total, List<ByStaff> byStaff) {}

    public record MonthTotal(String month, long total) {}

    /** GET /labor-cost/trend?months= — 확정된 과거 월별 합계 */
    public record TrendResponse(List<MonthTotal> items) {}
}
