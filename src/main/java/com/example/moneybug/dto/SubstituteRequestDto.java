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

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer scheduleId;
        private Integer requesterId;
        /** 대타 후보(acceptedId) */
        private Integer takerId;
        private String reason;
        private SubstituteRequest.Status status;
        private boolean notifyPeers;
        private LocalDateTime createdAt;
        private LocalDateTime notifiedAt;
        /** 수락한 직원들. 지원자 조인 테이블은 서비스에서 채운다. */
        private List<Integer> applicantIds;
        /** computed: open | accepted */
        private boolean pending;

        public static Response from(SubstituteRequest request) {
            return from(request, List.of());
        }

        public static Response from(SubstituteRequest request, List<Integer> applicantIds) {
            return Response.builder()
                    .id(request.getId())
                    .scheduleId(request.getSchedule().getId())
                    .requesterId(request.getRequester().getId())
                    .takerId(request.getTaker() == null ? null : request.getTaker().getId())
                    .reason(request.getReason())
                    .status(request.getStatus())
                    .notifyPeers(request.isNotifyPeers())
                    .createdAt(request.getCreatedAt())
                    .notifiedAt(request.getNotifiedAt())
                    .applicantIds(applicantIds)
                    .pending(request.isPending())
                    .build();
        }
    }
}
