package com.example.moneybug.dto;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Schedule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;

/** 월 근무표 쓰기 API 요청/응답 (계약 /schedules, /schedule-templates). month = yyyy-MM */
public class MonthScheduleCommandDto {

    public static final String MONTH_RE = "\\d{4}-(0[1-9]|1[0-2])";

    public record CreateRequest(
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month,
            Boolean prefillFixed) {}

    /** id 는 숫자 또는 FE 임시 문자열 — 숫자가 아니면 새 근무로 본다. */
    public record ShiftItem(
            String id,
            @NotNull Integer staffId,
            @NotNull LocalDate date,
            @NotNull Integer startMin,
            @NotNull Integer endMin,
            String part,
            Integer breakMin,
            Schedule.Kind kind,
            Boolean timeTbd) {}

    public record SaveShiftsRequest(@NotNull @Valid List<ShiftItem> shifts) {}

    public record Skipped(Integer id, String reason) {}

    public record SaveShiftsResponse(List<Skipped> skipped) {}

    public record GenerateRequest(
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month,
            MonthSchedule.HolidayPolicy weeklyHolidayPay,
            MonthSchedule.Priority priority) {}

    public record JobResponse(String jobId) {}

    public record TemplateCreateRequest(
            @NotBlank(message = "템플릿 이름을 입력하세요.") String name,
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month) {}

    public record TemplateApplyRequest(
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month) {}
}
