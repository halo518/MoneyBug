package com.example.moneybug.dto;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.service.FixedShiftPlanner;
import com.example.moneybug.service.GapFiller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
            @Size(max = 50, message = "파트 이름은 50자까지예요") String part,
            @Min(value = 0, message = "휴게 시간이 올바르지 않아요")
            @Max(value = 1440, message = "휴게 시간이 올바르지 않아요") Integer breakMin,
            Schedule.Kind kind,
            Boolean timeTbd) {}

    public record SaveShiftsRequest(
            @NotNull @Valid List<@NotNull(message = "빈 근무가 섞여 있어요") ShiftItem> shifts) {}

    public record Skipped(Integer id, String reason) {}

    public record SaveShiftsResponse(List<Skipped> skipped) {}

    public record GenerateRequest(
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month,
            MonthSchedule.HolidayPolicy weeklyHolidayPay,
            MonthSchedule.Priority priority) {}

    public record ApplyRequest(@NotBlank String planId) {}

    /** 미리보기 근무 한 칸. part 가 없는 직원 유동 근무는 "직원". */
    public record PlannedShift(Integer staffId, LocalDate date, int startMin, int endMin, String part, int breakMin) {}

    /** 못 채운 칸. missing = 모자란 인원. */
    public record Unfilled(LocalDate date, String part, int startMin, int endMin, int missing) {}

    /** replacing = 적용하면 지워질 기존 근무 수(오늘 이후~월말 WORK 중 출근 기록 없는 것). */
    public record PreviewResponse(String planId, String month, List<PlannedShift> shifts, List<String> lines,
                                  long laborCost, List<Unfilled> unfilled, int replacing) {

        public static PreviewResponse of(String planId, String month, List<FixedShiftPlanner.Planned> planned,
                                         String flexPart, List<String> lines, long laborCost,
                                         List<GapFiller.Gap> gaps, int replacing) {
            return new PreviewResponse(planId, month,
                    planned.stream().map(p -> new PlannedShift(p.staffId(), p.date(), p.startMin(), p.endMin(),
                            p.part() != null ? p.part() : flexPart, p.breakMin())).toList(),
                    lines, laborCost,
                    gaps.stream().map(g -> new Unfilled(g.date(), g.part(), g.startMin(), g.endMin(), g.missing()))
                            .toList(),
                    replacing);
        }
    }

    public record TemplateCreateRequest(
            @NotBlank(message = "템플릿 이름을 입력하세요.")
            @jakarta.validation.constraints.Size(max = 100, message = "템플릿 이름은 100자까지 입력할 수 있어요.") String name,
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month) {}

    public record TemplateApplyRequest(
            @NotNull @Pattern(regexp = MONTH_RE, message = "월 형식이 올바르지 않아요") String month) {}
}
