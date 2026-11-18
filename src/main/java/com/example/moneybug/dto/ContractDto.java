package com.example.moneybug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.StoreWorker;
import jakarta.validation.constraints.Max;
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

    /** 계약서 시급 상한 (오타로 이력·급여 계산이 넘치는 것 방지). */
    public static final int MAX_WAGE = 1_000_000;

    /** PUT .../contract — 사장님이 작성하고 서명해서 보낸다. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        // [변경] mappingId 제거: 경로의 {staffId} 로 결정한다.
        @NotNull(message = "계약 시작일을 입력해 주세요")
        @JsonProperty("start")
        private LocalDate startDate;

        @NotNull(message = "계약 종료일을 입력해 주세요")
        @JsonProperty("end")
        private LocalDate endDate;

        // 최저임금(WAGE_BELOW_MINIMUM)·종료일>시작일(INVALID_CONTRACT_PERIOD)은 서비스가 한국어 코드로 검사한다
        @NotNull(message = "시급을 입력해 주세요")
        @Max(value = MAX_WAGE, message = "시급은 1,000,000원 이하여야 해요")
        private Integer wage;

        @NotNull(message = "주 소정근로시간을 입력해 주세요")
        @Min(value = 1, message = "주 소정근로시간은 1시간 이상이어야 해요")
        @Max(value = 52, message = "주 소정근로시간은 52시간 이하여야 해요")
        private Integer weeklyHours;

        // 글자 수(50) 검사는 서비스가 한다. 여기는 UTF-16 단위 상한만.
        @NotBlank(message = "근무 요일을 입력해 주세요")
        @Size(max = 100, message = "근무 요일은 50자 이하로 적어 주세요")
        private String workDays;

        // 비어도 된다 (FE 가 비워 보낼 수 있음). 글자 수(100) 검사는 서비스가 한다.
        @Size(max = 200, message = "업무 내용은 100자 이하로 적어 주세요")
        private String duty;

        /** POST /me/signatures 로 올린 사장님 서명 id (선택). */
        @Size(max = 500, message = "서명 이미지 경로가 너무 길어요")
        private String ownerSignatureUrl;

        /** 사장님 서명 시각(ownerSignedAt)은 저장 시점 서버 시각으로 기록한다. */
        public Contract toEntity(StoreWorker storeWorker) {
            Contract contract = new Contract();
            contract.setStoreWorker(storeWorker);
            contract.setStartDate(startDate);
            contract.setEndDate(endDate);
            contract.setWage(wage);
            contract.setWeeklyHours(weeklyHours);
            contract.setWorkDays(workDays);
            contract.setDuty(duty == null ? "" : duty);
            contract.setOwnerSignatureUrl(ownerSignatureUrl);
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
        @NotBlank(message = "서명 이미지를 올려 주세요")
        @Size(max = 500, message = "서명 이미지 경로가 너무 길어요")
        private String signatureUrl;

        /** 직원이 본 계약서 id (선택). 현재 계약서가 아니면 409 CONTRACT_CHANGED. 없으면 현재 계약서에 서명. */
        private Integer contractId;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer mappingId;
        // JSON 이름은 FE models.dart Contract 와 같게
        @JsonProperty("start")
        private LocalDate startDate;
        @JsonProperty("end")
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
