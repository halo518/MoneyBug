package com.example.moneybug.dto;

import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

public class SubstituteRequestDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        // [변경] scheduleId/requesterId 제거: 경로의 {shiftId} 와 JWT 로그인 사용자로 결정한다.
        // 설계서: 사유 필수 ("사유를 입력해 주세요")
        @NotBlank
        private String reason;

        private boolean notifyPeers = true;

        /** 연관 엔티티(근무표, 신청자)는 서비스에서 조회해 넘겨준다. 수락자(taker)는 수락 시 지정한다. */
        public SubstituteRequest toEntity(Schedule schedule, User requester) {
            SubstituteRequest request = new SubstituteRequest();
            request.setSchedule(schedule);
            request.setRequester(requester);
            request.setReason(reason);
            request.setNotifyPeers(notifyPeers);
            return request;
        }
    }

    /**
     * FE models.dart SubstituteRequest 와 같은 모양. FE 는 requesterId·acceptedId·applicantIds 를
     * 그 매장의 직원(StoreWorker) id 로 쓴다. 엔티티는 User 를 들고 있으므로 조립할 때 변환한다
     * ({@link com.example.moneybug.service.SubstituteAssembler}).
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        private Integer shiftId;
        private Integer requesterId;
        /** 대타 후보(수락한 직원) */
        private Integer acceptedId;
        private String reason;
        private SubstituteRequest.Status status;
        private boolean notifyPeers;
        private LocalDateTime createdAt;
        private LocalDateTime notifiedAt;
        /** 수락한 직원들 */
        private List<Integer> applicantIds;
        /** computed: open | accepted */
        private boolean pending;

        /** staffIdOf: 이 매장에서 그 User 의 직원 id (없으면 null). */
        public static Response from(SubstituteRequest r, List<User> applicants, Function<User, Integer> staffIdOf) {
            return Response.builder()
                    .id(r.getId())
                    .storeId(r.getSchedule().getStoreWorker().getStore().getId())
                    .shiftId(r.getSchedule().getId())
                    .requesterId(staffIdOf.apply(r.getRequester()))
                    .acceptedId(r.getTaker() == null ? null : staffIdOf.apply(r.getTaker()))
                    .reason(r.getReason())
                    .status(r.getStatus())
                    .notifyPeers(r.isNotifyPeers())
                    .createdAt(r.getCreatedAt())
                    .notifiedAt(r.getNotifiedAt() != null ? r.getNotifiedAt() : r.getCreatedAt())
                    .applicantIds(applicants.stream().map(staffIdOf).toList())
                    .pending(r.isPending())
                    .build();
        }
    }
}
