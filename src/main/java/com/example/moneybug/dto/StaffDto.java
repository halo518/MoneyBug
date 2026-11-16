package com.example.moneybug.dto;

import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.service.StaffPrefs;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 설계서 §3.4 StaffDto: 매장 소속 직원 + 사람(person) 조인 + 계약서. 필드명은 Entity 기준. */
public record StaffDto(
        Integer id,
        Integer storeId,
        Integer personId,
        UserDto.Response person,
        String part,
        StoreWorker.Kind kind,
        // JSON 이름은 FE models.dart Staff 와 같게
        @JsonProperty("wage") Integer hourlyWage,
        @JsonProperty("deduction") StoreWorker.TaxType taxType,
        boolean weeklyHolidayAuto,
        boolean premium,
        StoreWorker.Tone tone,
        StoreWorker.Status status,
        LocalDate joinedAt,
        LocalDateTime requestedAt,
        ContractDto.Response contract,
        List<List<String>> fixedParts,
        List<StaffPrefs.WorkHours> workHours) {

    /** 다른 직원의 계약서를 숨길 때 (직원 화면용 /me/state). */
    public StaffDto withoutContract() {
        return new StaffDto(id, storeId, personId, person, part, kind, hourlyWage, taxType, weeklyHolidayAuto,
                premium, tone, status, joinedAt, requestedAt, null, fixedParts, workHours);
    }

    public static StaffDto from(StoreWorker sw, ContractDto.Response contract) {
        return new StaffDto(
                sw.getId(),
                sw.getStore().getId(),
                sw.getWorker().getId(),
                UserDto.Response.from(sw.getWorker()),
                sw.getPart() == null ? "" : sw.getPart(), // FE 는 part·joinedAt 을 null 로 받지 못한다
                sw.getKind(),
                sw.getHourlyWage(),
                sw.getTaxType(),
                sw.isWeeklyHolidayAuto(),
                sw.isPremium(),
                sw.getTone(),
                sw.getStatus(),
                sw.getJoinedAt() != null ? sw.getJoinedAt()
                        : sw.getRequestedAt() != null ? sw.getRequestedAt().toLocalDate() : null,
                sw.getRequestedAt(),
                contract,
                StaffPrefs.fixedParts(sw),
                StaffPrefs.workHours(sw));
    }
}
