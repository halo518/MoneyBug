package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Entity
@Table(name = "store")
@Getter
@Setter
@NoArgsConstructor
public class Store {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Integer id;

    // 사장님. 1인 N매장 허용(설계서 §10-4는 1인 1매장 전제이나 기존 구조 유지)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employer_id", nullable = false)
    private User employer;

    @Column(name = "store_name", nullable = false, length = 100)
    private String storeName;

    // [추가] 도로명 주소 (생성 시 필수는 DTO 검증으로 처리)
    @Column(length = 255)
    private String address;

    // [추가] 직원 가입용 매장 코드 (예: CAFE-8K2D). 저장 시 비어 있으면 자동 생성한다.
    @Column(unique = true, length = 20)
    private String code;

    // 상시 5인 미만 사업장. true 면 가산수당 미적용 (설계서 isSmall)
    @Column(name = "is_under_5", nullable = false)
    private boolean underFive = true;

    // [추가] 공지 작성 시 '필독' 기본값
    @Column(name = "notice_required_default", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean noticeRequiredDefault = true;

    // [추가] 대타 요청 시 동료에게 푸시
    @Column(name = "substitute_push", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean substitutePush = true;

    // [추가] 가입 승인 때 기본 공제
    @Convert(converter = StoreWorker.TaxTypeConverter.class)
    @Column(name = "default_deduction", length = 20)
    private StoreWorker.TaxType defaultDeduction = StoreWorker.TaxType.BUSINESS_TAX;

    // [추가] 근무표 확정 시각
    @Column(name = "schedule_confirmed_at")
    private LocalDateTime scheduleConfirmedAt;

    /** 매장 코드 후보 생성 (예: BUG-8K2D). 중복 검사는 서비스에서 한다. */
    public static String newCode() {
        StringBuilder sb = new StringBuilder("BUG-");
        for (int i = 0; i < 4; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    // 서비스가 코드를 지정하지 않고 저장하는 경우의 안전장치
    @PrePersist
    void ensureCode() {
        if (code == null || code.isBlank()) {
            code = newCode();
        }
    }
}
