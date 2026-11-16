package com.example.moneybug.dto;

import com.example.moneybug.entity.Schedule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 근무표 관련 쓰기 API 요청/응답 (설계서 §4.5).
 * staffId = StoreWorker.id (mapping_id), 시각은 하루 기준 분(startMin/endMin).
 */
public class ShiftCommandDto {

    /** POST /shifts — 여러 명이면 같은 근무를 명수만큼 생성. */
    public record CreateRequest(
            @NotNull LocalDate date,
            @NotNull @Min(0) @Max(1439) Integer startMin,
            @NotNull @Min(0) @Max(1439) Integer endMin,
            @Size(max = 50) String part,
            @NotEmpty(message = "근로자를 한 명 이상 골라 주세요") List<Integer> staffIds,
            /** 생략 시 자동 (480분 이상 60, 240분 이상 30, 그 외 0) */
            @Min(0) Integer breakMin,
            /** >1 이면 매주 같은 요일로 반복 생성. 생략 시 1 */
            @Min(1) Integer repeatWeeks,
            /** 생략 시 WORK */
            Schedule.Kind kind,
            Boolean timeTbd) {}

    public record CreateResponse(List<ScheduleDto.Response> created) {}

    /** PATCH /shifts/{id} — 모두 선택, 보낸 것만 반영. */
    public record UpdateRequest(
            Integer staffId,
            LocalDate date,
            @Min(0) @Max(1439) Integer startMin,
            @Min(0) @Max(1439) Integer endMin,
            @Size(max = 50) String part,
            @Min(0) Integer breakMin,
            Schedule.Kind kind,
            Boolean timeTbd) {}

    /** POST /schedule/auto-fill */
    public record AutoFillRequest(@NotNull LocalDate weekMonday) {}

    /** auto-fill, plan apply 결과 { "added": n } */
    public record AddedResponse(int added) {}

    /** POST /schedule/template */
    public record TemplateRequest(@NotNull List<Integer> needs, boolean copyLastWeek) {}

    public record CopiedResponse(int copied) {}

    /** POST /schedule/confirm */
    public record ConfirmRequest(
            @NotNull @Min(2000) Integer year,
            @NotNull @Min(1) @Max(12) Integer month) {}

    /** GET /schedule/tbd-count */
    public record TbdCountResponse(int count) {}
}
