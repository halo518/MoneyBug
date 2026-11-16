package com.example.moneybug.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 매장 소속 직원 (설계서 Staff). */
@Entity
@Table(name = "store_worker")
@Getter
@Setter
@NoArgsConstructor
public class StoreWorker {

    // DB 값 '4_INSURANCE' 는 자바 식별자로 쓸 수 없어 변환기로 매핑한다.
    // (설계서 Deduction: biz33 / insurance / none 에 대응. JSON 이름은 기존 Java enum 이름 유지)
    // JSON 요청/응답 값은 설계서 §2 Deduction 의 소문자 이름(biz33 / insurance / none)을 쓴다.
    //   biz33 = 원천징수 3.3%, insurance = 4대보험, none = 공제 없음
    // DB 값(dbValue)과 JSON 값(jsonValue)은 서로 독립이다. DB 는 TaxTypeConverter 가 dbValue 로 저장한다.
    public enum TaxType {
        BUSINESS_TAX("BUSINESS_TAX", "biz33"),
        FOUR_INSURANCE("4_INSURANCE", "insurance"),
        NONE("NONE", "none");

        private final String dbValue;
        private final String jsonValue;

        TaxType(String dbValue, String jsonValue) {
            this.dbValue = dbValue;
            this.jsonValue = jsonValue;
        }

        public String getDbValue() {
            return dbValue;
        }

        /** 직렬화: 응답에는 항상 "biz33" / "insurance" / "none" 으로 나간다. */
        @JsonValue
        public String getJsonValue() {
            return jsonValue;
        }

        /**
         * 역직렬화: "biz33" / "insurance" / "none" (대소문자 무시)을 받는다.
         * 이전에 쓰던 Java 이름(BUSINESS_TAX, FOUR_INSURANCE)도 하위 호환으로 받아 준다.
         */
        @JsonCreator
        public static TaxType fromJson(String value) {
            if (value == null) {
                return null;
            }
            String v = value.trim();
            for (TaxType t : values()) {
                if (t.jsonValue.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                    return t;
                }
            }
            throw new IllegalArgumentException(
                    "알 수 없는 공제 유형이에요: '" + value + "' (biz33, insurance, none 중 하나)");
        }
    }

    @Converter
    public static class TaxTypeConverter implements AttributeConverter<TaxType, String> {
        @Override
        public String convertToDatabaseColumn(TaxType attribute) {
            return attribute == null ? null : attribute.getDbValue();
        }

        @Override
        public TaxType convertToEntityAttribute(String dbData) {
            if (dbData == null) return null;
            for (TaxType t : TaxType.values()) {
                if (t.getDbValue().equals(dbData)) return t;
            }
            throw new IllegalArgumentException("Unknown tax_type: " + dbData);
        }
    }

    // [추가] 설계서 StaffStatus. 가입 신청 = PENDING. 기존 문자열 'ACTIVE' 와 호환된다.
    public enum Status {
        PENDING, ACTIVE, LEFT, REJECTED;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Status fromJson(String v) { return EnumJson.parse(Status.class, v); }
    }

    // [추가] 설계서 StaffKind. MANAGER/FULLTIME 은 유동 근무(part = "직원", 시간 직접 지정)
    public enum Kind {
        MANAGER, FULLTIME, PART_TIMER, MINOR;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Kind fromJson(String v) { return EnumJson.parse(Kind.class, v); }
    }

    // [추가] 설계서 Tone. 아바타 색(표시용)
    public enum Tone {
        PRIMARY, ACCENT, INFO, DANGER;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Tone fromJson(String v) { return EnumJson.parse(Tone.class, v); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Integer id;

    // 재가입 허용 정책에 따라 (store, worker) unique 제약은 두지 않는다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private User worker;

    @Column(name = "hourly_wage", nullable = false)
    private Integer hourlyWage;

    @Convert(converter = TaxTypeConverter.class)
    @Column(name = "tax_type", nullable = false, length = 20)
    private TaxType taxType = TaxType.NONE;

    // [변경] String -> enum (DB 에는 문자열로 저장)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    // [추가] 파트명 (유동 근무자는 "직원")
    @Column(length = 50)
    private String part;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Kind kind = Kind.PART_TIMER;

    // [추가] 주휴수당 자동 계산
    @Column(name = "weekly_holiday_auto", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean weeklyHolidayAuto = true;

    // [추가] 가산수당 적용 (5인 이상 매장에서만 의미 있음)
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean premium = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Tone tone = Tone.PRIMARY;

    // 알바생 고정 근무: JSON [[파트이름...] x7] (월~일). StaffPrefs 로 읽고 쓴다.
    @Column(name = "fixed_parts", columnDefinition = "TEXT")
    private String fixedParts;

    // 직원 요일별 출퇴근: JSON [{startMin,endMin}|null x7] 또는 null.
    @Column(name = "work_hours", columnDefinition = "TEXT")
    private String workHours;

    // [추가] 입사일(승인일)
    @Column(name = "joined_at")
    private LocalDate joinedAt;

    // [추가] 가입 신청 시각
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;
}
