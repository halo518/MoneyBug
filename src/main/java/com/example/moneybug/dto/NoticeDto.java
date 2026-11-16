package com.example.moneybug.dto;

import com.example.moneybug.entity.Notice;
import com.example.moneybug.entity.Store;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

public class NoticeDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        // [변경] storeId 제거: 경로의 {storeId} 로 결정한다.
        // 설계서: 제목·내용이 trim 후 비면 400
        @NotBlank
        @Size(max = 200)
        private String title;

        @NotBlank
        private String content;

        private Notice.Category category = Notice.Category.GENERAL;

        // 생략(null) 시 store.noticeRequiredDefault 를 서비스에서 적용
        private Boolean required;

        /** 연관 엔티티(매장)는 서비스에서 조회해 넘겨준다. */
        public Notice toEntity(Store store) {
            Notice notice = new Notice();
            notice.setStore(store);
            notice.setTitle(title);
            notice.setContent(content);
            notice.setCategory(category);
            notice.setRequired(required != null ? required : store.isNoticeRequiredDefault());
            return notice;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        private String title;
        private String content;
        private Notice.Category category;
        private boolean required;
        private boolean pinned;
        private LocalDateTime createdAt;
        private LocalDateTime remindedAt;

        public static Response from(Notice notice) {
            return Response.builder()
                    .id(notice.getId())
                    .storeId(notice.getStore().getId())
                    .title(notice.getTitle())
                    .content(notice.getContent())
                    .category(notice.getCategory())
                    .required(notice.isRequired())
                    .pinned(notice.isPinned())
                    .createdAt(notice.getCreatedAt())
                    .remindedAt(notice.getRemindedAt())
                    .build();
        }
    }
}
