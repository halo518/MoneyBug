package com.example.moneybug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 설계서 §3.10 PayslipDto — 서버가 계산해서 내려주는 값이라 요청 DTO 는 없다. */
public class PayslipDto {

    public record Response(
            Integer staffId,
            int year,
            int month,
            /** 그 달 실근무 분 */
            int minutes,
            /** 기본급 */
            int base,
            int weeklyHoliday,
            /** 가산수당(야간·8시간 초과분 0.5배) */
            int premium,
            /** = base + weeklyHoliday + premium */
            int gross,
            int deduction,
            /** = gross - deduction */
            int net,
            /** true 면 오늘 이후 예정 근무까지 포함한 '예상 급여' */
            boolean projected,
            /** 이 달 지난 근무 중 출근만 있고 퇴근 기록이 없어 0원으로 빠진 건수 */
            int missingClockOut,
            List<Entry> entries) {}

    public record Entry(
            LocalDate date,
            LocalDateTime start,
            LocalDateTime end,
            int breakMin,
            @JsonProperty("isSubstitute") boolean isSubstitute,
            boolean planned,
            int minutes,
            int nightMinutes) {}
}
