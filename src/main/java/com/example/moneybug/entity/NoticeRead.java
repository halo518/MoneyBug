package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** [신규] 공지 읽음 현황 (설계서 §3.8 readBy, §8 notice_reads). 키: (notice, staff). */
@Entity
@Table(name = "notice_read", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notice_read", columnNames = {"notice_id", "mapping_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class NoticeRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "read_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    // 읽은 직원 (설계서의 staffId)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private StoreWorker storeWorker;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt = LocalDateTime.now();
}
