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
        List<StaffPrefs.WorkHours> workHours,
        List<StaffPrefs.WorkHours> availHours,
        List<WageEntry> wages,
        LocalDate leftAt) {

    /** 시급 이력 한 줄: from 부터 wage. */
    public record WageEntry(LocalDate from, int wage) {}


    /** 사장님·본인이 아닌 사람에게 줄 모습: 연락처·시급·공제·계약서를 뺀다 (FE 는 wage 0, deduction null 을 받는다). */
    public StaffDto forViewer(boolean isOwner, Integer viewerUserId) {
        if (isOwner || personId.equals(viewerUserId)) return this;
        UserDto.Response p = person == null ? null : UserDto.Response.builder()
                .id(person.getId())
                .name(person.getName())
                .fullName(person.getFullName())
                .certExpiry(person.getCertExpiry())
                .certAlertDays(person.getCertAlertDays())
                .certHasPhoto(person.isCertHasPhoto())
                .certDaysLeft(person.getCertDaysLeft())
                .build();
        return new StaffDto(id, storeId, personId, p, part, kind, 0, null, weeklyHolidayAuto,
                false, tone, status, joinedAt, requestedAt, null, fixedParts, workHours, availHours, List.of(), leftAt);
    }

    public static StaffDto from(StoreWorker sw, ContractDto.Response contract) {
        return from(sw, contract, List.of());
    }

    public static StaffDto from(StoreWorker sw, ContractDto.Response contract,
                                List<com.example.moneybug.entity.StaffWage> wageRows) {
        return new StaffDto(
                sw.getId(),
                sw.getStore().getId(),
                sw.getWorker().getId(),
                UserDto.Response.from(sw.getWorker()),
                sw.getPart() == null ? "" : sw.getPart(), // FE 는 part·joinedAt 을 null 로 받지 못한다
                sw.getKind(),
                com.example.moneybug.service.StaffWageService.wageOn(wageRows,
                        com.example.moneybug.service.TimeUtil.today(), sw.getHourlyWage()),
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
                StaffPrefs.workHours(sw),
                StaffPrefs.availHours(sw),
                wageRows.stream().map(w -> new WageEntry(w.getEffectiveFrom(), w.getWage())).toList(),
                sw.getLeftAt());
    }
}
