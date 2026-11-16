package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [신규] 근로계약서 (설계서 §3.3, §4.4). 기존 Compliance.contractDocUrl 을 대체한다.
 * 이력 보관 방식: 직원(StoreWorker)당 여러 건을 쌓고, 가장 최근(createdAt) 건이 현재 계약서다.
 */
@Entity
@Table(name = "contract")
@Getter
@Setter
@NoArgsConstructor
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private StoreWorker storeWorker;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer wage;

    @Column(name = "weekly_hours", nullable = false)
    private Integer weeklyHours;

    // 자유 텍스트 (예: "월·수·금")
    @Column(name = "work_days", nullable = false, length = 50)
    private String workDays;

    @Column(nullable = false, length = 100)
    private String duty;

    @Column(name = "owner_signed_at")
    private LocalDateTime ownerSignedAt;

    @Column(name = "employee_signed_at")
    private LocalDateTime employeeSignedAt;

    // ---- 서명 증빙 (설계서 §4.4, §10-8: 법적 효력을 위해 이미지·IP·약관 버전 보관) ----

    @Column(name = "employee_signature_url", length = 500)
    private String employeeSignatureUrl;

    @Column(name = "employee_signed_ip", length = 45)
    private String employeeSignedIp;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    // 생성된 계약서 PDF 경로 (기존 Compliance.contractDocUrl 대체)
    @Column(name = "doc_url", length = 255)
    private String docUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 양쪽 서명 완료. */
    public boolean isSigned() {
        return ownerSignedAt != null && employeeSignedAt != null;
    }
}
