package com.example.moneybug.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 근무표 만들기 '미리보기'를 '적용'까지 이어주는 임시 저장소 (설계서 §3.11 planId).
 * 단일 서버 인스턴스 기준 메모리 저장이며 30분 뒤 만료된다. 서버를 늘리면 DB/캐시로 옮겨야 한다.
 */
@Component
public class SchedulePlanStore {

    private static final Duration TTL = Duration.ofMinutes(30);

    public record PlannedShift(Integer staffId, LocalDate date, int startMin, int endMin, String part, int breakMin) {}

    public record Plan(Integer storeId, Integer userId, LocalDate from, LocalDate to,
                       List<PlannedShift> shifts, Instant createdAt) {}

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    public String save(Plan plan) {
        purge();
        String id = UUID.randomUUID().toString();
        plans.put(id, plan);
        return id;
    }

    public Optional<Plan> find(String planId) {
        purge();
        return Optional.ofNullable(plans.get(planId));
    }

    public void remove(String planId) {
        plans.remove(planId);
    }

    private void purge() {
        Instant limit = Instant.now().minus(TTL);
        plans.values().removeIf(p -> p.createdAt().isBefore(limit));
    }
}
