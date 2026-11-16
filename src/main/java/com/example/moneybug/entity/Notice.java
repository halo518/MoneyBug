package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notice")
@Getter
@Setter
@NoArgsConstructor
public class Notice {

    // [추가] 설계서 §10-10: 한글 상수(일반/중요/노무)를 enum 코드값으로 정리. 표시 문구는 클라이언트.
    public enum Category { GENERAL, IMPORTANT, LABOR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // [추가] 제목 (기존 행 호환을 위해 DB 는 NULL 허용, 작성 시 필수는 DTO 검증)
    @Column(length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // [추가]
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category = Category.GENERAL;

    // [추가] 필독 여부
    @Column(name = "is_required", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean required = false;

    // [추가] 고정 여부. 정렬: 고정 먼저 -> 최신순 (현재 변경 UI 없음)
    @Column(name = "is_pinned", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean pinned = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // [추가] 마지막 재알림 시각
    @Column(name = "reminded_at")
    private LocalDateTime remindedAt;
}
