package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 직원 시급 이력. effective_from 부터 wage 적용. */
@Entity
@Table(name = "staff_wage")
@Getter
@Setter
@NoArgsConstructor
public class StaffWage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wage_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private StoreWorker storeWorker;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false)
    private Integer wage;

    /** 이 단계를 만든 계약서 id. null = 사장님이 직접 바꾼 시급(또는 이 열이 생기기 전 행). */
    @Column(name = "contract_id")
    private Integer contractId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
