package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "substitute_request")
@Getter
@Setter
@NoArgsConstructor
public class SubstituteRequest {

    // [변경] 설계서 SubStatus 에 맞춤: open / accepted / approved / rejected / cancelled
    //   OPEN, ACCEPTED(동료 수락, 사장님 승인 대기) 가 '진행 중'.
    //   기존 PENDING -> OPEN, CANCELED -> CANCELLED (schema.sql 마이그레이션 참고)
    public enum Status { OPEN, ACCEPTED, APPROVED, REJECTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    // 대타 후보 (설계서 acceptedId: 첫 수락자 또는 사장님 직접 배정). 수락 전에는 NULL
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "taker_id")
    private User taker;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    // [추가] 동료에게 알릴지 여부
    @Column(name = "notify_peers", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyPeers = true;

    // [추가]
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // [추가] 마지막 재알림 시각
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** open | accepted */
    public boolean isPending() {
        return status == Status.OPEN || status == Status.ACCEPTED;
    }
}
