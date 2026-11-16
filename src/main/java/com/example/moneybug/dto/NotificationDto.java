package com.example.moneybug.dto;

import com.example.moneybug.entity.Notification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 알림은 서버가 생성하므로 요청 DTO 는 없다 (설계서 §4.9). */
public class NotificationDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        private Integer toUserId;
        private Notification.Type type;
        private String title;
        private String body;
        private LocalDateTime createdAt;
        private boolean read;
        private Integer refId;

        public static Response from(Notification n) {
            return Response.builder()
                    .id(n.getId())
                    .storeId(n.getStore().getId())
                    .toUserId(n.getToUser().getId())
                    .type(n.getType())
                    .title(n.getTitle())
                    .body(n.getBody())
                    .createdAt(n.getCreatedAt())
                    .read(n.isRead())
                    .refId(n.getRefId())
                    .build();
        }
    }
}
