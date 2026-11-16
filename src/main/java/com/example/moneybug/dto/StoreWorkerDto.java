package com.example.moneybug.dto;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class StoreWorkerDto {

    /** 2026 최저임금. 설계서 §10-7: 추후 설정 테이블로 분리 예정. */
    public static final int MIN_WAGE_2026 = 10320;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        private Integer storeId;

        @NotNull
        private Integer workerId;

        @NotNull
        @Min(MIN_WAGE_2026)
        private Integer hourlyWage;

        private StoreWorker.TaxType taxType = StoreWorker.TaxType.NONE;

        private StoreWorker.Status status = StoreWorker.Status.ACTIVE;

        @Size(max = 50)
        private String part;

        private StoreWorker.Kind kind = StoreWorker.Kind.PART_TIMER;

        private boolean weeklyHolidayAuto = true;
        private boolean premium = false;
        private StoreWorker.Tone tone = StoreWorker.Tone.PRIMARY;

        /** 연관 엔티티(매장, 직원)는 서비스에서 조회해 넘겨준다. */
        public StoreWorker toEntity(Store store, User worker) {
            StoreWorker storeWorker = new StoreWorker();
            storeWorker.setStore(store);
            storeWorker.setWorker(worker);
            storeWorker.setHourlyWage(hourlyWage);
            storeWorker.setTaxType(taxType);
            storeWorker.setStatus(status);
            storeWorker.setPart(part);
            storeWorker.setKind(kind);
            storeWorker.setWeeklyHolidayAuto(weeklyHolidayAuto);
            storeWorker.setPremium(premium);
            storeWorker.setTone(tone);
            if (status == StoreWorker.Status.ACTIVE) {
                storeWorker.setJoinedAt(LocalDate.now());
            }
            return storeWorker;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        private Integer workerId;
        private Integer hourlyWage;
        private StoreWorker.TaxType taxType;
        private StoreWorker.Status status;
        private String part;
        private StoreWorker.Kind kind;
        private boolean weeklyHolidayAuto;
        private boolean premium;
        private StoreWorker.Tone tone;
        private LocalDate joinedAt;
        private LocalDateTime requestedAt;

        public static Response from(StoreWorker storeWorker) {
            return Response.builder()
                    .id(storeWorker.getId())
                    .storeId(storeWorker.getStore().getId())
                    .workerId(storeWorker.getWorker().getId())
                    .hourlyWage(storeWorker.getHourlyWage())
                    .taxType(storeWorker.getTaxType())
                    .status(storeWorker.getStatus())
                    .part(storeWorker.getPart())
                    .kind(storeWorker.getKind())
                    .weeklyHolidayAuto(storeWorker.isWeeklyHolidayAuto())
                    .premium(storeWorker.isPremium())
                    .tone(storeWorker.getTone())
                    .joinedAt(storeWorker.getJoinedAt())
                    .requestedAt(storeWorker.getRequestedAt())
                    .build();
        }
    }
}
