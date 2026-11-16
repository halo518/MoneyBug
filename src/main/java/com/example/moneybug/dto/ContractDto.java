package com.example.moneybug.dto;

import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.StoreWorker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class ContractDto {

    /** PUT .../contract — 사장님이 작성하고 서명해서 보낸다. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        // [변경] mappingId 제거: 경로의 {staffId} 로 결정한다.
        @NotNull
        private LocalDate startDate;

        @NotNull
        private LocalDate endDate;

        @NotNull
        @Min(StoreWorkerDto.MIN_WAGE_2026)
        private Integer wage;

        @NotNull
        @Min(1)
        private Integer weeklyHours;

        @NotBlank
        @Size(max = 50)
        private String workDays;

        @NotBlank
        @Size(max = 100)
        private String duty;

        // 설계서: end > start
        @AssertTrue(message = "종료일은 시작일 이후여야 합니다")
        public boolean isEndAfterStart() {
            return startDate == null || endDate == null || endDate.isAfter(startDate);
        }

        /** 사장님 서명 시각(ownerSignedAt)은 저장 시점 서버 시각으로 기록한다. */
        public Contract toEntity(StoreWorker storeWorker) {
            Contract contract = new Contract();
            contract.setStoreWorker(storeWorker);
            contract.setStartDate(startDate);
            contract.setEndDate(endDate);
            contract.setWage(wage);
            contract.setWeeklyHours(weeklyHours);
            contract.setWorkDays(workDays);
            contract.setDuty(duty);
            contract.setOwnerSignedAt(LocalDateTime.now());
            return contract;
        }
    }

    /** POST .../contract/sign */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SignRequest {
        // 서명 이미지 업로드 경로/ID
        @NotBlank
        @Size(max = 500)
        private String signatureUrl;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer mappingId;
        private LocalDate startDate;
        private LocalDate endDate;
        private Integer wage;
        private Integer weeklyHours;
        private String workDays;
        private String duty;
        private LocalDateTime ownerSignedAt;
        private LocalDateTime employeeSignedAt;
        private String docUrl;
        /** computed: 양쪽 서명 완료 */
        private boolean signed;

        public static Response from(Contract c) {
            return Response.builder()
                    .id(c.getId())
                    .mappingId(c.getStoreWorker().getId())
                    .startDate(c.getStartDate())
                    .endDate(c.getEndDate())
                    .wage(c.getWage())
                    .weeklyHours(c.getWeeklyHours())
                    .workDays(c.getWorkDays())
                    .duty(c.getDuty())
                    .ownerSignedAt(c.getOwnerSignedAt())
                    .employeeSignedAt(c.getEmployeeSignedAt())
                    .docUrl(c.getDocUrl())
                    .signed(c.isSigned())
                    .build();
        }
    }
}
