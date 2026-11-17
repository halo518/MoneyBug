package com.example.moneybug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 설계서 §3.2 StoreDto: 매장 + 파트(parts) + 월별 인건비 추이(monthlyTotals). */
public class StoreDetailDto {

    public record Response(
            Integer id,
            // JSON 이름은 FE models.dart Store 와 같게
            @JsonProperty("ownerPersonId") Integer employerId,
            @JsonProperty("name") String storeName,
            String address,
            String code,
            @JsonProperty("isSmall") boolean underFive,
            int openMin,
            int closeMin,
            int weekendOpenMin,
            int weekendCloseMin,
            Store.SizeMode sizeMode,
            boolean noticeRequiredDefault,
            boolean substitutePush,
            StoreWorker.TaxType defaultDeduction,
            LocalDateTime scheduleConfirmedAt,
            List<StorePartDto.Response> parts,
            /** "yyyy-MM" -> 확정된 월 인건비. 서버가 Payroll 을 집계해 채운다. */
            Map<String, Integer> monthlyTotals) {

        /** 사장님이 아닌 사람에게: 가입 코드와 월별 인건비 추이를 뺀다. */
        public Response withoutOwnerData() {
            return new Response(id, employerId, storeName, address, "", underFive, openMin, closeMin,
                    weekendOpenMin, weekendCloseMin, sizeMode, noticeRequiredDefault, substitutePush,
                    defaultDeduction, scheduleConfirmedAt, parts, Map.of());
        }

        public static Response from(Store s, List<StorePart> parts, Map<String, Integer> monthlyTotals) {
            return from(s, parts, monthlyTotals, s.isUnderFive());
        }

        /** underFive: 실제 적용값(AUTO 면 재직 인원으로 계산한 값). */
        public static Response from(Store s, List<StorePart> parts, Map<String, Integer> monthlyTotals,
                                    boolean underFive) {
            return new Response(
                    s.getId(),
                    s.getEmployer().getId(),
                    s.getStoreName(),
                    s.getAddress(),
                    s.getCode(),
                    underFive,
                    s.getOpenMin(),
                    s.getCloseMin(),
                    s.getWeekendOpenMin(),
                    s.getWeekendCloseMin(),
                    s.getSizeMode(),
                    s.isNoticeRequiredDefault(),
                    s.isSubstitutePush(),
                    s.getDefaultDeduction(),
                    s.getScheduleConfirmedAt(),
                    parts.stream().map(StorePartDto.Response::from).toList(),
                    monthlyTotals);
        }
    }

    /** GET /stores/{id}/code */
    public record CodeResponse(String code) {}

    /** PUT /stores/{id}/parts/needs — store.parts 순서대로의 필요 인원. 인덱스가 모자라면 무시. */
    public record PartNeedsRequest(@jakarta.validation.constraints.NotNull List<Integer> needs) {}

    /** POST /stores/join-requests 요청 */
    public record JoinRequest(@jakarta.validation.constraints.NotBlank String code) {}

    /** 가입 결과 (설계서 JoinResult) */
    public enum JoinResult {
        REQUESTED, ALREADY_MEMBER, ALREADY_PENDING, NOT_FOUND;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return com.example.moneybug.entity.EnumJson.camel(this); }
    }

    public record JoinStoreRef(Integer id, String name) {}

    public record JoinResponse(JoinResult result, JoinStoreRef store) {}
}
