package com.example.moneybug.dto;

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
            Integer employerId,
            String storeName,
            String address,
            String code,
            boolean underFive,
            boolean noticeRequiredDefault,
            boolean substitutePush,
            StoreWorker.TaxType defaultDeduction,
            LocalDateTime scheduleConfirmedAt,
            List<StorePartDto.Response> parts,
            /** "yyyy-MM" -> 확정된 월 인건비. 서버가 Payroll 을 집계해 채운다. */
            Map<String, Integer> monthlyTotals) {

        public static Response from(Store s, List<StorePart> parts, Map<String, Integer> monthlyTotals) {
            return new Response(
                    s.getId(),
                    s.getEmployer().getId(),
                    s.getStoreName(),
                    s.getAddress(),
                    s.getCode(),
                    s.isUnderFive(),
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
    public enum JoinResult { REQUESTED, ALREADY_MEMBER, ALREADY_PENDING, NOT_FOUND }

    public record JoinStoreRef(Integer id, String name) {}

    public record JoinResponse(JoinResult result, JoinStoreRef store) {}
}
