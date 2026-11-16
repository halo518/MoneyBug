package com.example.moneybug.dto;

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
        @Size(max = 100)
        private String storeName;

        @Size(max = 255)
        private String address;

        private Boolean underFive;

        private StoreWorker.TaxType defaultDeduction;
    }

    /** PATCH /stores/{id}/settings — 보낸 것만 반영하기 위해 래퍼 타입 사용. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SettingsRequest {
        private Boolean underFive;
        private Boolean noticeRequiredDefault;
        private Boolean substitutePush;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer employerId;
        private String storeName;
        private String address;
        private String code;
        private boolean underFive;
        private boolean noticeRequiredDefault;
        private boolean substitutePush;
        private StoreWorker.TaxType defaultDeduction;
        private LocalDateTime scheduleConfirmedAt;

        public static Response from(Store store) {
            return Response.builder()
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
