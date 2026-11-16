package com.example.moneybug.dto;

import com.example.moneybug.entity.Notice;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 설계서 §3.8 NoticeDto(읽음 정보 포함)와 §4.8 응답. */
public class NoticeViewDto {

    public record View(
            Integer id,
            Integer storeId,
            String title,
            @JsonProperty("body") String content,
            Notice.Category category,
            boolean required,
            boolean pinned,
            LocalDateTime createdAt,
            LocalDateTime remindedAt,
            /** staffId -> 읽은 시각. 사장님은 전원, 직원은 본인 것만 */
            Map<Integer, LocalDateTime> readBy,
            /** computed: 요청자 기준 */
            @JsonProperty("isRead") boolean isRead,
            /** computed: 사장님 화면 */
            Integer unreadCount) {

        public static View from(Notice n, Map<Integer, LocalDateTime> readBy, boolean isRead, Integer unreadCount) {
            // 예전 행은 title 이 null 일 수 있다. FE 는 null 을 못 받는다.
            return new View(n.getId(), n.getStore().getId(), n.getTitle() == null ? "" : n.getTitle(),
                    n.getContent() == null ? "" : n.getContent(), n.getCategory(),
                    n.isRequired(), n.isPinned(), n.getCreatedAt(), n.getRemindedAt(), readBy, isRead, unreadCount);
        }
    }

    /** GET /notices — 정렬: 고정 먼저 -> 최신순. unreadNoticeCount 는 내 readBy 에 없는 공지 수. */
    public record ListResponse(List<View> items, String nextCursor, int unreadNoticeCount) {}

    public record ReadEntry(Integer staffId, LocalDateTime readAt) {}

    /** GET /notices/{id}/read-status */
    public record ReadStatusResponse(List<ReadEntry> read, List<StaffDto> unread) {}

    /** POST /notices/{id}/remind */
    public record RemindResponse(int sent) {}
}
