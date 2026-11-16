package com.example.moneybug.dto;

import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/** FE ScheduleTemplate. 패턴(shifts)은 patternJson 에 같은 모양(staffId 숫자)으로 저장한다. */
public class ScheduleTemplateDto {

    /** dayIndex 0=월 … 6=일. */
    public record Shift(int dayIndex, Integer staffId, int startMin, int endMin, String part, int breakMin,
                        Schedule.Kind kind, boolean timeTbd) {}

    public record Response(Integer id, Integer storeId, String name, LocalDateTime createdAt, List<Shift> shifts) {

        public static Response from(ScheduleTemplate t) {
            return new Response(t.getId(), t.getStore().getId(), t.getName(), t.getCreatedAt(),
                    parse(t.getPatternJson()));
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 깨진·빈 값은 빈 목록. */
    public static List<Shift> parse(String patternJson) {
        if (patternJson == null || patternJson.isBlank()) return List.of();
        try {
            return MAPPER.readValue(patternJson, new TypeReference<List<Shift>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static String toJson(List<Shift> shifts) {
        return MAPPER.writeValueAsString(shifts);
    }
}
