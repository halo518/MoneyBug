package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** [신규] 매장 파트(오픈·미들·마감 등) (설계서 §3.2 parts[], §8 store_parts). */
@Entity
@Table(name = "store_part", uniqueConstraints = {
        @UniqueConstraint(name = "uk_store_part_name", columnNames = {"store_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
public class StorePart {

    /** 파트 색(FE 표시용). */
    public enum Tone {
        OPEN, MIDDLE, CLOSE;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Tone fromJson(String v) { return EnumJson.parse(Tone.class, v); }
    }

    /** 파트가 쓰이는 날. WEEKEND = 토·일·공휴일, WEEKDAY = 그 밖의 날. */
    public enum Days {
        ALL, WEEKDAY, WEEKEND;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Days fromJson(String v) { return EnumJson.parse(Days.class, v); }

        public boolean appliesOn(java.time.LocalDate d) {
            return this == ALL || (this == WEEKEND) == com.example.moneybug.util.Holidays.isWeekendOrHoliday(d);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "part_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 50)
    private String name;

    // 하루 기준 분 (Schedule 과 동일 규칙)
    @Column(name = "start_min", nullable = false)
    private Integer startMin;

    @Column(name = "end_min", nullable = false)
    private Integer endMin;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Tone tone = Tone.MIDDLE;

    // 휴게 분. 기존 행은 null 이라 getter 가 0 을 돌려준다.
    @Column(name = "break_min")
    private Integer breakMin = 30;

    public Tone getTone() { return tone == null ? Tone.MIDDLE : tone; }

    public int getBreakMin() { return breakMin == null ? 0 : breakMin; }

    // true = 직원 파트, false = 알바 파트. 기존 행은 null 이라 false.
    @Column(name = "flex")
    private Boolean flex = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "days", length = 10)
    private Days days = Days.ALL;

    public boolean isFlex() { return Boolean.TRUE.equals(flex); }

    public Days getDays() { return days == null ? Days.ALL : days; }

    /** 그날 이 파트의 필요 인원이 적용되는지. */
    public boolean appliesOn(java.time.LocalDate d) { return getDays().appliesOn(d); }

    // 그 파트의 하루 필요 인원
    @Column(nullable = false)
    private Integer need = 1;
}
