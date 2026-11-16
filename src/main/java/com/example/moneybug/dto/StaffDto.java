package com.example.moneybug.dto;

import com.example.moneybug.entity.StoreWorker;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 설계서 §3.4 StaffDto: 매장 소속 직원 + 사람(person) 조인 + 계약서. 필드명은 Entity 기준. */
public record StaffDto(
        Integer id,
        Integer storeId,
        UserDto.Response person,
        String part,
        StoreWorker.Kind kind,
        Integer hourlyWage,
        StoreWorker.TaxType taxType,
        boolean weeklyHolidayAuto,
        boolean premium,
        StoreWorker.Tone tone,
        StoreWorker.Status status,
        LocalDate joinedAt,
        LocalDateTime requestedAt,
        ContractDto.Response contract) {

    public static StaffDto from(StoreWorker sw, ContractDto.Response contract) {
        return new StaffDto(
                sw.getId(),
                sw.getStore().getId(),
                UserDto.Response.from(sw.getWorker()),
                sw.getPart(),
                sw.getKind(),
                sw.getHourlyWage(),
                sw.getTaxType(),
                sw.isWeeklyHolidayAuto(),
                sw.isPremium(),
                sw.getTone(),
                sw.getStatus(),
                sw.getJoinedAt(),
                sw.getRequestedAt(),
                contract);
    }
}
