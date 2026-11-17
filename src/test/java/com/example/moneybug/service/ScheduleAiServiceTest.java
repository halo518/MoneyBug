package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleAiServiceTest {

    @Test
    void briefPromptHasIdsOnly() {
        String p = ScheduleAiService.briefPrompt("2026-11", List.of("#7 근무 40시간"),
                MonthSchedule.Priority.COST, MonthSchedule.HolidayPolicy.MINIMIZE);
        assertTrue(p.contains("#7 근무 40시간"));
        assertTrue(p.contains("2026-11"));
        assertTrue(p.contains("인건비 절감"));
        assertFalse(p.contains("김알바"));
    }

    @Test
    void nameBackReplacesIds() {
        assertEquals("김알바님이 7 아님 #9", ScheduleAiService.nameBack("#7님이 7 아님 #9", Map.of(7, "김알바")));
    }
}
