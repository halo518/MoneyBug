package com.example.moneybug.service;

import com.example.moneybug.entity.StoreWorker;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/** StoreWorker.fixedParts / workHours / availHours (JSON 텍스트) 읽기·쓰기. 항상 7칸(월~일)으로 돌려준다. */
public final class StaffPrefs {

    private StaffPrefs() {}

    public record WorkHours(int startMin, int endMin) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static List<List<String>> fixedParts(StoreWorker sw) {
        List<List<String>> out = new ArrayList<>();
        List<List<String>> raw = null;
        if (sw.getFixedParts() != null && !sw.getFixedParts().isBlank()) {
            try {
                raw = MAPPER.readValue(sw.getFixedParts(), new TypeReference<List<List<String>>>() {});
            } catch (RuntimeException e) {
                raw = null; // 깨진 값은 비어 있는 것으로 본다
            }
        }
        for (int i = 0; i < 7; i++) {
            out.add(raw != null && i < raw.size() && raw.get(i) != null ? List.copyOf(raw.get(i)) : List.of());
        }
        return out;
    }

    /** 저장된 값이 없으면 null. */
    public static List<WorkHours> workHours(StoreWorker sw) {
        return hours(sw.getWorkHours());
    }

    /** 알바 추가 근무 가능 시간. 저장된 값이 없으면 7칸 null. */
    public static List<WorkHours> availHours(StoreWorker sw) {
        List<WorkHours> h = hours(sw.getAvailHours());
        return h != null ? h : java.util.Arrays.asList(new WorkHours[7]);
    }

    private static List<WorkHours> hours(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<WorkHours> raw = MAPPER.readValue(json, new TypeReference<List<WorkHours>>() {});
            List<WorkHours> out = new ArrayList<>();
            for (int i = 0; i < 7; i++) out.add(i < raw.size() ? raw.get(i) : null);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void setFixedParts(StoreWorker sw, List<List<String>> value) {
        sw.setFixedParts(MAPPER.writeValueAsString(value));
    }

    public static void setWorkHours(StoreWorker sw, List<WorkHours> value) {
        sw.setWorkHours(value == null ? null : MAPPER.writeValueAsString(value));
    }

    public static void setAvailHours(StoreWorker sw, List<WorkHours> value) {
        sw.setAvailHours(value == null ? null : MAPPER.writeValueAsString(value));
    }
}
