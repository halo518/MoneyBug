package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** [신규] 대타 지원자 조인 테이블 (설계서 §3.7 applicantIds, §8 substitute_applicants). */
@Entity
@Table(name = "substitute_applicant", uniqueConstraints = {
        @UniqueConstraint(name = "uk_substitute_applicant", columnNames = {"request_id", "applicant_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class SubstituteApplicant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "applicant_row_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SubstituteRequest request;

    // 기존 requester/taker 와 동일하게 User 를 참조한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id", nullable = false)
    private User applicant;

    @CreationTimestamp
    @Column(name = "applied_at", updatable = false)
    private LocalDateTime appliedAt;
}
