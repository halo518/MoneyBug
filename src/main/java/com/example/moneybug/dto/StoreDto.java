package com.example.moneybug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

public class StoreDto {

    // 매장 생성 요청은 StoreCreateRequest 로 분리했다 (설계서 §4.2: name, address, isSmall, defaultDeduction).

    /** PATCH /stores/{id} — 이름·주소·규모·기본공제 수정. 보낸 것만 반영하기 위해 래퍼 타입 사용. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class UpdateRequest {
        @JsonProperty("name")
        private String storeName; // 길이는 trim 뒤 StoreService 가 검사(한국어 문구)

        private String address;

        @JsonProperty("isSmall")
        private Boolean underFive;

        private StoreWorker.TaxType defaultDeduction;
    }

    /** PATCH /stores/{id}/settings — 보낸 것만 반영하기 위해 래퍼 타입 사용. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SettingsRequest {
        @JsonProperty("name")
        private String storeName; // 길이는 trim 뒤 StoreService 가 검사(한국어 문구)
        private Integer openMin;
        private Integer closeMin;
        private Integer weekendOpenMin;
        private Integer weekendCloseMin;
        private Store.SizeMode sizeMode;
        @JsonProperty("isSmall")
        private Boolean underFive;
        private Boolean noticeRequiredDefault;
        private Boolean substitutePush;
        private StoreWorker.TaxType defaultDeduction;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        @JsonProperty("ownerPersonId")
        private Integer employerId;
        @JsonProperty("name")
        private String storeName;
        private String address;
        private String code;
        @JsonProperty("isSmall")
        private boolean underFive;
        private boolean noticeRequiredDefault;
        private int openMin;
        private int closeMin;
        private int weekendOpenMin;
        private int weekendCloseMin;
        private Store.SizeMode sizeMode;
        private boolean substitutePush;
        private StoreWorker.TaxType defaultDeduction;
        private LocalDateTime scheduleConfirmedAt;

        public static Response from(Store store) {
            return Response.builder()
                    .openMin(store.getOpenMin())
                    .closeMin(store.getCloseMin())
                    .weekendOpenMin(store.getWeekendOpenMin())
                    .weekendCloseMin(store.getWeekendCloseMin())
                    .sizeMode(store.getSizeMode())
                    .id(store.getId())
                    .employerId(store.getEmployer().getId())
                    .storeName(store.getStoreName())
                    .address(store.getAddress())
                    .code(store.getCode())
                    .underFive(store.isUnderFive())
                    .noticeRequiredDefault(store.isNoticeRequiredDefault())
                    .substitutePush(store.isSubstitutePush())
                    .defaultDeduction(store.getDefaultDeduction())
                    .scheduleConfirmedAt(store.getScheduleConfirmedAt())
                    .build();
        }
    }
}
