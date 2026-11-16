package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** 근무 (설계서 Shift). */
@Entity
@Table(name = "schedule", indexes = {
        @Index(name = "idx_schedule_mapping_date", columnList = "mapping_id, work_date")
})
@Getter
@Setter
@NoArgsConstructor
public class Schedule {

    // [추가] 설계서 ShiftKind. 연차는 유급, 휴무는 무급
    public enum Kind {
        WORK, LEAVE, OFF;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Kind fromJson(String v) { return EnumJson.parse(Kind.class, v); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private StoreWorker storeWorker;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    // [변경] LocalTime start_time/end_time -> 하루 기준 분 정수 (09:30 -> 570)
    // endMin <= startMin 이면 야간(다음 날까지). endAbs = endMin + 1440 (설계서 §1.3)
    @Column(name = "start_min", nullable = false)
    private Integer startMin;

    @Column(name = "end_min", nullable = false)
    private Integer endMin;

    // [추가] 파트명
    @Column(length = 50)
    private String part;

    // [추가] 휴게 분. 미지정 시 서비스에서 자동 계산 (480분 이상 60, 240분 이상 30, 그 외 0)
    @Column(name = "break_min", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
    private int breakMin = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Kind kind = Kind.WORK;

    // [추가] 시간 미정. true 면 시각은 '추천값'
    @Column(name = "time_tbd", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean timeTbd = false;

    @Column(name = "is_substitute", nullable = false)
    private boolean substitute = false;

    // [추가] 대타로 바뀐 경우 원래 근무자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_mapping_id")
    private StoreWorker originalStoreWorker;

    // [추가] 낙관적 락 (설계서 §10-3)
    @Version
    @Column(nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long version;

    // ---- 파생값 (컬럼 아님) ----

    /** kind == WORK && !timeTbd. 출퇴근·근태·급여·대타는 이것만 대상. */
    public boolean isWork() {
        return kind == Kind.WORK && !timeTbd;
    }

    /** 야간이면 다음 날 기준으로 보정한 종료 분. */
    public int endAbs() {
        return endMin <= startMin ? endMin + 1440 : endMin;
    }

    /** 소정 근로 분 = endAbs - startMin - breakMin. */
    public int scheduledMinutes() {
        return endAbs() - startMin - breakMin;
    }
}
