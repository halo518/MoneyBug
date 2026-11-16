package com.example.moneybug.dto;

import com.example.moneybug.entity.StoreWorker;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 직원 관련 쓰기 API 요청 (설계서 §4.3). */
public class StaffCommandDto {

    /** POST .../approve — taxType 생략 시 store.defaultDeduction. */
    public record ApproveRequest(
            @NotNull @Min(StoreWorkerDto.MIN_WAGE_2026) Integer hourlyWage,
            StoreWorker.TaxType taxType) {}

    /** PATCH .../staff/{staffId} — 모두 선택, 보낸 것만 반영. */
    public record UpdateRequest(
            @Min(StoreWorkerDto.MIN_WAGE_2026) Integer hourlyWage,
            StoreWorker.TaxType taxType,
            Boolean weeklyHolidayAuto,
            Boolean premium,
            @Size(max = 50) String part,
            StoreWorker.Kind kind) {}
}
