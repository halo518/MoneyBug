package com.example.moneybug.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 근무표 만들기 미리보기 → 적용 (설계서 §3.11, §4.5). */
public class SchedulePlanDto {

    public record Request(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            /** 파트별 필요 인원 (store.parts 순서) */
            @NotNull List<Integer> needs,
            /** 생략하면 false */
            Boolean copyLastWeek,
            /** 이번 배정에서 뺄 직원(StoreWorker id) */
            List<@NotNull(message = "제외할 직원 id 가 비어 있어요") Integer> excludeStaffIds,
            /** 주 최대 근무 분 (생략 시 2400 = 40시간) */
            @Min(1) Integer weeklyMaxMin) {}

    public record Response(
            /** 미리보기 → 적용을 이어주는 id */
            String planId,
            LocalDate from,
            LocalDate to,
            /** 새로 추가될 근무 (id 없음) */
            List<ScheduleDto.Response> shifts,
            /** 사람이 모자라 못 채운 칸 수 */
            int unfilled,
            /** staffId -> 범위 내 총 근무 분(기존 + 새 근무) */
            Map<Integer, Integer> minutesByStaff,
            /** 새 근무 예상 인건비(기본급 기준) */
            int cost) {}

    public record ApplyRequest(@NotNull List<Integer> needs) {}
}
