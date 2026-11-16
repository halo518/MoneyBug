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

    /** 사업장 규모 모드. AUTO 면 재직 인원 5명 미만일 때 underFive 가 자동으로 맞춰진다. */
    public enum SizeMode {
        AUTO, UNDER5, OVER5;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static SizeMode fromJson(String v) { return EnumJson.parse(SizeMode.class, v); }
    }

    public static final int DEFAULT_OPEN_MIN = 480;
    public static final int DEFAULT_CLOSE_MIN = 1320;

    // 영업시간(분). 마감 > 1440 이면 익일. 기존 행은 null 이라 getter 가 기본값을 돌려준다.
    @Column(name = "open_min")
    private Integer openMin = DEFAULT_OPEN_MIN;

    @Column(name = "close_min")
    private Integer closeMin = DEFAULT_CLOSE_MIN;

    @Column(name = "weekend_open_min")
    private Integer weekendOpenMin = DEFAULT_OPEN_MIN;

    @Column(name = "weekend_close_min")
    private Integer weekendCloseMin = DEFAULT_CLOSE_MIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_mode", length = 10)
    private SizeMode sizeMode = SizeMode.AUTO;

    public int getOpenMin() { return openMin == null ? DEFAULT_OPEN_MIN : openMin; }
    public int getCloseMin() { return closeMin == null ? DEFAULT_CLOSE_MIN : closeMin; }
    public int getWeekendOpenMin() { return weekendOpenMin == null ? DEFAULT_OPEN_MIN : weekendOpenMin; }
    public int getWeekendCloseMin() { return weekendCloseMin == null ? DEFAULT_CLOSE_MIN : weekendCloseMin; }

    /** 기존 행(null)은 저장돼 있던 underFive 를 수동 모드로 이어받는다. */
    public SizeMode getSizeMode() {
        if (sizeMode != null) return sizeMode;
        return underFive ? SizeMode.UNDER5 : SizeMode.OVER5;
    }

    /** 실제 적용되는 5인 미만 여부. AUTO 면 재직 인원으로 계산. */
    public boolean effectiveUnderFive(long activeCount) {
        return switch (getSizeMode()) {
            case AUTO -> activeCount < 5;
            case UNDER5 -> true;
            case OVER5 -> false;
        };
    }

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

    /** 매장 코드 후보 생성 (예: CAFE-8K2D). FE 입력칸이 8칸이라 영숫자 8자 + 가운데 '-'. 중복 검사는 서비스에서 한다. */
    public static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
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
