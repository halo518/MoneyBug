package com.example.moneybug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.service.StaffPrefs;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 직원 관련 쓰기 API 요청 (설계서 §4.3). JSON 이름은 FE AppStore 인자(wage, deduction)와 같게. */
public class StaffCommandDto {

    /** POST .../approve — taxType 생략 시 store.defaultDeduction. */
    public record ApproveRequest(
            @JsonProperty("wage") @NotNull @Min(StoreWorkerDto.MIN_WAGE_2026) Integer hourlyWage,
            @JsonProperty("deduction") StoreWorker.TaxType taxType) {}

    /** PATCH .../staff/{staffId} — 모두 선택, 보낸 것만 반영. */
    public record UpdateRequest(
            @JsonProperty("wage") @Min(StoreWorkerDto.MIN_WAGE_2026) Integer hourlyWage,
            @JsonProperty("deduction") StoreWorker.TaxType taxType,
            Boolean weeklyHolidayAuto,
            Boolean premium,
            @Size(max = 50) String part,
            StoreWorker.Kind kind,
            List<List<String>> fixedParts,
            List<StaffPrefs.WorkHours> workHours) {}
}
