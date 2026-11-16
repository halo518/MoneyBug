package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 이름 있는 주간 근무표 템플릿. 패턴은 JSON 배열(dayIndex 0=월, staffId, startMin, endMin, part, breakMin, kind, timeTbd). */
@Entity
@Table(name = "schedule_template")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "pattern_json", columnDefinition = "TEXT")
    private String patternJson = "[]";
}
