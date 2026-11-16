package com.example.moneybug.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 월 근무표 메타(상태·출처·AI 코멘트). 근무 자체는 Schedule 행. */
@Entity
@Table(name = "month_schedule", uniqueConstraints = {
        @UniqueConstraint(name = "uk_month_schedule_store_month", columnNames = {"store_id", "month"})
})
@Getter
@Setter
@NoArgsConstructor
public class MonthSchedule {

    public enum Status {
        DRAFT, CONFIRMED;

        @JsonValue
        public String json() { return EnumJson.camel(this); }

        @JsonCreator
        public static Status fromJson(String v) { return EnumJson.parse(Status.class, v); }
    }

    public enum Source {
        AI, TEMPLATE, MANUAL;

        @JsonValue
        public String json() { return EnumJson.camel(this); }

        @JsonCreator
        public static Source fromJson(String v) { return EnumJson.parse(Source.class, v); }
    }

    /** 주휴수당 최소화/허용. */
    public enum HolidayPolicy {
        MINIMIZE, ALLOW;

        @JsonValue
        public String json() { return EnumJson.camel(this); }

        @JsonCreator
        public static HolidayPolicy fromJson(String v) { return EnumJson.parse(HolidayPolicy.class, v); }
    }

    /** 공평 배분/인건비 절감. */
    public enum Priority {
        FAIRNESS, COST;

        @JsonValue
        public String json() { return EnumJson.camel(this); }

        @JsonCreator
        public static Priority fromJson(String v) { return EnumJson.parse(Priority.class, v); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "month_schedule_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // yyyy-MM
    @Column(nullable = false, length = 7)
    private String month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "weekly_holiday_pay", nullable = false, length = 20)
    private HolidayPolicy weeklyHolidayPay = HolidayPolicy.MINIMIZE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.FAIRNESS;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
